"""Check Compose recovery and reject a bad migration in an isolated temporary DB.

Run against a local test deployment, never a production project:
  python scripts/recovery_check.py --project roomflow --base-url http://localhost:8080

The script briefly stops app/db, always restarts them, preserves their volumes,
and creates disposable containers/network for the invalid-migration check.
"""

import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.request import urlopen
import uuid

ROOT = Path(__file__).resolve().parents[1]


def docker(*args, env=None, timeout=60, check=True):
    result = subprocess.run(
        ["docker", *args], cwd=ROOT, env=env, text=True, capture_output=True,
        encoding="utf-8", errors="replace", timeout=timeout,
    )
    if check and result.returncode:
        raise RuntimeError(f"Docker {args[0]} failed (exit {result.returncode})")
    return result.stdout.strip()


def inspect(container, template):
    return docker("inspect", "--format", template, container)


def http_health(base_url, timeout=40):
    try:
        with urlopen(base_url.rstrip("/") + "/healthz", timeout=timeout) as response:
            return response.status == 200 and json.load(response).get("status") == "UP"
    except (HTTPError, URLError, TimeoutError):
        return False


def wait_until(predicate, message, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(1)
    raise RuntimeError(message)


def service_id(project, compose_file, service):
    container = docker("compose", "-p", project, "-f", compose_file, "ps", "-q", service)
    if not container or "\n" in container:
        raise RuntimeError(f"Expected one running {service} container")
    labels = json.loads(inspect(container, "{{json .Config.Labels}}"))
    if labels.get("com.docker.compose.project") != project or labels.get("com.docker.compose.service") != service:
        raise RuntimeError("Compose labels do not match the requested test project")
    return container


def recovery(container, service, base_url):
    started = time.monotonic()
    try:
        docker("stop", "--time", "10", container)
        if http_health(base_url):
            raise RuntimeError(f"Health endpoint reported UP while {service} was stopped")
        print(f"PASS: health reports a failure while {service} is stopped", flush=True)
    finally:
        docker("start", container)
    wait_until(lambda: http_health(base_url), f"Application did not recover after restarting {service}")
    print(f"PASS: {service} recovery in {time.monotonic() - started:.1f}s; existing volumes preserved", flush=True)


def invalid_migration(app_image):
    suffix = uuid.uuid4().hex[:12]
    network = f"roomflow-migration-check-{suffix}"
    db_container = f"{network}-db"
    app_container = f"{network}-app"
    scratch = ROOT / "build" / "recovery"
    scratch.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env.update({
        "POSTGRES_DB": "recovery_test", "POSTGRES_USER": "recovery_test",
        "POSTGRES_PASSWORD": secrets.token_hex(24),
        "JWT_SECRET": base64.b64encode(secrets.token_bytes(48)).decode("ascii"),
        "SPRING_PROFILES_ACTIVE": "docker",
        "SPRING_DATASOURCE_URL": "jdbc:postgresql://db:5432/recovery_test",
        "SPRING_FLYWAY_LOCATIONS": "classpath:db/migration,filesystem:/failure",
        "BOOTSTRAP_ADMIN_EMAIL": "", "BOOTSTRAP_ADMIN_PASSWORD": "",
    })
    with tempfile.TemporaryDirectory(prefix="migration-", dir=scratch) as directory:
        # Cleanup is restricted to the fresh directory under this repository's build/.
        if not Path(directory).resolve().is_relative_to(scratch.resolve()):
            raise RuntimeError("Temporary migration path escaped the test directory")
        Path(directory, "V999__recovery_failure.sql").write_text("THIS IS NOT VALID SQL;\n", encoding="utf-8")
        try:
            docker("network", "create", network)
            docker("run", "-d", "--name", db_container, "--network", network,
                   "--network-alias", "db", "--tmpfs", "/var/lib/postgresql/data:rw",
                   "-e", "POSTGRES_DB", "-e", "POSTGRES_USER", "-e", "POSTGRES_PASSWORD",
                   "postgres:16-alpine", env=env)
            wait_until(
                lambda: "accepting connections" in docker("exec", db_container, "pg_isready", "-U", "recovery_test", "-d", "recovery_test", check=False),
                "Temporary PostgreSQL failed to start", timeout=60,
            )
            variables = [value for key in (
                "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD", "JWT_SECRET",
                "SPRING_PROFILES_ACTIVE", "SPRING_DATASOURCE_URL", "SPRING_FLYWAY_LOCATIONS",
                "BOOTSTRAP_ADMIN_EMAIL", "BOOTSTRAP_ADMIN_PASSWORD",
            ) for value in ("-e", key)]
            docker("run", "-d", "--name", app_container, "--network", network,
                   "--mount", f"type=bind,source={directory},target=/failure,readonly",
                   *variables, app_image, env=env)
            wait_until(lambda: inspect(app_container, "{{.State.Running}}") == "false",
                       "Application remained running after an invalid migration")
            exit_code = int(inspect(app_container, "{{.State.ExitCode}}"))
            logs = docker("logs", app_container)
            if exit_code == 0 or "V999__recovery_failure.sql" not in logs or "Flyway" not in logs:
                raise RuntimeError("Startup did not fail with the expected Flyway migration error")
            print(f"PASS: invalid Flyway migration prevents startup (exit {exit_code}) in isolated temporary DB", flush=True)
        finally:
            docker("rm", "-f", app_container, db_container, check=False)
            docker("network", "rm", network, check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="Explicit name of a disposable/local Compose deployment")
    parser.add_argument("--compose-file", default="docker-compose.prod.yml")
    parser.add_argument("--base-url", default="http://localhost:8080")
    args = parser.parse_args()
    app = service_id(args.project, args.compose_file, "app")
    db = service_id(args.project, args.compose_file, "db")
    image = inspect(app, "{{.Image}}")
    if not http_health(args.base_url):
        raise RuntimeError("Application must be healthy before testing recovery")
    recovery(app, "app", args.base_url)
    recovery(db, "db", args.base_url)
    invalid_migration(image)
    if not http_health(args.base_url):
        raise RuntimeError("Main deployment is unhealthy after the isolated migration test")
    print("PASS: main deployment is healthy; temporary containers and network removed", flush=True)


if __name__ == "__main__":
    main()
