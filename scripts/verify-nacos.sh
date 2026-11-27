#!/usr/bin/env bash
# Exercise a running Nacos 2.5.5 server backed by this PostgreSQL plugin.
# Requires: bash, curl, jq. Response bodies are temporary and are never printed.
set -euo pipefail

BASE_URL="${NACOS_BASE_URL:-http://127.0.0.1:8848}"
BASE_URL="${BASE_URL%/}"
NAMESPACE_ID="${NACOS_NAMESPACE_ID:-}"
GROUP="${VERIFY_GROUP:-CI_POSTGRESQL_GROUP}"
TEST_ID="${VERIFY_TEST_ID:-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}-$$}"
DATA_ID="${VERIFY_DATA_ID:-ci-postgresql-${TEST_ID}.properties}"
READY_TIMEOUT="${VERIFY_READY_TIMEOUT:-180}"
LOG_DIR="${NACOS_LOG_DIR:-${NACOS_HOME:-}/logs}"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nacos-pg-verify.XXXXXX")"
HTTP_BODY="$WORK_DIR/response.body"
HTTP_STATUS=""
CURL_ARGS=(--silent --show-error --connect-timeout 5 --max-time 20)

cleanup() {
    rm -rf -- "$WORK_DIR"
}
trap cleanup EXIT

fail() {
    printf '[FAIL] %s\n' "$*" >&2
    exit 1
}

pass() {
    printf '[PASS] %s\n' "$*"
}

step() {
    printf '\n==> %s\n' "$*"
}

# Deliberately do not use curl -f: HTTP_STATUS must be asserted explicitly.
# Callers pass either --get or --request METHOD plus encoded form/query fields.
request() {
    : > "$HTTP_BODY"
    if ! HTTP_STATUS="$(curl "${CURL_ARGS[@]}" --output "$HTTP_BODY" --write-out '%{http_code}' "$@")"; then
        HTTP_STATUS="000"
    fi
}

assert_status() {
    local expected="$1"
    local operation="$2"
    [ "$HTTP_STATUS" = "$expected" ] || \
        fail "$operation: expected HTTP $expected, got $HTTP_STATUS (response body suppressed)."
}

assert_publish_result() {
    local operation="$1"
    assert_status 200 "$operation"
    jq -e 'type == "object" and .code == 0 and .data == true' "$HTTP_BODY" >/dev/null || \
        fail "$operation: expected a Nacos v2 success envelope with data=true."
}

get_config() {
    local expected_content="$1"
    local gray_name="${2:-}"
    local description="$3"
    local args=(
        --get "$BASE_URL/nacos/v2/cs/config"
        --data-urlencode "dataId=$DATA_ID"
        --data-urlencode "group=$GROUP"
        --data-urlencode "namespaceId=$NAMESPACE_ID"
    )
    if [ -n "$gray_name" ]; then
        args+=(--data-urlencode "grayName=$gray_name")
    fi
    # POST persists to PostgreSQL before the local dump cache updates; retry
    # brief 404s so the immediate read observes the published configuration.
    for attempt in {1..30}; do
        request "${args[@]}"
        [ "$HTTP_STATUS" = "404" ] || break
        sleep 1
    done
    assert_status 200 "$description"
    jq -e --arg content "$expected_content" '
        type == "object" and .code == 0 and .data == $content
    ' "$HTTP_BODY" >/dev/null || fail "$description: returned configuration content does not exactly match."
}

get_deleted_config() {
    local gray_name="${1:-}"
    local description="$2"
    local args=(
        --get "$BASE_URL/nacos/v2/cs/config"
        --data-urlencode "dataId=$DATA_ID"
        --data-urlencode "group=$GROUP"
        --data-urlencode "namespaceId=$NAMESPACE_ID"
    )
    if [ -n "$gray_name" ]; then
        args+=(--data-urlencode "grayName=$gray_name")
    fi
    request "${args[@]}"
    for attempt in {1..30}; do
        if [ "$HTTP_STATUS" = "404" ]; then
            return 0
        fi
        if [ "$HTTP_STATUS" = "200" ] && jq -e 'type == "object" and .code == 0 and .data == null' "$HTTP_BODY" >/dev/null; then
            return 0
        fi
        sleep 1
        request "${args[@]}"
    done
    fail "$description: expected deleted configuration to be absent (HTTP 404 or code=0 with null data)."
}

publish_formal() {
    local content="$1"
    local description="$2"
    request --request POST "$BASE_URL/nacos/v2/cs/config" \
        --data-urlencode "dataId=$DATA_ID" \
        --data-urlencode "group=$GROUP" \
        --data-urlencode "namespaceId=$NAMESPACE_ID" \
        --data-urlencode "content=$content" \
        --data-urlencode "appName=ci-postgresql-e2e" \
        --data-urlencode "type=properties" \
        --data-urlencode "desc=$description" \
        --data-urlencode "src_user=ci-e2e"
    assert_publish_result "publish formal configuration"
}

