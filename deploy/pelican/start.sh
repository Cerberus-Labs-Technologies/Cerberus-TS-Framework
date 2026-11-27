#!/usr/bin/env bash
# Cerberus Labs: fixed Pelican entry point; credentials stay in the environment.
set -euo pipefail
umask 077
cerberus_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
fail() { printf 'Cerberus TS: %s\n' "$1" >&2; exit 2; }
[[ "${SERVER_PORT:-}" =~ ^[0-9]+$ ]] || fail 'SERVER_PORT must be the primary TCP allocation.'
(( 10#$SERVER_PORT >= 1 && 10#$SERVER_PORT <= 65535 )) || fail 'Invalid primary allocation port.'
selection="${CERBERUS_BUNDLED_PLUGINS-welcome,music}"
[[ "$selection" != none ]] || selection=""
selected=",${selection},"
[[ -z "$selection" || "$selection" =~ ^(welcome|music|server-setup)(,(welcome|music|server-setup))*$ ]] || fail 'Bundled plugins: use welcome,music,server-setup, or none.'
# The explicit value "none" disables all bundled plugins.
export CERBERUS_DATA_DIRECTORY="$PWD/data"
export CERBERUS_PLUGIN_DIRECTORY="$PWD/plugins"
export CERBERUS_KNOWN_HOSTS="${CERBERUS_KNOWN_HOSTS:-$PWD/config/known_hosts}"
export CERBERUS_WEB_ENABLED=true CERBERUS_WEB_HOST=0.0.0.0 CERBERUS_REST_HOST=0.0.0.0
export CERBERUS_WEB_PORT="$SERVER_PORT" CERBERUS_REST_PORT="$SERVER_PORT"
export XDG_CACHE_HOME="$PWD/.cache"
export JAVA_OPTS='-Xms128m -XX:MaxRAMPercentage=70.0 -Djava.awt.headless=true -Dfile.encoding=UTF-8'
mkdir -p config plugins data/music/files "$XDG_CACHE_HOME"
if [[ ! -e config/cerberus.properties ]]; then
    cp "$cerberus_root/app/config/cerberus.example.properties" config/cerberus.properties
fi
# Blank optional panel fields must not shadow values in the properties file.
for optional in CERBERUS_PASSWORD CERBERUS_API_KEY CERBERUS_ADMIN_UID CERBERUS_VOICE_PASSWORD CERBERUS_VIDEO_PUBLIC_ADDRESS; do
    if [[ -z "${!optional:-}" ]]; then unset "$optional"; fi
done
if [[ "${CERBERUS_TRANSPORT:-SSH}" == SSH ]] && { [[ ! -s "$CERBERUS_KNOWN_HOSTS" ]] || ! grep -Eq '^[^#[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+[^[:space:]]+' "$CERBERUS_KNOWN_HOSTS"; }; then
    fail 'Upload the verified SSH host key to config/known_hosts before starting.'
fi
# These three filenames are reserved. All other plugin JARs are operator-owned.
for plugin in welcome music server-setup; do
    destination="plugins/cerberus-bundled-${plugin}.jar"
    [[ ! -L "$destination" ]] || fail 'A reserved bundled plugin filename is a symbolic link.'
    if [[ "$selected" == *",$plugin,"* ]]; then
        cp "$cerberus_root/bundled/${plugin}-plugin.jar" "$destination"
    else
        rm -f -- "$destination"
    fi
done
"$cerberus_root/app/bin/cerberus-ts" --config config/cerberus.properties --check-config
exec "$cerberus_root/app/bin/cerberus-ts" --config config/cerberus.properties --console
