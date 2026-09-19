"""Run the combined Nginx/Java image against a disposable database.

Requires an already-built image, Docker, and Python 3.9+; reads no .env file.
  python scripts/render_check.py --image roomflow-render:local --port 18088
Only a loopback HTTP port is published. Containers/network are removed on exit.
"""

import argparse
import base64
from http.cookiejar import CookieJar
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.request import HTTPCookieProcessor, Request, build_opener
import uuid

ROOT = Path(__file__).resolve().parents[1]


def docker(*args, env=None, check=True):
    result = subprocess.run(["docker", *args], cwd=ROOT, env=env, capture_output=True,
                            text=True, encoding="utf-8", errors="replace", timeout=60)
    if check and result.returncode:
        raise RuntimeError(f"Docker {args[0]} failed (exit {result.returncode})")
    return (result.stdout + (result.stderr if args[0] == "logs" else "")).strip()


def wait_until(predicate, message, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(1)
    raise RuntimeError(message)


class Client:
    def __init__(self, base):
        self.base = base
        self.cookies = CookieJar()
        self.opener = build_opener(HTTPCookieProcessor(self.cookies))
        self.token = None

    def request(self, method, path, body=None, expected=200):
        headers = {"Content-Type": "application/json"}
        if path.startswith("/api/"):
            headers["Origin"] = self.base
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        request = Request(self.base + path, method=method, headers=headers,
                          data=json.dumps(body).encode() if body is not None else None)
        try:
            response = self.opener.open(request, timeout=5)
        except HTTPError as error:
            response = error
        with response:
            if response.status != expected:
                raise AssertionError(f"{method} {path}: expected {expected}, received {response.status}")
            return response.read().decode("utf-8"), response.headers


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--port", type=int, default=18088)
    parser.add_argument("--memory", default="512m", help="Container memory limit, matching the free web-service size")
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("Use an unprivileged TCP port from 1024 to 65535")
    suffix = uuid.uuid4().hex[:12]
    network = f"roomflow-render-check-{suffix}"
    database = f"{network}-db"
    application = f"{network}-app"
    base = f"http://127.0.0.1:{args.port}"
    # Fresh values are passed only to these disposable containers; no project secrets are loaded.
    env = os.environ.copy()
    env.update({
        "POSTGRES_DB": "render_test", "POSTGRES_USER": "render_test",
        "POSTGRES_PASSWORD": secrets.token_hex(24),
        "JWT_SECRET": base64.b64encode(secrets.token_bytes(48)).decode("ascii"),
        "SPRING_PROFILES_ACTIVE": "docker",
        "SPRING_DATASOURCE_URL": "jdbc:postgresql://db:5432/render_test",
        "SPRING_FLYWAY_LOCATIONS": "classpath:db/migration",
        "PORT": "10000", "APP_PUBLIC_BASE_URL": base,
        "BOOTSTRAP_ADMIN_EMAIL": "", "BOOTSTRAP_ADMIN_PASSWORD": "",
        "AUTH_REFRESH_COOKIE_SECURE": "false",
        "S3_ENDPOINT": "http://127.0.0.1:9000", "S3_PUBLIC_ENDPOINT": "http://127.0.0.1:9000",
        "S3_ACCESS_KEY": "unused-test-access", "S3_SECRET_KEY": "unused-test-secret",
    })
    try:
        docker("network", "create", network)
        docker("run", "-d", "--name", database, "--network", network, "--network-alias", "db",
               "--tmpfs", "/var/lib/postgresql/data:rw", "-e", "POSTGRES_DB", "-e", "POSTGRES_USER",
               "-e", "POSTGRES_PASSWORD", "postgres:16-alpine", env=env)
        wait_until(lambda: "accepting connections" in docker("exec", database, "pg_isready", "-U", "render_test", "-d", "render_test", check=False),
                   "Temporary database did not start", timeout=60)
        names = ("POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD", "JWT_SECRET", "SPRING_PROFILES_ACTIVE",
                 "SPRING_DATASOURCE_URL", "SPRING_FLYWAY_LOCATIONS", "PORT", "APP_PUBLIC_BASE_URL",
                 "BOOTSTRAP_ADMIN_EMAIL", "BOOTSTRAP_ADMIN_PASSWORD", "AUTH_REFRESH_COOKIE_SECURE",
                 "S3_ENDPOINT", "S3_PUBLIC_ENDPOINT", "S3_ACCESS_KEY", "S3_SECRET_KEY")
        variables = [value for name in names for value in ("-e", name)]
        docker("run", "-d", "--name", application, "--network", network,
               "--memory", args.memory, "--publish", f"127.0.0.1:{args.port}:10000", *variables, args.image, env=env)
        client = Client(base)

        def healthy():
            state = docker("inspect", "--format", "{{.State.Running}}", application)
            if state == "false":
                logs = docker("logs", "--tail", "25", application)
                raise RuntimeError("Render container exited before becoming healthy:\n" + logs)
            try:
                body, _ = client.request("GET", "/healthz")
                return json.loads(body).get("status") == "UP"
            except (AssertionError, URLError, TimeoutError):
                return False

        wait_until(healthy, "Render image failed its HTTP health check")
        configured_user = docker("inspect", "--format", "{{.Config.User}}", application)
        if configured_user in ("", "0", "root"):
            raise AssertionError("Render runtime must use a non-root user")
        print("PASS: combined Nginx/Java image starts as non-root with an isolated database", flush=True)

        html, _ = client.request("GET", "/schedule")
        for marker in ("<title>", 'rel="canonical"', "application/ld+json", base):
            if marker not in html:
                raise AssertionError(f"Initial HTML is missing SEO marker: {marker}")
        asset = re.search(r'src="(/assets/[^\"]+\.js)"', html)
        if not asset:
            raise AssertionError("Initial HTML does not load the frontend entrypoint")
        client.request("GET", asset.group(1))
        missing, _ = client.request("GET", "/missing-render-check-page", expected=404)
        if "<html" not in missing:
            raise AssertionError("Unknown URL must render the frontend 404 page")
        client.request("GET", "/sitemap.xml")
        client.request("GET", "/robots.txt")
        print("PASS: initial SEO HTML, frontend asset, sitemap/robots and HTTP 404", flush=True)

        client.request("GET", "/api/v1/auth/me", expected=401)
        body, headers = client.request("POST", "/api/v1/auth/register", {
            "email": f"render-{suffix}@example.test", "password": "test-password-123",
        })
        client.token = json.loads(body)["token"]
        if "HttpOnly" not in headers.get("Set-Cookie", ""):
            raise AssertionError("Refresh cookie must be HttpOnly")
        client.request("GET", "/api/v1/auth/me")
        first_refresh = next(cookie.value for cookie in client.cookies if cookie.name == "refresh_token")
        body, _ = client.request("POST", "/api/v1/auth/refresh")
        client.token = json.loads(body)["token"]
        second_refresh = next(cookie.value for cookie in client.cookies if cookie.name == "refresh_token")
        if first_refresh == second_refresh:
            raise AssertionError("Refresh token was not rotated")
        client.request("GET", "/api/v1/auth/me")
        client.request("POST", "/api/v1/auth/logout", expected=204)
        client.request("POST", "/api/v1/auth/refresh", expected=401)
        print("PASS: registration, authenticated API, HttpOnly refresh rotation and logout", flush=True)
    finally:
        docker("rm", "-f", application, database, check=False)
        docker("network", "rm", network, check=False)


if __name__ == "__main__":
    main()