publish_beta() {
    local content="$1"
    request --request POST "$BASE_URL/nacos/v2/cs/config" \
        --data-urlencode "dataId=$DATA_ID" \
        --data-urlencode "group=$GROUP" \
        --data-urlencode "namespaceId=$NAMESPACE_ID" \
        --data-urlencode "content=$content" \
        --data-urlencode "appName=ci-postgresql-e2e" \
        --data-urlencode "type=properties" \
        --data-urlencode "src_user=ci-e2e" \
        --data-urlencode "betaIps=127.0.0.1"
    assert_publish_result "publish beta gray configuration"
}

delete_config() {
    local gray_name="${1:-}"
    local required="${2:-true}"
    local args=(
        --get --request DELETE "$BASE_URL/nacos/v2/cs/config"
        --data-urlencode "dataId=$DATA_ID"
        --data-urlencode "group=$GROUP"
        --data-urlencode "namespaceId=$NAMESPACE_ID"
    )
    if [ -n "$gray_name" ]; then
        args+=(--data-urlencode "grayName=$gray_name")
    fi
    request "${args[@]}"
    if [ "$required" = "true" ]; then
        assert_publish_result "delete ${gray_name:-formal} configuration"
    else
        assert_status 200 "pre-test cleanup of ${gray_name:-formal} configuration"
        jq -e 'type == "object" and .code == 0 and (.data | type == "boolean")' \
            "$HTTP_BODY" >/dev/null || fail "Pre-test cleanup did not return a valid v2 envelope."
    fi
}

# Nacos 2.5.5 v1 history returns a Page, whose records contain content,
# publishType, grayName and extInfo (mapped from publish_type/gray_name/ext_info).
request_history() {
    request --get "$BASE_URL/nacos/v1/cs/history?search=accurate" \
        --data-urlencode "dataId=$DATA_ID" \
        --data-urlencode "group=$GROUP" \
        --data-urlencode "tenant=$NAMESPACE_ID" \
        --data-urlencode "pageNo=1" \
        --data-urlencode "pageSize=100"
    assert_status 200 "read configuration history"
    jq -e 'type == "object" and (.pageItems | type == "array")' "$HTTP_BODY" >/dev/null || \
        fail "History response is not a Nacos v1 Page with a pageItems array."
}

