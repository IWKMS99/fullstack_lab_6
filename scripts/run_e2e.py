"""Build an isolated real E2E stack, execute browser tests and always clean up."""
from pathlib import Path
import os
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT.name.replace("_", "-") + "-e2e"
COMPOSE = ["docker", "compose", "-p", PROJECT, "-f", "docker-compose.test.yml"]


def run(args, cwd=ROOT, check=True):
    return subprocess.run(args, cwd=cwd, check=check)


if __name__ == "__main__":
    npm = shutil.which("npm.cmd" if os.name == "nt" else "npm")
    if not npm:
        raise SystemExit("npm was not found")
    try:
        # The project has only ephemeral test containers and tmpfs data.
        run(COMPOSE + ["down", "--volumes", "--remove-orphans"])
        run(COMPOSE + ["up", "--build", "-d", "--wait", "--wait-timeout", "180"])
        run([npm, "run", "test:e2e:real"], ROOT / "frontend")
    except subprocess.CalledProcessError:
        run(COMPOSE + ["logs", "--tail", "100"], check=False)
        raise
    finally:
        run(COMPOSE + ["down", "--volumes", "--remove-orphans"], check=False)
