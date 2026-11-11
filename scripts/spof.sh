#!/usr/bin/env bash
# The single-point-of-failure lab (Chapter 9.4): while the probe asks for /health through the
# proxy every 100 ms, stop one container for 10 s and start it again, then print what the client
# saw and what the proxy logged. "reload" instead checks and reloads the proxy's configuration
# twice under the same probe, the rollout step of Section 10.
#
# Usage: scripts/spof.sh <proxy|backend|reload> [--run] [--yes]
#   default   a dry run: print the plan, change nothing
#   --run     do it; asks for confirmation on the terminal first
#   --yes     skip the question
#
# Guardrails: the stack must be up (scripts/stack.sh up --run); the only containers touched are
# the named service of the project seatwave-proxy; the service is started again on every path out
# of this script; every stop and start is written to build/stack/audit.log; failures exit non-zero.
set -euo pipefail

readonly PROJECT="seatwave-proxy"
readonly STOP_AFTER_SECONDS=5
readonly DOWN_FOR_SECONDS=10

[[ $# -ge 1 ]] || { echo "usage: $0 <proxy|backend|reload> [--run] [--yes]" >&2; exit 2; }
action="$1"
shift
case "$action" in proxy|backend|reload) ;; *) echo "the first argument is proxy, backend or reload" >&2; exit 2 ;; esac
service="$action"
[[ "$action" == "reload" ]] && service="proxy"
mode="dry-run"
confirmed="no"
for option in "$@"; do
  case "$option" in
    --run) mode="run" ;;
    --yes) confirmed="yes" ;;
    *) echo "unknown option $option" >&2; exit 2 ;;
  esac
done
cd "$(dirname "$0")/.."
compose=(docker compose -p "$PROJECT")

# Check before: the service must be running.
"${compose[@]}" ps --status running --services 2> /dev/null | grep -qx "$service" \
  || { echo "the $service of $PROJECT is not running: run scripts/stack.sh up --run first" >&2; exit 1; }

if [[ "$action" == "reload" ]]; then
  echo "plan: probe https://127.0.0.1:20900/health for 30 s; nginx -t and nginx -s reload at ${STOP_AFTER_SECONDS} s and again 5 s later"
else
  echo "plan: probe https://127.0.0.1:20900/health for 30 s; stop $service at ${STOP_AFTER_SECONDS} s, start it at $(( STOP_AFTER_SECONDS + DOWN_FOR_SECONDS )) s"
fi
if [[ "$mode" == "dry-run" ]]; then
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead with '$action' on $service of $PROJECT? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi

mkdir -p build/stack
audit() {
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) spof: $1" >> build/stack/audit.log
}
# The service is started again on every path out of this script, even if the probe fails. The
# sidecar lives in SeatWave's network namespace, so it must restart with SeatWave to have one.
restore() {
  "${compose[@]}" start "$service" > /dev/null 2>&1 || true
  [[ "$service" == "backend" ]] && "${compose[@]}" restart sidecar > /dev/null 2>&1 || true
}
trap restore EXIT

log_lines_before=$("${compose[@]}" logs --no-log-prefix proxy 2>&1 | wc -l | tr -d ' ')
./gradlew -q probe --args='config/probe.properties' > build/stack/probe-"$action".txt &
probe=$!
sleep "$STOP_AFTER_SECONDS"
if [[ "$action" == "reload" ]]; then
  for round in 1 2; do
    docker exec "$PROJECT-proxy-1" nginx -t 2>&1 | sed 's/^/  /'
    docker exec "$PROJECT-proxy-1" nginx -s reload
    audit "reloaded the proxy configuration (round $round)"
    sleep "$STOP_AFTER_SECONDS"
  done
  wait "$probe"
  cat build/stack/probe-"$action".txt
  exit 0
fi
stopped_at=$(date -u +%H:%M:%S)
"${compose[@]}" stop "$service" > /dev/null 2>&1
audit "stopped $service"
sleep "$DOWN_FOR_SECONDS"
"${compose[@]}" start "$service" > /dev/null 2>&1
audit "started $service"
wait "$probe"
echo "stopped $service at $stopped_at UTC for ${DOWN_FOR_SECONDS} s"
cat build/stack/probe-"$action".txt
if [[ "$service" == "backend" ]]; then
  "${compose[@]}" restart sidecar > /dev/null 2>&1
  new_errors=$("${compose[@]}" logs --no-log-prefix proxy 2>&1 | tail -n +"$(( log_lines_before + 1 ))" | grep -E '\[error\].*GET /health' || true)
  echo "the proxy logged $(printf '%s' "$new_errors" | grep -c . || true) error line(s) for /health while SeatWave was down; the first and the last:"
  printf '%s\n' "$new_errors" | sed -n '1p;$p' | sed 's/^[0-9/]* [0-9:]* //'
fi
