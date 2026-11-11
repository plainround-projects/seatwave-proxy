#!/usr/bin/env bash
# Starts, tests and stops the Module 9 stack: SeatWave, its reverse proxy and the sidecar, under
# the Compose project name seatwave-proxy, every port on 127.0.0.1 only.
#
# Usage: scripts/stack.sh <up|down|test|status> [--run] [--yes]
#   default   a dry run: print what would happen, change nothing
#   --run     do it; asks for confirmation on the terminal first
#   --yes     skip the question (for a reader who has already read the plan)
#
#   up      build the jars and the TEST certificates, build the image, start the stack, wait for it
#   down    stop and remove every container, network and volume of the project (down -v)
#   test    up, ./gradlew stackTest, then down, on every path (also when a test fails)
#   status  list the project's containers (read-only, no --run needed)
#
# Guardrails: the only target is the project seatwave-proxy; Docker must answer first or nothing
# is done; every change appends an audit line to build/stack/audit.log; "down" checks afterwards
# that no container, network or volume of the project is left; any failure exits non-zero.
set -euo pipefail

readonly PROJECT="seatwave-proxy"
readonly HEALTH_URL="https://127.0.0.1:20900/health"
readonly CA_FILE="build/tls/seatwave-test-ca.pem"
readonly WAIT_SECONDS=60
readonly DOCKER_TIMEOUT_SECONDS=10

usage() {
  echo "usage: $0 <up|down|test|status> [--run] [--yes]" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
action="$1"
shift
mode="dry-run"
confirmed="no"
for option in "$@"; do
  case "$option" in
    --run) mode="run" ;;
    --yes) confirmed="yes" ;;
    *) usage ;;
  esac
done
case "$action" in up|down|test|status) ;; *) usage ;; esac

cd "$(dirname "$0")/.."
compose=(docker compose -p "$PROJECT")
# "down" names the tools profile too, so a container left by "docker compose run tools" goes as well.
compose_all=(docker compose -p "$PROJECT" --profile tools)

# Check before: Docker must answer, within a bound, or nothing is done.
docker_answers() {
  docker info --format '{{.ServerVersion}}' > /dev/null 2>&1 &
  local checker=$!
  for (( waited = 0; waited < DOCKER_TIMEOUT_SECONDS; waited++ )); do
    if ! kill -0 "$checker" 2> /dev/null; then
      wait "$checker"
      return $?
    fi
    sleep 1
  done
  kill "$checker" 2> /dev/null || true
  return 1
}
if ! command -v docker > /dev/null || ! docker_answers; then
  echo "Docker is not running (or does not answer within ${DOCKER_TIMEOUT_SECONDS} s)." >&2
  echo "Start Docker Desktop and run this again; nothing was started or stopped." >&2
  exit 1
fi

if [[ "$action" == "status" ]]; then
  "${compose[@]}" ps --all
  exit 0
fi

echo "plan: $action the Compose project $PROJECT"
case "$action" in
  up) echo "  ./gradlew -q stackPrepare; ${compose[*]} up -d --build; wait for $HEALTH_URL" ;;
  down) echo "  ${compose_all[*]} down -v --remove-orphans; check nothing of $PROJECT is left" ;;
  test) echo "  up, then ./gradlew stackTest, then down -v (always)" ;;
esac
if [[ "$mode" == "dry-run" ]]; then
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead with '$action' for $PROJECT? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi

mkdir -p build/stack
audit() {
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $1" >> build/stack/audit.log
}

up() {
  ./gradlew -q stackPrepare
  "${compose[@]}" up -d --build --quiet-pull
  audit "up: started $PROJECT"
  for (( waited = 0; waited < WAIT_SECONDS; waited++ )); do
    if curl -s -o /dev/null --max-time 2 --cacert "$CA_FILE" "$HEALTH_URL"; then
      echo "stack is up: $HEALTH_URL answered after ${waited} s"
      return 0
    fi
    sleep 1
  done
  echo "the stack did not answer $HEALTH_URL within ${WAIT_SECONDS} s" >&2
  "${compose[@]}" logs --tail 40 >&2
  return 1
}

down() {
  "${compose_all[@]}" down -v --remove-orphans
  audit "down: removed $PROJECT"
  # Check after: nothing of this project may be left.
  local left
  left="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT")"
  left+="$(docker network ls -q --filter "label=com.docker.compose.project=$PROJECT")"
  left+="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")"
  if [[ -n "$left" ]]; then
    echo "something of $PROJECT is still there: $left" >&2
    return 1
  fi
  echo "stack is down: no container, network or volume of $PROJECT is left"
}

case "$action" in
  up) up ;;
  down) down ;;
  test)
    # down runs on every path out of this block, including a failed test.
    trap 'down' EXIT
    up
    ./gradlew stackTest
    ;;
esac
