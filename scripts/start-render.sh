#!/usr/bin/env bash
set -euo pipefail
export PORT="${PORT:-10000}"
export SERVER_PORT=8080
export APP_PUBLIC_BASE_URL="${APP_PUBLIC_BASE_URL:-${RENDER_EXTERNAL_URL:-http://localhost:${PORT}}}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--XX:MaxRAMPercentage=60 -XX:+UseSerialGC}"
if [[ -n "${DATABASE_URL:-}" ]]; then
  # Render provides credentials separately; JDBC needs only host, port and database.
  database_address="${DATABASE_URL#*://}"
  database_address="${database_address##*@}"
  export SPRING_DATASOURCE_URL="jdbc:postgresql://${database_address}"
fi
envsubst '${PORT}' < /app/nginx.template > /tmp/nginx.conf
java -jar /app/app.jar &
java_pid=$!
nginx -c /tmp/nginx.conf -g 'daemon off;' &
nginx_pid=$!
cleanup() {
  kill -TERM "$java_pid" "$nginx_pid" 2>/dev/null || true
  wait "$java_pid" "$nginx_pid" 2>/dev/null || true
}
trap cleanup EXIT TERM INT
wait -n "$java_pid" "$nginx_pid"
