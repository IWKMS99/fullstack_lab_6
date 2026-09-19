"""Deterministic Nager.Date test double. Run only inside the isolated test network."""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

state = {"mode": "ok"}
lock = Lock()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            return self.reply(200, {"status": "up"})
        parts = self.path.split("?")[0].strip("/").split("/")
        if len(parts) != 5 or parts[:3] != ["api", "v3", "PublicHolidays"]:
            return self.reply(404, {"message": "unknown route"})
        year, country = parts[3:]
        with lock:
            mode = state["mode"]
        if mode == "error" or country == "ZZ":
            return self.reply(503, {"message": "simulated provider outage"})
        holidays = [] if mode == "empty" else [{"date": f"{year}-01-01", "localName": "New Year", "name": "New Year", "countryCode": country, "global": True}]
        return self.reply(200, holidays)

    def do_POST(self):
        if self.path != "/__admin/mode":
            return self.reply(404, {})
        try:
            payload = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        except (ValueError, json.JSONDecodeError):
            return self.reply(400, {})
        if payload.get("mode") not in {"ok", "empty", "error"}:
            return self.reply(400, {"message": "Expected ok, empty or error"})
        with lock:
            state["mode"] = payload["mode"]
        return self.reply(200, payload)

    def reply(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8090), Handler).serve_forever()
