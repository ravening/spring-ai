#!/usr/bin/env bash
# Crash demo for the durable agent run (temporal-durable-agent-poc.md, sections 16 and 21).
#
# Not part of `mvn test`: it needs a running Temporal dev server and takes minutes.
#
#   terminal 1:  temporal server start-dev
#   terminal 2:  ./scripts/temporal-crash-demo.sh
#
# The script starts the app with an artificial per-step delay, submits a run, waits
# until step 3 is RUNNING, kills the JVM with kill -9, restarts the app, and polls
# until the run COMPLETED. Completed steps must not be re-executed; inspect the
# attempts with `temporal workflow show --workflow-id <runId>` or the Temporal UI
# (http://localhost:8233).
set -euo pipefail

BASE_URL="${AGENT_URL:-http://localhost:8090}"
STEP_DELAY="${AGENT_TEMPORAL_STEP_DELAY:-15s}"
RUN_ID="crash-demo-$(date +%s)"
CONVERSATION_ID="crash-demo"
TASK="${1:-Compare the last three quarterly reports and write a summary}"

command -v jq >/dev/null || { echo "jq is required"; exit 1; }

view() { curl -sf "$BASE_URL/api/agent/runs/$1" | jq .; }

start_app() {
  local delay="$1"
  echo "==> starting app (step delay $delay)"
  AGENT_TEMPORAL_STEP_DELAY="$delay" ./mvnw -q spring-boot:run > /tmp/agent-crash-demo.log 2>&1 &
  APP_PID=$!
  echo "$APP_PID" > /tmp/agent-crash-demo.pid
  for i in $(seq 1 120); do
    curl -sf "$BASE_URL/actuator/health" >/dev/null 2>&1 && break
    sleep 1
  done
}

stop_app_9() {
  echo "==> kill -9 $APP_PID (simulating a JVM crash)"
  kill -9 "$APP_PID" 2>/dev/null || true
  wait "$APP_PID" 2>/dev/null || true
}

poll_until() { # runId, jq-expression, description
  local run_id="$1" expr="$2" desc="$3"
  for i in $(seq 1 240); do
    local status
    status=$(curl -sf "$BASE_URL/api/agent/runs/$run_id" 2>/dev/null | jq -r "$expr" || true)
    if [ -n "$status" ] && [ "$status" != "null" ]; then
      echo "==> $desc: $status"
      return 0
    fi
    sleep 1
  done
  echo "!! timed out waiting for $desc" >&2
  return 1
}

echo "==> submitting durable run $RUN_ID"
RESPONSE=$(curl -sf -X POST "$BASE_URL/api/agent/runs" \
  -H 'Content-Type: application/json' \
  -d "{\"task\":\"$TASK\",\"conversationId\":\"$CONVERSATION_ID\",\"runId\":\"$RUN_ID\"}")
echo "$RESPONSE" | jq .

start_app "$STEP_DELAY"

echo "==> waiting for step 3 to be RUNNING (lastCompletedStep == 2)"
poll_until "$RUN_ID" 'select(.currentStep == 3 and .lastCompletedStep == 2) | .status' "step 3 running"
view "$RUN_ID" >/dev/null

stop_app_9

echo "==> while the app is down, inspect the durable state:"
echo "    temporal workflow show --workflow-id $RUN_ID"
echo "    (open http://localhost:8233 -> namespace default -> workflow $RUN_ID)"

start_app 0s

echo "==> polling until the run completes after restart"
poll_until "$RUN_ID" 'select(.status == "COMPLETED") | .status' "run completed"

echo "==> final run view:"
view "$RUN_ID"

echo "==> verify history (expect one completion per step 1-2, retries on step 3, one workflow completion):"
echo "    temporal workflow show --workflow-id $RUN_ID | jq '.events[] | select(.eventType | tostring | test(\"ActivityTask|WorkflowExecution\")) | {eventId, eventType}'"
