#!/usr/bin/env bash
# Install a supplied plugin JAR into official Nacos and run the full HTTP suite.
# Intended for CI: bash scripts/verify-artifact.sh /absolute/path/plugin-all.jar
set -euo pipefail

if [ "$#" -ne 1 ]; then
    printf 'Usage: bash scripts/verify-artifact.sh /absolute/path/plugin-all.jar\n' >&2
    exit 2
fi

PLUGIN_JAR="$1"
NACOS_VERSION="${NACOS_VERSION:-2.5.5}"
DB_URL="${NACOS_DB_URL:-jdbc:postgresql://127.0.0.1:5432/nacos}"
DB_USER="${NACOS_DB_USER:-nacos}"
DB_PASSWORD="${NACOS_DB_PASSWORD:-nacos}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
WORK_ROOT="${VERIFY_WORK_ROOT:-${RUNNER_TEMP:-${TMPDIR:-/tmp}/nacos-artifact-verification}}"

case "$NACOS_VERSION" in
    ''|*[!0-9.]*) printf 'Invalid NACOS_VERSION\n' >&2; exit 2 ;;
esac
case "$NACOS_VERSION" in
    *..*|.*|*.) printf 'Invalid NACOS_VERSION\n' >&2; exit 2 ;;
esac
for tool in curl gh unzip java; do
    command -v "$tool" >/dev/null 2>&1 || {
        printf 'Required command not found: %s\n' "$tool" >&2
        exit 2
    }
done
[ -f "$PLUGIN_JAR" ] && [ -s "$PLUGIN_JAR" ] || {
    printf 'Plugin JAR does not exist or is empty: %s\n' "$PLUGIN_JAR" >&2
    exit 2
}
case "$DB_URL" in
    jdbc:postgresql://*) ;;
    *) printf 'NACOS_DB_URL must be a jdbc:postgresql:// URL\n' >&2; exit 2 ;;
esac
case "$DB_URL$DB_USER$DB_PASSWORD" in
    *[$'\r\n']*) printf 'Database settings must be single-line values\n' >&2; exit 2 ;;
esac

mkdir -p "$WORK_ROOT/nacos-download"
# gh constructs the official GitHub Release URL for alibaba/nacos. It avoids
# downloading and executing any third-party installer or unknown script.
gh release download "$NACOS_VERSION" --repo alibaba/nacos \
    --pattern "nacos-server-$NACOS_VERSION.zip" \
    --dir "$WORK_ROOT/nacos-download"
NACOS_ZIP="$WORK_ROOT/nacos-download/nacos-server-$NACOS_VERSION.zip"
[ -s "$NACOS_ZIP" ] || {
    printf 'Official Nacos ZIP was not downloaded\n' >&2
    exit 1
}
unzip -q "$NACOS_ZIP" -d "$WORK_ROOT/nacos-server"
NACOS_HOME="$WORK_ROOT/nacos-server/nacos"
[ -f "$NACOS_HOME/bin/startup.sh" ] || {
    printf 'Unexpected Nacos archive layout: startup.sh not found\n' >&2
    exit 1
}
install -D -m 0644 "$PLUGIN_JAR" "$NACOS_HOME/plugins/$(basename -- "$PLUGIN_JAR")"

# Never log DB_PASSWORD or print application.properties. Failure-log upload in
# the workflow collects only selected .log/.out files, not this configuration.
printf '%s\n' \
    '' \
    '# CI PostgreSQL datasource (configuration is not a failure-log artifact).' \
    'spring.sql.init.platform=postgresql' \
    'db.num=1' \
    'db.pool.config.driverClassName=org.postgresql.Driver' \
    "db.url.0=$DB_URL" \
    "db.user.0=$DB_USER" \
    "db.password.0=$DB_PASSWORD" \
    >> "$NACOS_HOME/conf/application.properties"

printf '==> Starting official Nacos %s in standalone mode\n' "$NACOS_VERSION"
(
    cd "$NACOS_HOME"
    bash bin/startup.sh -m standalone
)
# The workflow separates startup from verification so its failure logs and
# explicit readiness step remain visible; standalone users get both here.
if [ "${VERIFY_ARTIFACT_START_ONLY:-false}" = "true" ]; then
    printf '[PASS] Nacos startup command completed; plugin is installed under %s/plugins\n' "$NACOS_HOME"
    exit 0
fi
NACOS_HOME="$NACOS_HOME" bash "$SCRIPT_DIR/verify-nacos.sh"
