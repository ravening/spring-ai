#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

port="${AGENT_PORT:-8090}"
if command -v lsof >/dev/null 2>&1 && lsof -iTCP:"$port" -sTCP:LISTEN -n -P >/dev/null 2>&1; then
    echo "Port $port is already in use. Stop the other agent instance first." >&2
    exit 1
fi

provider="${AI_PROVIDER:-deeprelay}"
if [[ "$provider" == "azure" || "${SPRING_PROFILES_ACTIVE:-}" == *azure* ]]; then
    provider="azure"
    if [[ -z "${AZURE_OPENAI_API_KEY:-}" || -z "${AZURE_OPENAI_ENDPOINT:-}" ]]; then
        echo "AI_PROVIDER=azure requires AZURE_OPENAI_ENDPOINT and AZURE_OPENAI_API_KEY." >&2
        exit 1
    fi
elif [[ -z "${DEEPRELAY_API_KEY:-}" ]]; then
    echo "DEEPRELAY_API_KEY is not set. Export your model API key before starting the agent." >&2
    exit 1
fi

echo "Starting agent app + UI on http://localhost:$port (provider: $provider)"
echo "Keep this terminal open. Start the MCP server in another terminal first."
exec mvn spring-boot:run
