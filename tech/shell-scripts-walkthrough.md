# The shell scripts: `stack.sh`, `spof.sh`, `curl-timings.sh` and `hop-timings.sh`

## What it is

A **shell** is a program that reads commands you type and runs them. A **shell script** is a text file of such commands, so you can run them again with one word. These four scripts are written for **Bash**, a widely used shell. The first line, `#!/usr/bin/env bash`, is the **shebang**: it tells the operating system which program should run the file. `env bash` finds Bash on your computer wherever it is installed.

`posix-shell.md` explains shell basics (commands, arguments, variables, quoting, exit codes, pipes) using the Gradle wrapper script. Read it first if those words are new. This note covers what these scripts add: Bash arrays, `[[ ... ]]` tests, functions, `trap`, background jobs, and a common pattern for scripts that change things (dry run, ask first, check before, check after, audit line).

## Why this repository uses it

The stack has three containers, a probe and a set of measurements. Typing every Docker and curl command by hand invites mistakes, and some of the commands stop or start containers. The scripts wrap each action in the same safe routine: do nothing unless told to (`--run`), ask for confirmation, refuse to touch anything but the project's own containers or the lab's own ports, write what was done to a log, and check the result.

## Where it appears

| Path | What it does |
|---|---|
| `scripts/stack.sh` | Starts, tests, stops and lists the Compose project `seatwave-proxy`. |
| `scripts/spof.sh` | The single-point-of-failure lab: stops one container (or reloads the proxy) while the probe runs. |
| `scripts/curl-timings.sh` | Times each target in a file with `curl -w` and writes one line per request. |
| `scripts/hop-timings.sh` | Times the extra hop from inside the proxy's own container. |
| `build/stack/audit.log` | The log the scripts append to (created at run time; not in git). |
| `config/targets-before-after.txt` | The targets file `curl-timings.sh` reads. |

## How it works underneath

1. **A command is a process.** The shell starts a program, waits for it and reads its **exit code**: 0 means success, anything else means failure. `$?` holds the last exit code.
2. **Three streams.** Every process has standard input (0), standard output (1) and standard error (2). `> file` sends output to a file, `>> file` appends, `2>&1` sends errors to the same place as output, `>&2` sends output to the error stream (used for messages that are not results), and `/dev/null` is a file that swallows everything.
3. **A pipe** (`a | b`) connects the output of `a` to the input of `b`.
4. **Variables** are set with `name=value` (no spaces) and read with `$name`. Always put the read in double quotes, `"$name"`, so a value with spaces stays one word.
5. **An array** holds a list: `names=(a b c)`. `"${names[@]}"` becomes one word per item. `${#names[@]}` is the count. `${!names[@]}` is the list of positions.
6. **Safety line.** `set -euo pipefail` at the top of each script turns Bash from "carry on after errors" into "stop at the first one". Each letter is explained in the first walkthrough below.
7. **These scripts talk to Docker.** `docker compose -p seatwave-proxy ...` acts on the containers of one named project; `docker exec <container> <command>` runs a command inside a running container.

## Walkthrough of this repository's use

### `scripts/stack.sh`: the header and the safety line

```bash
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
```

- Line 1 is the shebang. Lines 2 to 17 are comments (a `#` starts a comment). They document the usage: one of four actions, and the options `--run` and `--yes`. With no `--run` the script is a **dry run**: it prints what it would do and changes nothing. With `--run` it asks first, unless `--yes` skips the question for a reader who has already read the plan.
- The comment lists the four actions: `up` (build the jars and the TEST certificates, build the image, start the stack, wait for it), `down` (stop and remove every container, network and volume of the project), `test` (up, run the stack tests, then down, even if a test fails) and `status` (list the containers; read-only).
- The last comment block lists the **guardrails**: the only target is the project `seatwave-proxy`; Docker must answer first or nothing is done; every change appends an audit line; `down` checks afterwards that nothing of the project is left; any failure exits non-zero.
- Line 18, `set -euo pipefail`, three switches in one:
  - `-e`: stop the script when a command fails (returns a non-zero exit code). There are exceptions: a command used as the condition of an `if`, or on the left of `&&` or `||`, may fail without stopping the script.
  - `-u`: treat reading a variable that was never set as an error, instead of silently using an empty string. A typo in a name then stops the script.
  - `-o pipefail`: a pipe `a | b` normally has the exit code of `b` only. With this switch it fails if any stage failed.

### `scripts/stack.sh`: constants, arguments and the project

```bash
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
```