assert_formal_history() {
    local operation="$1"
    local expected_content="$2"
    local expected_description="$3"
    request_history
    jq -e --arg data_id "$DATA_ID" --arg group "$GROUP" --arg tenant "$NAMESPACE_ID" \
        --arg operation "$operation" --arg content "$expected_content" --arg description "$expected_description" '
        def matches: [.pageItems[] | select(
            .dataId == $data_id and .group == $group and (.tenant // "") == $tenant
            and .opType == $operation
        )];
        (matches | length == 1)
        and (matches[0] |
            has("publishType") and has("grayName") and has("extInfo")
            and .publishType == "formal" and .grayName == ""
            and (.extInfo | type == "string")
            and ((.extInfo | fromjson).c_desc == $description)
            and ((.extInfo | fromjson).type == "properties")
            and ((.extInfo | fromjson).src_user == "ci-e2e")
        )
    ' "$HTTP_BODY" >/dev/null || \
        fail "History missing exactly one formal opType=$operation row with empty grayName and expected extInfo metadata."
}

assert_gray_history() {
    local operation="$1"
    local expected_content="$2"
    request_history
    jq -e --arg data_id "$DATA_ID" --arg group "$GROUP" --arg tenant "$NAMESPACE_ID" \
        --arg operation "$operation" --arg content "$expected_content" '
        def matches: [.pageItems[] | select(
            .dataId == $data_id and .group == $group and (.tenant // "") == $tenant
            and .opType == $operation
        )];
        (matches | length == 1)
        and (matches[0] |
            has("publishType") and has("grayName") and has("extInfo")
            and .publishType == "gray" and .grayName == "beta"
            and (.extInfo | type == "string")
            and ((.extInfo | fromjson).gray_name == "beta")
            and ((.extInfo | fromjson).gray_rule | type == "string")
            and (((.extInfo | fromjson).gray_rule | fromjson).type == "beta")
            and (((.extInfo | fromjson).gray_rule | fromjson).expr == "127.0.0.1")
            and (((.extInfo | fromjson).gray_rule | fromjson).version == "1.0.0")
        )
    ' "$HTTP_BODY" >/dev/null || \
        fail "History missing exactly one gray opType=$operation row with grayName=beta and valid extInfo/gray_rule."
}

wait_until_ready() {
    local deadline=$((SECONDS + READY_TIMEOUT))
    local last_status="000"
    step "Waiting up to ${READY_TIMEOUT}s for Nacos readiness (namespaceId='$NAMESPACE_ID')"
    while [ "$SECONDS" -lt "$deadline" ]; do
        request --get "$BASE_URL/nacos/v1/console/health/readiness"
        last_status="$HTTP_STATUS"
        if [ "$HTTP_STATUS" = "200" ] && [ "$(<"$HTTP_BODY")" = "OK" ]; then
            pass "Nacos readiness returned HTTP 200 with body OK"
            return 0
        fi
        sleep 2
    done
    fail "Nacos was not ready within ${READY_TIMEOUT}s (last HTTP status $last_status)."
}

check_logs() {
    local bad_files=""
    step "Checking Nacos logs for MapperManager/PostgreSQL failures"
    if [ -z "${NACOS_HOME:-}" ] && [ -z "${NACOS_LOG_DIR:-}" ]; then
        fail "Set NACOS_HOME or NACOS_LOG_DIR so logs can be checked."
    fi
    [ -d "$LOG_DIR" ] || fail "Nacos log directory does not exist: $LOG_DIR"
    shopt -s nullglob globstar
    local logs=("$LOG_DIR"/**/*.log "$LOG_DIR"/**/*.out)
    shopt -u nullglob globstar
    [ "${#logs[@]}" -gt 0 ] || fail "No Nacos .log/.out files found in $LOG_DIR"
    # Match errors on either side of MapperManager/PostgreSQL; also catch common
    # JDBC/plugin failures whose exception details continue on following lines.
    bad_files="$(grep -Eil \
        -e 'ERROR.*MapperManager|MapperManager.*(ERROR|Exception|not found|failed)' \
        -e 'ERROR.*PostgreSQL|PostgreSQL.*(ERROR|Exception|failed)' \
        -e 'PSQLException|CannotGetJdbcConnectionException|DataAccessException|\[db-error\]' \
        -- "${logs[@]}" || true)"
    if [ -n "$bad_files" ]; then
        printf '%s\n' "$bad_files" | sed 's/^/[FAIL] Suspicious log: /' >&2
        fail "MapperManager/PostgreSQL-related log check failed; inspect the redacted failure-log artifact."
    fi
    pass "No MapperManager/PostgreSQL exception signatures found in ${#logs[@]} log files"
}

for tool in curl jq; do
    command -v "$tool" >/dev/null 2>&1 || fail "Required command not found: $tool"
done
case "$READY_TIMEOUT" in
    ''|*[!0-9]*) fail "VERIFY_READY_TIMEOUT must be a non-negative integer" ;;
esac

CONTENT_ONE=$'mode=full\ncolor=blue\n'
CONTENT_TWO=$'mode=full\ncolor=green\nrevision=2\n'
GRAY_CONTENT=$'mode=beta\ncolor=gray\n'
DESCRIPTION_ONE='CI PostgreSQL create'
DESCRIPTION_TWO='CI PostgreSQL update'

wait_until_ready

step "Preparing isolated test data: dataId=$DATA_ID group=$GROUP"
delete_config "" false
delete_config beta false

step "1/8 publish and get a formal configuration through the v2 API"
publish_formal "$CONTENT_ONE" "$DESCRIPTION_ONE"
get_config "$CONTENT_ONE" "" "get newly published formal configuration"
pass "Formal publish and exact-content get succeeded"

step "2/8 verify persisted INSERT history and ext_info metadata"
assert_formal_history I "$CONTENT_ONE" "$DESCRIPTION_ONE"
pass "Formal INSERT history has publishType=formal, grayName=\"\", and expected extInfo"

step "3/8 update and get the formal configuration"
publish_formal "$CONTENT_TWO" "$DESCRIPTION_TWO"
get_config "$CONTENT_TWO" "" "get updated formal configuration"
pass "Formal update and exact-content get succeeded"

step "4/8 verify persisted UPDATE history"
assert_formal_history U "$CONTENT_ONE" "$DESCRIPTION_ONE"
pass "Formal UPDATE history retains the previous content and expected extInfo"

step "5/8 publish/get/delete a beta gray configuration and verify its history"
publish_beta "$GRAY_CONTENT"
get_config "$GRAY_CONTENT" beta "get beta gray configuration"
assert_gray_history I "$GRAY_CONTENT"
delete_config beta true
get_deleted_config beta "get deleted beta gray configuration"
assert_gray_history D "$GRAY_CONTENT"
pass "Gray CRUD and history verify publishType=gray, grayName=beta, and extInfo"

step "6/8 delete the formal configuration"
delete_config "" true
pass "Formal delete returned a successful v2 response"

step "7/8 verify deleted formal configuration cannot be read"
get_deleted_config "" "get deleted formal configuration"
pass "Deleted formal configuration returned v2 code=0 with null data"

step "8/8 verify persisted DELETE history"
assert_formal_history D "$CONTENT_TWO" "$DESCRIPTION_TWO"
pass "Formal DELETE history retains the last content and expected extInfo"

check_logs
printf '\n[PASS] Nacos PostgreSQL v2 CRUD, gray CRUD, v1 history, and log verification completed.\n'
