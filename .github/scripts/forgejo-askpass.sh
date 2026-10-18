#!/usr/bin/env bash
set -euo pipefail
case "$1" in
  *Username*) printf '%s\n' tasks-ci ;;
  *Password*) printf '%s\n' "$FORGEJO_PUSH_TOKEN" ;;
  *) exit 1 ;;
esac
