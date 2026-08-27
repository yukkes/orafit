#!/usr/bin/env bash
set -Eeuo pipefail

cd "$(dirname "$0")/../.."
socket=/var/run/docker.sock
[[ -S "$socket" ]] || { echo "Docker socket not found: $socket" >&2; exit 1; }
export PATH="$HOME/.local/bin:$PATH"
if ! command -v act >/dev/null; then
    mkdir -p "$HOME/.local/bin"
    curl -fsSL https://github.com/nektos/act/releases/latest/download/act_Linux_x86_64.tar.gz \
        | tar -xz -C "$HOME/.local/bin" act
fi

exec act workflow_dispatch \
    -W .github/workflows/ci.yml \
    --container-options="-u $(id -u):$(id -g) --group-add $(stat -c '%g' "$socket")" \
    --input compatibility_status=supported