- Lines 20 to 24: `readonly NAME=value`. `readonly` makes the variable impossible to change later. `PROJECT` is the Compose project name. `HEALTH_URL` is the address the script asks to see that the stack is up (PRODUCTION's `/health`). `CA_FILE` is the TEST certificate authority curl must trust. `WAIT_SECONDS` is how long `up` waits for the stack (60 s). `DOCKER_TIMEOUT_SECONDS` is how long to wait for Docker to answer (10 s).
- Lines 26 to 29: `usage() { ... }` defines a **function**, a named group of commands that runs when you write its name. `echo "usage: $0 ..." >&2` prints the message on standard error. `$0` is the name the script was started with. `exit 2` ends the script with code 2, the usual code for "wrong use".
- Line 31: `[[ $# -ge 1 ]] || usage`. `[[ ... ]]` is Bash's test. `$#` is the number of arguments. `-ge` means "greater than or equal". `A || B` runs `B` only if `A` failed, so with no argument the script prints the usage and stops.
- Line 32: `action="$1"` stores the first argument (`up`, `down`, `test` or `status`). Line 33: `shift` throws the first argument away, so `"$@"` (all arguments) now holds only the options.
- Lines 34 and 35 set the defaults: `mode="dry-run"`, `confirmed="no"`.
- Lines 36 to 42 are a loop over the options: `for option in "$@"; do ... done`. The `case "$option" in ... esac` picks by text: `--run)` sets `mode="run"`, `--yes)` sets `confirmed="yes"`, `*)` (anything else) calls `usage`. The `;;` ends each branch.
- Line 43 checks the action itself: `case "$action" in up|down|test|status) ;; *) usage ;; esac`. A `|` between patterns means "or". The first branch does nothing (`;;` right away); every other word stops with the usage.
- Line 45: `cd "$(dirname "$0")/.."`. `$( ... )` runs a command and uses its output. `dirname "$0"` is the folder of the script (`scripts`). Adding `/..` is the repository's top folder. After this, relative paths such as `build/tls/...` work from wherever the script was started.
- Line 46: `compose=(docker compose -p "$PROJECT")` stores a command in an **array**. Later `"${compose[@]}" up -d` expands to `docker compose -p seatwave-proxy up -d`. An array keeps each piece as its own word, which a plain string would not.
- Lines 47 and 48: the second array, `compose_all`, adds `--profile tools`. The comment says why: `down` must name the `tools` profile, so a container left behind by `docker compose run tools` goes away too.

### `scripts/stack.sh`: ask Docker, with a time limit

```bash
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
```

- Line 50 is a comment: Docker must answer, within a bound, or nothing is done. `docker info` can hang when Docker Desktop is half-started, so the script does not wait for it forever.
- Lines 51 to 63 are the function `docker_answers`. Line 52 runs `docker info --format '{{.ServerVersion}}'` with the output thrown away (`> /dev/null 2>&1`) and a final `&`, which puts it in the **background**: the script goes on without waiting. Line 53, `local checker=$!`, saves the process number of the last background job (`$!`) in a variable that exists only inside the function.
- Line 54 is a counting loop: `for (( waited = 0; waited < DOCKER_TIMEOUT_SECONDS; waited++ ))`. The double parentheses give C-style arithmetic. It runs 10 times.
- Line 55, `if ! kill -0 "$checker" 2> /dev/null; then`: `kill -0` sends no signal; it only asks whether the process still exists. `!` negates the result. So this branch runs when the background job has finished. Line 56, `wait "$checker"`, collects its exit code and line 57, `return $?`, hands that code back to the caller: success if Docker answered.
- Line 59: `sleep 1` waits a second and the loop tries again.
- Lines 61 and 62: after ten seconds the script gives up: `kill "$checker" 2> /dev/null || true` stops the stuck job (the `|| true` makes sure a failed `kill` does not end the script), and `return 1` reports "no answer".
- Line 64: `if ! command -v docker > /dev/null || ! docker_answers; then`. `command -v docker` prints where the program `docker` is, or fails if it is not installed. The line reads: if Docker is not installed, or does not answer, then lines 65 to 67 print two messages to standard error and `exit 1`. Nothing was started or stopped.

### `scripts/stack.sh`: status, the plan, the dry run and the question

```bash
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
```

- Lines 70 to 73: `status` runs `docker compose -p seatwave-proxy ps --all` and exits 0. It is read-only, so it needs no `--run`.
- Line 75 prints the plan headline. Lines 76 to 80 print the commands the action would run. `${compose[*]}` (with `*`) joins the array into one string for printing.
- Lines 81 to 84: if `mode` is still `dry-run`, print that nothing changed and `exit 0`. This is the default.
- Lines 85 to 88: unless `--yes` was given, ask. `read -r -p "Go ahead ...? [y/N] " answer < /dev/tty`: `read` stores what you type in `answer`; `-p` shows the prompt; `-r` stops it from treating backslashes specially; `< /dev/tty` reads from your terminal itself, not from the script's input, so `yes | scripts/stack.sh ...` cannot answer for you. Only the exact answer `y` goes on (`[[ "$answer" == "y" ]] || { ...; exit 1; }`); anything else, including a bare Enter, stops. When there is no terminal at all, the redirect fails and the script stops with exit code 1: I ran `scripts/stack.sh down --run < /dev/null` in a tool without a terminal and got `/dev/tty: Device not configured` and exit code 1.
- Line 90 creates the folder for the log: `mkdir -p build/stack` (`-p` makes parent folders and does not complain if it exists).
- Lines 91 to 93: `audit()` appends one line to `build/stack/audit.log`: the time, from `date -u +%Y-%m-%dT%H:%M:%SZ` (`-u` is UTC; the format gives for example `2026-11-11T05:42:00Z`), and the message `$1` (the first argument of the function).

### `scripts/stack.sh`: `up`, `down` and the dispatch

```bash
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
```

- Line 96: `./gradlew -q stackPrepare` builds the jars and the TEST certificates (`gradle.md`; `-q` is quiet).
- Line 97: `"${compose[@]}" up -d --build --quiet-pull`. `up` creates and starts the containers; `-d` runs them in the background (detached); `--build` builds the image first (`docker.md`); `--quiet-pull` hides the progress bars when an image has to be downloaded.
- Line 98 writes the audit line.
- Lines 99 to 105: a loop up to 60 times. `curl -s -o /dev/null --max-time 2 --cacert "$CA_FILE" "$HEALTH_URL"`: `-s` is silent, `-o /dev/null` throws the answer body away, `--max-time 2` gives each try at most 2 seconds, `--cacert` names the authority to trust. The `if` is true when curl succeeded in getting any HTTP answer. Then the script prints how long it waited and `return 0`. If not, it sleeps one second and tries again.
- Lines 106 to 108: after 60 seconds, print an error and the last 40 lines of every container's log (`logs --tail 40`), both to standard error, and `return 1`.

```bash
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
```

- Line 112: `"${compose_all[@]}" down -v --remove-orphans`. `down` stops and removes the project's containers and network. `-v` also removes its volumes. `--remove-orphans` removes containers of the project that are no longer in the file.
- Line 113 writes the audit line.
- Lines 114 to 118: the **check after**. `local left` declares a variable, and line 116 assigns `left="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT")"`: all containers, running or not (`-a`), as bare ids (`-q`), that carry the project's label. Lines 117 and 118 append (`+=`) the networks and the volumes with the same label. Declaring `local` on its own line matters: `local x=$(cmd)` hides the command's exit code from `set -e`.
- Lines 119 to 122: if anything was found, print it and `return 1`. Line 123 prints the success message.

```bash
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
```

- Lines 126 to 135: the last `case` runs the chosen action: `up) up ;;` calls the function `up`, and `down) down ;;` calls `down`.
- The `test)` branch is the interesting one. Line 131, `trap 'down' EXIT`, registers `down` to run when the script ends by **any** route: normal end, a failed command under `set -e`, or Ctrl-C. Then it runs `up`, then `./gradlew stackTest`. If a test fails, `set -e` stops the script and the trap still brings the stack down.

### `scripts/spof.sh`: header, arguments and the plan

```bash
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
```

- Lines 1 to 14 are comments. The lab: while the probe asks for `/health` through the proxy every 100 ms, stop one container for 10 s and start it again, then print what the client saw and what the proxy logged. `reload` checks and reloads the proxy's configuration twice under the same probe, which is the rollout step of the course's Section 10. The guardrails: the stack must be up; the only containers touched are the named service of the project; the service is started again on every path out of the script; every stop and start is written to `build/stack/audit.log`.
- Line 15: `set -euo pipefail` (explained above).
- Lines 17 to 19: constants. The probe starts, the script waits `STOP_AFTER_SECONDS` (5), stops the service for `DOWN_FOR_SECONDS` (10), and starts it again.
- Line 21: with no argument, print the usage and exit 2. This line uses a **brace group** `{ ...; exit 2; }`, which runs several commands as one after `||`.
- Line 22, `action="$1"`, and line 23, `shift`, as before.
- Line 24: `case "$action" in proxy|backend|reload) ;; *) echo "..." >&2; exit 2 ;; esac` on one line: only these three words are accepted.
- Lines 25 and 26: `service="$action"`, then `[[ "$action" == "reload" ]] && service="proxy"`. `A && B` runs `B` only if `A` succeeded. So `reload` acts on the proxy service.
- Lines 27 to 35: defaults and the option loop; here an unknown option prints `unknown option` and exits 2.
- Line 36: `cd "$(dirname "$0")/.."` as in `stack.sh`. Line 37: `compose=(docker compose -p "$PROJECT")`.

### `scripts/spof.sh`: check before, plan, dry run, question

```bash
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
```

- Line 40: `"${compose[@]}" ps --status running --services 2> /dev/null | grep -qx "$service"`. `ps --status running --services` prints the names of the running services, one per line. The pipe sends them to `grep -qx "$service"`: `-q` quiet (only the exit code), `-x` the whole line must equal the word. So the check passes only if that service is running. Otherwise, line 41 prints `the <service> of seatwave-proxy is not running: run scripts/stack.sh up --run first` and exits 1. This check runs even in a dry run, so a dry run with the stack down exits 1 (I ran it).
- Lines 43 to 47: print the plan. `$(( STOP_AFTER_SECONDS + DOWN_FOR_SECONDS ))` is arithmetic: 5 + 10 = 15, "start it at 15 s".
- Lines 48 to 55: the dry-run exit and the question, as in `stack.sh`.

### `scripts/spof.sh`: the audit log and the safety net

```bash
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
```

- Lines 57 to 60: the folder and the `audit()` function. The line starts with `spof:` so it is easy to tell from `stack.sh`'s lines in the same file.
- Lines 61 and 62 are a comment: the service is started again on every path out of the script, even if the probe fails. The sidecar lives in SeatWave's network namespace, so it must restart with SeatWave to have one.
- Lines 63 to 66: `restore()` runs `docker compose ... start "$service"` with all output discarded and `|| true`, so it never fails. Line 65: if the service was `backend`, it also restarts the sidecar. The shape `[[ ... ]] && cmd || true` means: if the test is true run `cmd`; whatever happens, finish with success.
- Line 67: `trap restore EXIT` runs `restore` whenever the script ends: on success, on error, or on Ctrl-C.
- Line 69: `log_lines_before=$( ... )`. It counts the lines the proxy's log has now: `logs --no-log-prefix proxy 2>&1 | wc -l | tr -d ' '`. `wc -l` counts lines; on macOS it pads the number with spaces and `tr -d ' '` deletes the spaces. Later the script prints only lines after this point.
- Line 70: `./gradlew -q probe --args='config/probe.properties' > build/stack/probe-"$action".txt &`. It runs the probe task (`java-probe-walkthrough.md`) in the background (`&`), writing its output to a file named after the action. Line 71, `probe=$!`, remembers its process number. Line 72: `sleep 5` lets the probe collect five seconds of normal answers before anything is disturbed.

### `scripts/spof.sh`: the reload round, the stop and the report

```bash
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
```

- Lines 73 to 83 are the `reload` path. `for round in 1 2; do ... done` runs twice. Line 75: `docker exec "$PROJECT-proxy-1" nginx -t 2>&1 | sed 's/^/  /'` runs the configuration check inside the proxy container (`nginx.md`) and indents each output line with two spaces (`sed 's/^/  /'` replaces the start of a line with two spaces). Line 76: `nginx -s reload` tells NGINX to reload. Line 77 audits it, line 78 waits five seconds. Line 80, `wait "$probe"`, waits for the probe to finish (30 s in all). Line 81 prints the probe's report. Line 82, `exit 0`, ends the script; the trap runs `restore` (harmless).
- Line 84: `stopped_at=$(date -u +%H:%M:%S)` notes the time for the report.
- Line 85: `"${compose[@]}" stop "$service"` stops the container. Line 86 audits. Line 87: `sleep "$DOWN_FOR_SECONDS"`. Line 88: `start`. Line 89 audits. Line 90: wait for the probe.
- Lines 91 and 92: print when and for how long, then the probe's report.
- Lines 93 to 98 run only when the service was `backend`. Line 94 restarts the sidecar so it joins the new SeatWave network namespace. Line 95: `new_errors=$( ... )`. `logs --no-log-prefix proxy 2>&1` gets the log, `tail -n +"$(( log_lines_before + 1 ))"` keeps the lines after the earlier count, and `grep -E '\[error\].*GET /health' || true` keeps the error lines for `/health` (`-E` is extended patterns; `\[` is a literal bracket; `|| true` keeps `set -e` quiet when nothing matches).
- Line 96: `printf '%s' "$new_errors" | grep -c . || true` counts the lines; `grep -c .` counts lines that have at least one character. Line 97 prints the first and the last of them: `sed -n '1p;$p'` prints line 1 and the last line (`$` means last), and `sed 's/^[0-9/]* [0-9:]* //'` strips NGINX's date and time from the start.

### `scripts/curl-timings.sh`: header, constants and arguments

```bash
#!/usr/bin/env bash
# Times each target of a targets file with curl -w and writes one line per request, in the format
# the report task reads (src/main/java/io/plainround/seatwave/proxy/report/CurlLine.java). This is
# Module 5's script with two changes: only the lab's own ports on 127.0.0.1 are allowed (20900 to
# 20999), and a target may ask for gzip.
#
# Usage: scripts/curl-timings.sh <targets file> <runs> <output file> [--run] [--yes] [--reuse]
#   default   a dry run: print the plan and the curl command, send nothing
#   --run     send the requests; asks for confirmation on the terminal first
#   --yes     skip the question (for a reader who has already read the plan)
#   --reuse   each run fetches the URL twice on one connection
#
# A targets line is: <name> <url> [<CA file> [gzip]]. With "gzip", curl sends
# "Accept-Encoding: gzip" and does not decompress, so size= is the bytes that crossed the wire.
#
# Guardrails: a target is SeatWave or its proxy on 127.0.0.1 in Module 9's port range and nothing
# else; the output file must not exist yet; every run appends one audit line; any failure exits
# non-zero.
set -euo pipefail

readonly MAX_RUNS=50
readonly CONNECT_TIMEOUT_SECONDS=5
readonly MAX_TIME_SECONDS=10
readonly NAME_PATTERN='^[a-z0-9-]{1,40}$'
readonly LAB_PATTERN='^https?://127\.0\.0\.1:209[0-9]{2}/[A-Za-z0-9/?=&._-]*$'

usage() {
  echo "usage: $0 <targets file> <runs> <output file> [--run] [--yes] [--reuse]" >&2
  exit 2
}

[[ $# -ge 3 ]] || usage
targets_file="$1"
runs="$2"
out_file="$3"
shift 3
mode="dry-run"
confirmed="no"
reuse="no"
for option in "$@"; do
  case "$option" in
    --run) mode="run" ;;
    --yes) confirmed="yes" ;;
    --reuse) reuse="yes" ;;
    *) usage ;;
  esac
done
```

- Lines 1 to 18 are comments. This is Module 5's script with two changes: only the lab's own ports on `127.0.0.1` (20900 to 20999) are allowed, and a target may ask for gzip. A targets line is `<name> <url> [<CA file> [gzip]]`. With `gzip`, curl sends `Accept-Encoding: gzip` and does not decompress, so `size=` is the number of bytes that crossed the wire. `--reuse` makes each run fetch the URL twice on one connection. The guardrails: only SeatWave or its proxy on `127.0.0.1` in the module's range; the output file must not exist yet; every run appends an audit line; any failure exits non-zero.
- Line 19: `set -euo pipefail`.
- Lines 21 to 25: constants. `MAX_RUNS=50`. Two time limits for curl: 5 s to connect and 10 s in all. `NAME_PATTERN='^[a-z0-9-]{1,40}$'` is a **regular expression** (a pattern language for text): from the start `^` to the end `$`, 1 to 40 characters, each a lower-case letter, digit or dash. `LAB_PATTERN` is another: `http` or `https`, then `127.0.0.1` (the dots escaped with `\.`), a colon, `209` and two digits (ports 20900 to 20999), a slash and then only letters, digits and `/ ? = & . _ -`. A target with a space, a quote or a semicolon cannot match.
- Lines 27 to 30: `usage` with `exit 2`, as before.
- Line 32: `[[ $# -ge 3 ]] || usage`: at least three arguments. Lines 33 to 35 store them: the targets file, the number of runs, the output file. Line 36: `shift 3` removes those three; the rest are options.
- Lines 37 to 39: defaults (`dry-run`, not confirmed, no reuse). Lines 40 to 47: the option loop (`--run`, `--yes`, `--reuse`); anything else prints the usage.

### `scripts/curl-timings.sh`: check before, then read the targets file

```bash
# Check before: the tool, the inputs and the output.
command -v curl >/dev/null || { echo "curl is not installed" >&2; exit 1; }
[[ -f "$targets_file" ]] || { echo "no targets file: $targets_file" >&2; exit 2; }
[[ "$runs" =~ ^[0-9]+$ ]] && (( runs >= 1 && runs <= MAX_RUNS )) \
  || { echo "runs must be 1 to $MAX_RUNS" >&2; exit 2; }
[[ ! -e "$out_file" ]] || { echo "refusing to overwrite $out_file" >&2; exit 2; }

names=()
urls=()
cacerts=()
encodings=()
while read -r name url cacert encoding extra; do
  [[ -z "${name:-}" || "$name" == \#* ]] && continue
  [[ -z "${extra:-}" ]] || { echo "too many fields for $name" >&2; exit 2; }
  [[ "$name" =~ $NAME_PATTERN ]] || { echo "invalid name: $name" >&2; exit 2; }
  [[ "${url:-}" =~ $LAB_PATTERN ]] \
    || { echo "not an allowed target (127.0.0.1, ports 20900 to 20999): ${url:-}" >&2; exit 2; }
  if [[ -n "${cacert:-}" && ! -f "$cacert" ]]; then
    echo "no CA file $cacert for $name: run ./gradlew testCertificates" >&2
    exit 2
  fi
  [[ -z "${encoding:-}" || "$encoding" == "gzip" ]] \
    || { echo "the fourth field may only be gzip, for $name" >&2; exit 2; }
  names+=("$name")
  urls+=("$url")
  cacerts+=("${cacert:-}")
  encodings+=("${encoding:-}")
done < "$targets_file"
(( ${#names[@]} > 0 )) || { echo "the targets file lists no target" >&2; exit 2; }
```

- Line 50: `command -v curl >/dev/null || { echo "curl is not installed" >&2; exit 1; }`.
- Line 51: `[[ -f "$targets_file" ]]`: `-f` is true for an existing regular file; otherwise exit 2.
- Lines 52 and 53: the runs must be a number from 1 to 50. `[[ "$runs" =~ ^[0-9]+$ ]]` tests the text against a regular expression (one or more digits). `&& (( runs >= 1 && runs <= MAX_RUNS ))` is an arithmetic test. The `\` at the end of line 52 continues the command. `|| { ...; exit 2; }` stops with a message if either test failed.
- Line 54: `[[ ! -e "$out_file" ]]`: `-e` is "exists" and `!` negates. The script **refuses to overwrite** an earlier result.
- Lines 56 to 59: four empty arrays, one per field of a target.
- Lines 60 to 76: the loop. `while read -r name url cacert encoding extra; do ... done < "$targets_file"` reads the file line by line. `read` splits a line on spaces into the five variables (the last one, `extra`, catches anything left over). `< "$targets_file"` feeds the file to the loop.
  - Line 61: skip blank lines and comments: `[[ -z "${name:-}" || "$name" == \#* ]] && continue`. `-z` is "empty"; `${name:-}` is "the value, or empty if unset", needed because of `set -u`; `\#*` is a pattern for "starts with #"; `continue` goes to the next line.
  - Line 62: more than four fields means a mistake: `too many fields for <name>`.
  - Line 63: the name must match `NAME_PATTERN`. Note `$NAME_PATTERN` is on the right of `=~` and unquoted, so it is used as a pattern.
  - Lines 64 and 65: the URL must match `LAB_PATTERN`, or the script refuses with `not an allowed target (127.0.0.1, ports 20900 to 20999)`.
  - Lines 66 to 69: if a CA file is named it must exist, else the message says to run `./gradlew testCertificates`.
  - Lines 70 and 71: the fourth field may only be `gzip`.
  - Lines 72 to 75: `names+=("$name")` appends to an array; `"${cacert:-}"` stores an empty string when the field is missing.
- Line 77: `(( ${#names[@]} > 0 )) || ...`: at least one target must have been read.

### `scripts/curl-timings.sh`: the plan, the curl format, the dry run

```bash
per_run=1
[[ "$reuse" == "yes" ]] && per_run=2
expected_lines=$(( ${#names[@]} * runs * per_run ))
echo "plan: $runs run(s) x ${#names[@]} target(s) x $per_run request(s) = $expected_lines requests"
for index in "${!names[@]}"; do
  echo "  ${names[$index]}  ${urls[$index]}${encodings[$index]:+  (gzip)}"
done

timing_format() {
  # One line per request; the Server-Timing header comes last because it may hold spaces.
  printf '%s dns=%%{time_namelookup} connect=%%{time_connect} tls=%%{time_appconnect}' "$1"
  printf ' ttfb=%%{time_starttransfer} total=%%{time_total} http=%%{http_version}'
  printf ' code=%%{http_code} size=%%{size_download} connects=%%{num_connects}'
  printf ' encoding=%%header{content-encoding} cache=%%header{x-cache-status}'
  printf ' server=%%header{server-timing}\\n'
}

if [[ "$mode" == "dry-run" ]]; then
  echo "dry run: nothing sent. Each request would be:"
  echo "  curl -sS --connect-timeout $CONNECT_TIMEOUT_SECONDS --max-time $MAX_TIME_SECONDS -o /dev/null -w '$(timing_format NAME)' URL"
  echo "add --run to send them"
  exit 0
fi

if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Send $expected_lines requests to the targets above? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing sent" >&2; exit 1; }
fi
```

- Lines 79 and 80: `per_run=1`, and `per_run=2` with `--reuse` (two fetches per run). Line 81: `expected_lines=$(( ${#names[@]} * runs * per_run ))`: targets times runs times fetches. This is how many lines the output must have.
- Lines 82 to 85: print the plan. `for index in "${!names[@]}"` goes over the positions of the array. `${names[$index]}` is the item at a position. `${encodings[$index]:+  (gzip)}` prints `  (gzip)` only if that item is not empty.
- Lines 87 to 94: `timing_format()` prints curl's `-w` (write-out) format. Each `printf` line adds a part. `%%{time_namelookup}` in the `printf` becomes `%{time_namelookup}` for curl: in `printf`, `%%` prints a single `%`. The first `%s` is the target's name. Curl fills in the fields: time to look up the name (`dns`), to connect, to finish TLS (`tls`), to the first byte (`ttfb`), in total, the HTTP version, the status code, the downloaded size, the number of new connections, and three response headers (`content-encoding`, `x-cache-status`, `server-timing`). Line 88 notes why the `Server-Timing` header is last: it may contain spaces. `\\n` in single quotes becomes `\n` for curl, which curl turns into a new line. One request is one line.
- Lines 96 to 101: in a dry run, print the plan and the exact curl command (with `NAME` and `URL` as placeholders), then `exit 0`. Nothing was sent.
- Lines 103 to 106: the question, as in `stack.sh`. The prompt says how many requests will be sent.

### `scripts/curl-timings.sh`: send, then check after

```bash
mkdir -p "$(dirname "$out_file")"
audit_file="$(dirname "$out_file")/audit.log"
for index in "${!names[@]}"; do
  name="${names[$index]}"
  url="${urls[$index]}"
  arguments=(-sS --connect-timeout "$CONNECT_TIMEOUT_SECONDS" --max-time "$MAX_TIME_SECONDS")
  [[ -n "${cacerts[$index]}" ]] && arguments+=(--cacert "${cacerts[$index]}")
  [[ -n "${encodings[$index]}" ]] && arguments+=(-H "Accept-Encoding: gzip")
  arguments+=(-w "$(timing_format "$name")" -o /dev/null "$url")
  [[ "$reuse" == "yes" ]] && arguments+=(-o /dev/null "$url")
  for (( run = 1; run <= runs; run++ )); do
    curl "${arguments[@]}" >> "$out_file"
  done
done

# Check after: one line per request, or the run failed somewhere.
written=$(wc -l < "$out_file" | tr -d ' ')
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) sent $expected_lines requests to ${names[*]}; wrote $written lines to $out_file" >> "$audit_file"
if [[ "$written" != "$expected_lines" ]]; then
  echo "expected $expected_lines lines, but $out_file has $written" >&2
  exit 1
fi
echo "wrote $written lines to $out_file; audit line added to $audit_file"
```

- Lines 108 and 109: create the output folder (`dirname "$out_file"`) and name the audit file in it.
- Lines 110 to 121: for each target, build the curl command in an array. Line 113: `arguments=(-sS --connect-timeout ... --max-time ...)`. `-sS` means silent (no progress meter) but still show errors. Line 114: add `--cacert <file>` if the target has a CA file. Line 115: add `-H "Accept-Encoding: gzip"` if it asks for gzip. Line 116: add `-w "<format>" -o /dev/null "$url"`: the format, discard the body, the URL. Line 117: with `--reuse` add `-o /dev/null "$url"` again, so curl fetches the URL a second time on the same connection (curl keeps the connection open between the two URLs of one command).
- Lines 118 to 120: `for (( run = 1; run <= runs; run++ ))` runs curl `runs` times, each time appending its line to the output: `curl "${arguments[@]}" >> "$out_file"`. If curl fails, `set -e` stops the script with curl's exit code.
- Lines 123 to 130: **check after**. `written=$(wc -l < "$out_file" | tr -d ' ')` counts the lines written. Line 125 appends an audit line to `audit.log` next to the output, with the time, the number of requests and the file. Lines 126 to 129: if the count is not what was expected, print both numbers and `exit 1`. Line 130 prints the success message.

### `scripts/hop-timings.sh`

```bash
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
[[ "$runs" =~ ^[0-9]+$ ]] && (( runs >= 1 && runs <= MAX_RUNS )) \
  || { echo "runs must be 1 to $MAX_RUNS" >&2; exit 2; }
[[ ! -e "$out_file" ]] || { echo "refusing to overwrite $out_file" >&2; exit 2; }
format='%s dns=%%{time_namelookup} connect=%%{time_connect} tls=%%{time_appconnect} ttfb=%%{time_starttransfer} total=%%{time_total} http=%%{http_version} code=%%{http_code} size=%%{size_download} connects=%%{num_connects} server=%%header{server-timing}\\n'
```

- Lines 1 to 12 are comments. The extra hop is measured **inside** the Docker virtual machine, without Docker Desktop's port forwarder in the path. The curl inside the proxy's own container asks SeatWave directly (`10.209.9.20:8080`) and through the proxy on its loopback address (`127.0.0.1:8080`). Both use the same curl, the same request and Module 5's `-w` format, so the report task can read the output. Without `--run` it is a dry run. There is no question when it runs, because everything stays inside one container and the lab network.
- Line 13: `set -euo pipefail`.
- Lines 15 to 18: constants. `CONTAINER` is the proxy container's name (the project name, the service and `-1`). The two URLs are fixed.
- Line 20: `[[ $# -ge 2 ]] || { ...; exit 2; }`: at least the runs and the output file. Lines 21 and 22 store them. Lines 23 to 25: the same runs check (1 to 50) and refusal to overwrite as in `curl-timings.sh`.
- Line 26: `format='...'`. A single-quoted string, so the shell leaves `%` and `\` alone. It has the same fields as `curl-timings.sh`, except there is no `encoding` or `cache` header. `%s` is filled later by `printf` with the target name.

```bash
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
```

- Lines 28 to 30: print the plan.
- Lines 31 to 34: the dry run. `"${3:-}" != "--run"` looks at the third argument, if any; the script goes on only when it is exactly `--run`.
- Lines 35 and 36: **check before**. `docker inspect --format '{{.State.Running}}' "$CONTAINER" 2> /dev/null | grep -qx true` is true only when Docker says the container is running (`grep -qx true` matches the whole line `true`). Otherwise the script says to run `stack.sh up` first and exits 1.
- Line 38: create the folder.
- Lines 39 to 47: the loop. `for name in hop-direct hop-proxy` picks the label. Lines 40 and 41 choose the URL (`url="$DIRECT_URL"`, then change to the proxy's if `name` is `hop-proxy`). Lines 42 to 46: for each run, `docker exec "$CONTAINER" curl -sS --max-time 10 -o /dev/null -w "$(printf "$format" "$name")" "$url" -o /dev/null "$url" >> "$out_file"`. `docker exec` runs a command inside the container. `$(printf "$format" "$name")` fills the name into the format. Giving the URL twice makes curl fetch it twice on one connection: the first request is on a **new** connection, the second **reused**. Line 43 is a comment for the linter ShellCheck: the format string comes from a variable on purpose.
- Lines 48 to 51: **check after**. Count lines; append an audit line; `(( written == runs * 4 ))` checks the count (2 targets times 2 requests per run); otherwise print both numbers and exit 1.

## Try it yourself

I ran these on 11 November 2026 in a **copy** of the repository. The copy differed from the original in three lines: `PROJECT` in `stack.sh` and `spof.sh` and `CONTAINER` in `hop-timings.sh` named `seatwave-proxy-notes` instead of `seatwave-proxy`, so my run could not touch any other project. So the output below says `seatwave-proxy-notes` where yours says `seatwave-proxy`. Timings and times differ on every run. Every command here is safe: the first four only print.

1. The dry run of `stack.sh` and a refusal:

```
scripts/stack.sh up
scripts/stack.sh jump; echo "exit $?"
```

Output:

```
plan: up the Compose project seatwave-proxy-notes
  ./gradlew -q stackPrepare; docker compose -p seatwave-proxy-notes up -d --build; wait for https://127.0.0.1:20900/health
dry run: nothing changed. Add --run to do it.
usage: scripts/stack.sh <up|down|test|status> [--run] [--yes]
exit 2
```

2. The refusals of `curl-timings.sh`. First make three bad targets files:

```
mkdir -p build/try
printf 'bad http://example.com:20900/x\n' > build/try/outside.txt
printf 'ok http://127.0.0.1:20911/health nofile\n' > build/try/nocafile.txt
printf 'ok http://127.0.0.1:20911/health a b c\n' > build/try/toomany.txt
for f in outside nocafile toomany; do scripts/curl-timings.sh build/try/$f.txt 1 build/try/$f.out; echo "exit $?"; done
scripts/curl-timings.sh config/targets-before-after.txt 51 build/try/x.out; echo "exit $?"
```

Output:

```
not an allowed target (127.0.0.1, ports 20900 to 20999): http://example.com:20900/x
exit 2
no CA file nofile for ok: run ./gradlew testCertificates
exit 2
too many fields for ok
exit 2
runs must be 1 to 50
exit 2
```

3. The dry run of `curl-timings.sh` (it prints the plan and the curl command, and sends nothing; the plan is 2 runs times 7 targets times 2 requests):

```
scripts/curl-timings.sh config/targets-before-after.txt 2 build/try/out.txt --reuse | head -3
```

Output:

```
plan: 2 run(s) x 7 target(s) x 2 request(s) = 28 requests
  direct-https  https://127.0.0.1:20910/events/1/seats?section=A
  direct-http  http://127.0.0.1:20911/events/1/seats?section=A
```

4. A real run needs the stack (`scripts/stack.sh up --run --yes`). It sends 28 requests, all to `127.0.0.1`:

```
scripts/curl-timings.sh config/targets-before-after.txt 2 build/try/out.txt --run --yes --reuse | tail -1
head -2 build/try/out.txt
cat build/try/audit.log
```

Output (mine; the numbers differ):

```
wrote 28 lines to build/try/out.txt; audit line added to build/try/audit.log
direct-https dns=0.000084 connect=0.000228 tls=0.090978 ttfb=0.094342 total=0.098164 http=1.1 code=200 size=36894 connects=1 encoding= cache= server=app;dur=0.83
direct-https dns=0.000012 connect=0.000000 tls=0.000000 ttfb=0.001998 total=0.004449 http=1.1 code=200 size=36894 connects=0 encoding= cache= server=app;dur=0.60
2026-11-11T05:41:35Z sent 28 requests to direct-https direct-http proxy-http proxy-tls proxy-no-reuse proxy-sidecar production; wrote 28 lines to build/try/out.txt
```

The second line shows `connects=0` and `tls=0.000000`: curl reused the connection, so there was no new TCP or TLS work.

5. `hop-timings.sh` with the stack up (the run takes under a second for 3 runs):

```
scripts/hop-timings.sh 3 build/try/hop.txt --run
head -1 build/try/hop.txt
```

Output:

```
plan: inside seatwave-proxy-notes-proxy-1, 3 run(s) x 2 targets x 2 requests (new, then reused)
  hop-direct  http://10.209.9.20:8080/events/1/seats?section=A
  hop-proxy   http://127.0.0.1:8080/events/1/seats?section=A
wrote 12 lines to build/try/hop.txt
hop-direct dns=0.000010 connect=0.000161 tls=0.000000 ttfb=0.001398 total=0.001529 http=1.1 code=200 size=36894 connects=1 server=app;dur=0.54
```

6. The reload under the probe (about 30 s). It needs the stack up and changes nothing permanent:

```
scripts/spof.sh reload --run --yes
```

Output (the times and process numbers differ):

```
plan: probe https://127.0.0.1:20900/health for 30 s; nginx -t and nginx -s reload at 5 s and again 5 s later
  nginx: the configuration file /etc/nginx/nginx.conf syntax is ok
  nginx: configuration file /etc/nginx/nginx.conf test is successful
2026/11/11 05:42:00 [notice] 193#193: signal process started
  nginx: the configuration file /etc/nginx/nginx.conf syntax is ok
  nginx: configuration file /etc/nginx/nginx.conf test is successful
2026/11/11 05:42:06 [notice] 207#207: signal process started
probe: GET https://127.0.0.1:20900/health every 100 ms for 30 s, one client, HTTP/1.1 keep-alive
  0.000 s to  29.907 s  200                    x  300  latency p50     7.38 ms, max   135.98 ms
outage: none that ended inside the probe
```

I also ran `scripts/spof.sh proxy --run --yes` and `scripts/spof.sh backend --run --yes`. They stopped the container for 10 s and the probe saw an outage of 10.409 s and 11.013 s. `reverse-proxy-concepts.md` has the table. Afterwards `cat build/stack/audit.log` listed every stop, start and reload with the time.

Finally, stop what you started: `scripts/stack.sh down --run --yes`. It ends with `stack is down: no container, network or volume of seatwave-proxy is left`.

## Mistakes people make

- **Forgetting to quote variables.** `rm $dir` with `dir="my files"` is two words. These scripts quote every `"$variable"` and every `"${array[@]}"`.
- **Trusting `set -e` too much.** It does not stop a failure inside an `if` condition or on the left of `&&` or `||`. That is why `stack.sh` checks `down`'s result explicitly and why `spof.sh` ends `restore` with `|| true`.
- **Running a script that changes things by default.** All four scripts do nothing unless given `--run` (and three ask first).
- **Parsing `ls` or `docker ps` output with spaces.** The scripts use `-q` and `--filter` options that give clean, one-per-line output.
- **Leaving the stack up after a failed test.** `stack.sh test` uses `trap ... EXIT`, so `down` runs on every path.
- **Reading a number with a leading zero.** In Bash arithmetic `010` is octal, 8. `curl-timings.sh` accepts the text `010` as a run count (its plan then says `010 run(s)` but sends 8 runs of requests), and the text `08` makes `(( ... ))` print `value too great for base`. Type plain numbers. (This is a defect in line 52 of the script; see my report.)
- **A targets file without a newline at the end of the last line.** `read` returns failure for a last line with no newline, so `curl-timings.sh` silently ignores that line. I tested it with a two-line file: the plan listed one target. End every targets file with a newline.
- **Hard-coded names.** `hop-timings.sh` names the container `seatwave-proxy-proxy-1`. If you start the project under another name, the script cannot find it.

## Check yourself

1. What are the three letters of `set -euo pipefail`, and what does each do?
   *`-e` stops at the first failing command, `-u` makes an unset variable an error, and `-o pipefail` makes a pipe fail if any stage failed.*
2. How does `stack.sh` make sure `down` runs even when a stack test fails?
   *In the `test` branch it registers `trap 'down' EXIT` before running anything, and an EXIT trap runs on every way out of the script.*
3. Why does `curl-timings.sh` read the confirmation from `/dev/tty`?
   *So that input piped into the script cannot answer the question. A person at a terminal must.*
4. What does the pattern `LAB_PATTERN` stop?
   *A target that is not `http` or `https` on `127.0.0.1` with a port from 20900 to 20999, or that has characters outside letters, digits and `/ ? = & . _ -` in its path.*
5. In `hop-timings.sh`, why is the URL given twice to one curl command?
   *Curl fetches both on one connection: the first request opens it (a new connection) and the second reuses it.*

## Further reading

- GNU Bash reference manual: https://www.gnu.org/software/bash/manual/bash.html
- The shell command language (POSIX): https://pubs.opengroup.org/onlinepubs/9799919799/utilities/V3_chap02.html
- `curl` man page, including `--write-out`: https://curl.se/docs/manpage.html
- `docker compose` command line (`up`, `down`, `ps`, `logs`, `exec`, `run`): https://docs.docker.com/reference/cli/docker/compose/
- `docker exec`: https://docs.docker.com/reference/cli/docker/container/exec/
- Filtering with labels: https://docs.docker.com/engine/cli/filter/
- ShellCheck (the linter named in a comment of `hop-timings.sh`): https://www.shellcheck.net/wiki/SC2059
