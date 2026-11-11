#!/usr/bin/env bash
# The extra hop measured inside the Docker virtual machine, without Docker Desktop's port
# forwarder in the path: the curl inside the proxy's own container asks SeatWave directly
# (10.209.9.20:8080) and through the proxy on its loopback address (127.0.0.1:8080). Both use the
# same curl, the same request and Module 5's -w format, so the report task reads the output.
#
# Usage: scripts/hop-timings.sh <runs> <output file> [--run]
#   default   a dry run: print the plan, send nothing
#   --run     send the requests (no question: they stay inside one container and the lab network)
#
# Guardrails: the only container used is the proxy of the project seatwave-proxy, the two URLs
# are fixed, the output file must not exist yet, one audit line per run, non-zero exit on failure.
set -euo pipefail

readonly CONTAINER="seatwave-proxy-proxy-1"
readonly MAX_RUNS=50
readonly DIRECT_URL="http://10.209.9.20:8080/events/1/seats?section=A"
readonly PROXY_URL="http://127.0.0.1:8080/events/1/seats?section=A"

[[ $# -ge 2 ]] || { echo "usage: $0 <runs> <output file> [--run]" >&2; exit 2; }
runs="$1"
out_file="$2"
# No leading zero: bash would read "08" as a broken octal number and "010" as 8.
[[ "$runs" =~ ^[1-9][0-9]*$ ]] && (( runs <= MAX_RUNS )) \
  || { echo "runs must be 1 to $MAX_RUNS" >&2; exit 2; }
[[ ! -e "$out_file" ]] || { echo "refusing to overwrite $out_file" >&2; exit 2; }
format='%s dns=%%{time_namelookup} connect=%%{time_connect} tls=%%{time_appconnect} ttfb=%%{time_starttransfer} total=%%{time_total} http=%%{http_version} code=%%{http_code} size=%%{size_download} connects=%%{num_connects} server=%%header{server-timing}\\n'

echo "plan: inside $CONTAINER, $runs run(s) x 2 targets x 2 requests (new, then reused)"
echo "  hop-direct  $DIRECT_URL"
echo "  hop-proxy   $PROXY_URL"
if [[ "${3:-}" != "--run" ]]; then
  echo "dry run: nothing sent. Add --run to send them."
  exit 0
fi
docker inspect --format '{{.State.Running}}' "$CONTAINER" 2> /dev/null | grep -qx true \
  || { echo "$CONTAINER is not running: run scripts/stack.sh up --run first" >&2; exit 1; }

mkdir -p "$(dirname "$out_file")"
for name in hop-direct hop-proxy; do
  url="$DIRECT_URL"
  [[ "$name" == "hop-proxy" ]] && url="$PROXY_URL"
  for (( run = 1; run <= runs; run++ )); do
    # shellcheck disable=SC2059
    docker exec "$CONTAINER" curl -sS --max-time 10 -o /dev/null -w "$(printf "$format" "$name")" \
      "$url" -o /dev/null "$url" >> "$out_file"
  done
done
written=$(wc -l < "$out_file" | tr -d ' ')
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) hop-timings: $written lines to $out_file" >> "$(dirname "$out_file")/audit.log"
(( written == runs * 4 )) || { echo "expected $(( runs * 4 )) lines, got $written" >&2; exit 1; }
echo "wrote $written lines to $out_file"
