#!/usr/bin/env bash
#
# Convenience entry point for the Car Rental service.
#
#   ./run.sh            start the service (Ctrl-C to stop)
#   ./run.sh test       run the whole test suite
#   ./run.sh build      compile, test and package
#   ./run.sh demo       start the service, walk the API with curl, shut it down
#   ./run.sh help       this message
#
# The JDK is handled by the Gradle toolchain (Java 21); nothing needs to be on the PATH
# except a JVM Gradle itself can start.

set -euo pipefail

readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly GRADLE="${PROJECT_DIR}/gradlew"
readonly PORT="${PORT:-8080}"
readonly BASE_URL="http://localhost:${PORT}"
readonly START="2026-09-07T10:00:00"
readonly STARTUP_TIMEOUT_SECONDS=90

cd "${PROJECT_DIR}"

# --- output helpers ---------------------------------------------------------

if [[ -t 1 ]]; then
    readonly BOLD=$'\033[1m' DIM=$'\033[2m' RED=$'\033[31m' GREEN=$'\033[32m' RESET=$'\033[0m'
else
    readonly BOLD='' DIM='' RED='' GREEN='' RESET=''
fi

step()  { printf '\n%s==> %s%s\n' "${BOLD}" "$*" "${RESET}"; }
info()  { printf '%s%s%s\n' "${DIM}" "$*" "${RESET}"; }
ok()    { printf '%s%s%s\n' "${GREEN}" "$*" "${RESET}"; }
fail()  { printf '%s%s%s\n' "${RED}" "$*" "${RESET}" >&2; }
die()   { fail "$*"; exit 1; }

# Pretty-prints JSON when python3 is around, otherwise passes it through untouched.
pretty_json() {
    if command -v python3 >/dev/null 2>&1; then
        python3 -m json.tool 2>/dev/null || cat
    else
        cat
    fi
}

# --- commands ---------------------------------------------------------------

cmd_app() {
    step "Starting the car rental service on port ${PORT}"
    info "Fleet comes from src/main/resources/application.yml. Ctrl-C to stop."
    exec "${GRADLE}" bootRun --console=plain --args="--server.port=${PORT}"
}

cmd_test() {
    step "Running the test suite"
    "${GRADLE}" test --console=plain
    ok "All tests passed. Report: build/reports/tests/test/index.html"
}

cmd_build() {
    step "Building (compile + test + package)"
    "${GRADLE}" clean build --console=plain
    ok "Built $(find build/libs -name '*.jar' ! -name '*-plain.jar' | head -1)"
}

# --- demo -------------------------------------------------------------------

APP_PID=""
APP_LOG=""

stop_app() {
    if [[ -n "${APP_PID}" ]] && kill -0 "${APP_PID}" 2>/dev/null; then
        step "Shutting the service down"
        kill "${APP_PID}" 2>/dev/null || true
    fi
    # bootRun starts the application in a child JVM, so also clear whatever still holds the port
    local listeners
    listeners="$(lsof -ti:"${PORT}" 2>/dev/null || true)"
    [[ -n "${listeners}" ]] && kill -9 ${listeners} 2>/dev/null || true
    [[ -n "${APP_LOG}" && -f "${APP_LOG}" ]] && rm -f "${APP_LOG}"
    return 0
}

start_app_in_background() {
    if lsof -ti:"${PORT}" >/dev/null 2>&1; then
        die "Port ${PORT} is already in use. Free it, or run: PORT=8081 ./run.sh demo"
    fi

    APP_LOG="$(mktemp -t carrental-demo)"
    trap stop_app EXIT INT TERM

    step "Starting the service on port ${PORT}"
    "${GRADLE}" bootRun --console=plain --args="--server.port=${PORT}" > "${APP_LOG}" 2>&1 &
    APP_PID=$!

    local waited=0
    until curl -sf -o /dev/null "${BASE_URL}/api/availability?carType=SUV&startDateTime=${START}&days=1"; do
        if ! kill -0 "${APP_PID}" 2>/dev/null; then
            fail "The service stopped during start-up:"
            tail -30 "${APP_LOG}" >&2
            exit 1
        fi
        if (( waited >= STARTUP_TIMEOUT_SECONDS )); then
            fail "The service did not come up within ${STARTUP_TIMEOUT_SECONDS}s:"
            tail -30 "${APP_LOG}" >&2
            exit 1
        fi
        sleep 1
        waited=$(( waited + 1 ))
    done
    ok "Up after ${waited}s"
}

