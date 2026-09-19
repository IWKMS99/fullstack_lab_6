"""Generate local configuration once without committing secrets."""
from pathlib import Path
import secrets
import base64

root = Path(__file__).resolve().parents[1]
target = root / ".env"
if target.exists():
    raise SystemExit(".env already exists; no changes made")
values = {
    "POSTGRES_DB": "roomflow_db", "POSTGRES_USER": "roomflow",
    "POSTGRES_PASSWORD": secrets.token_hex(24), "SERVER_PORT": "8080",
    "JWT_SECRET": base64.b64encode(secrets.token_bytes(48)).decode("ascii"), "JWT_EXPIRATION": "900000",
    "JWT_ACCESS_EXPIRATION_MS": "900000", "JWT_REFRESH_EXPIRATION_MS": "604800000",
    "AUTH_REFRESH_COOKIE_SECURE": "false", "AUTH_REFRESH_COOKIE_SAME_SITE": "Lax",
    "BOOTSTRAP_ADMIN_EMAIL": "admin@roomflow.local",
    "BOOTSTRAP_ADMIN_PASSWORD": secrets.token_urlsafe(24),
}
storage_password = secrets.token_hex(24)
values.update({"MINIO_ROOT_USER": "roomflow-storage", "MINIO_ROOT_PASSWORD": storage_password,
    "S3_ACCESS_KEY": "roomflow-storage", "S3_SECRET_KEY": storage_password,
    "S3_BUCKET": "roomflow-files", "S3_REGION": "us-east-1",
    "S3_ENDPOINT": "http://minio:9000", "S3_PUBLIC_ENDPOINT": "http://localhost:9000",
    "S3_PRESIGN_TTL_MINUTES": "15"})
with target.open("x", encoding="utf-8") as output:
    output.write("".join(f"{key}={value}\n" for key, value in values.items()))
print("Created .env. First administrator credentials are in BOOTSTRAP_ADMIN_*.")
