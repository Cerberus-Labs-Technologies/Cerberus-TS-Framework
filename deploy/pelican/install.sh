#!/usr/bin/env bash
# Wings installation container, not the runtime image. Reinstallation preserves data.
set -euo pipefail
umask 077
cd /mnt/server
mkdir -p config plugins data/music/files
if [[ ! -e config/known_hosts ]]; then
    printf '# Upload a verified TeamSpeak Query SSH host key here.\n' > config/known_hosts
fi
printf 'Cerberus TS directories prepared. The runtime image contains the framework and music tools.\n'