# Sends a request and prints the status line plus the pretty-printed body.
# call GET|POST <path> [json body]
call() {
    local method="$1" path="$2" body="${3:-}" response status
    if [[ -n "${body}" ]]; then
        info "${method} ${path}  ${body}"
        response="$(curl -s -w $'\n%{http_code}' -X "${method}" "${BASE_URL}${path}" \
            -H 'Content-Type: application/json' -d "${body}")"
    else
        info "${method} ${path}"
        response="$(curl -s -w $'\n%{http_code}' "${BASE_URL}${path}")"
    fi
    status="${response##*$'\n'}"
    printf 'HTTP %s\n' "${status}"
    printf '%s' "${response%$'\n'*}" | pretty_json
    LAST_BODY="${response%$'\n'*}"
}

json_field() {
    python3 -c 'import json,sys; print(json.loads(sys.argv[1])[sys.argv[2]])' "$1" "$2"
}

cmd_demo() {
    command -v curl >/dev/null 2>&1 || die "curl is required for the demo"
    start_app_in_background

    step "1. How many SUVs are free that week?"
    call GET "/api/availability?carType=SUV&startDateTime=${START}&days=3"

    step "2. Reserve one - a type, a start and a number of days is the whole request"
    call POST "/api/reservations" \
        "{\"carType\":\"SUV\",\"startDateTime\":\"${START}\",\"days\":3}"
    local reservation_id
    reservation_id="$(json_field "${LAST_BODY}" reservationId)"

    step "3. Read that reservation back"
    call GET "/api/reservations/${reservation_id}"

    step "4. Availability now shows one car of that type in use"
    call GET "/api/availability?carType=SUV&startDateTime=${START}&days=3"

    step "4b. The same question without naming a type - what can I get that week?"
    info "carType is only a filter; leave it out and every configured type is reported."
    call GET "/api/availability?startDateTime=${START}&days=3"

    step "5. Capacity returns the moment a rental ends"
    info "Back-to-back rentals share a car, so this fits even when the fleet is one car."
    call POST "/api/reservations" \
        "{\"carType\":\"SUV\",\"startDateTime\":\"2026-09-10T10:00:00\",\"days\":2}"

    step "5b. Capacity is counted over intervals, not per calendar day"
    info "Booking every VAN from 08:00, then asking for one from 07:00 the same day - refused."
    call POST "/api/reservations" \
        "{\"carType\":\"VAN\",\"startDateTime\":\"2026-10-01T08:00:00\",\"days\":1}"
    call POST "/api/reservations" \
        "{\"carType\":\"VAN\",\"startDateTime\":\"2026-10-01T08:00:00\",\"days\":1}"
    call POST "/api/reservations" \
        "{\"carType\":\"VAN\",\"startDateTime\":\"2026-10-01T07:00:00\",\"days\":1}"
    info "But one collected at 08:00 the NEXT day fits: the first pair is back by then."
    call POST "/api/reservations" \
        "{\"carType\":\"VAN\",\"startDateTime\":\"2026-10-02T08:00:00\",\"days\":1}"

    step "6. Exhaust the vans (the fleet has 2), then ask for a third"
    call POST "/api/reservations" \
        "{\"carType\":\"VAN\",\"startDateTime\":\"${START}\",\"days\":1}"
    call POST "/api/reservations" \
        "{\"carType\":\"VAN\",\"startDateTime\":\"${START}\",\"days\":1}"
    info "This one has nowhere to go - 409, not 400: the request was perfectly valid."
    call POST "/api/reservations" \
        "{\"carType\":\"VAN\",\"startDateTime\":\"${START}\",\"days\":1}"

    step "7. A car type the business does not offer is a bad request, not a capacity problem"
    info "LORRY is not a key under car-rental.fleet.counts - 400, and the answer lists what is."
    call POST "/api/reservations" \
        "{\"carType\":\"LORRY\",\"startDateTime\":\"${START}\",\"days\":1}"

    step "8. An invalid request is rejected field by field"
    info "A missing car type and a zero-day rental - both reported, not just the first."
    call POST "/api/reservations" \
        "{\"startDateTime\":\"${START}\",\"days\":0}"

    step "9. An unknown reservation"
    call GET "/api/reservations/00000000-0000-0000-0000-000000000000"

    ok $'\nDemo complete.'
}

cmd_help() {
    # The header comment block is the usage message; stop at the first non-comment line.
    awk 'NR > 1 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
}

# --- dispatch ---------------------------------------------------------------

case "${1:-app}" in
    app|run|start|'') cmd_app ;;
    test)             cmd_test ;;
    build)            cmd_build ;;
    demo)             cmd_demo ;;
    help|-h|--help)   cmd_help ;;
    *)                fail "Unknown command: $1"; echo; cmd_help; exit 1 ;;
esac
