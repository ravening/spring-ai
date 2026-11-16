#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

port="${MCP_SERVER_PORT:-8091}"
if command -v lsof >/dev/null 2>&1 && lsof -iTCP:"$port" -sTCP:LISTEN -n -P >/dev/null 2>&1; then
    echo "Port $port is already in use. Stop the other MCP server instance first." >&2
    exit 1
fi

echo "Starting MCP server on http://localhost:$port/mcp"
echo "Keep this terminal open. Start the agent app in another terminal."
exec mvn -Pmcp-server spring-boot:run
