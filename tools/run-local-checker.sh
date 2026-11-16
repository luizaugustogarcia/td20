#!/usr/bin/env bash
source "$HOME/.bashrc"
source "$(dirname -- "${BASH_SOURCE[0]}")/local-profile.sh"
td20_local_main checker "$@"
