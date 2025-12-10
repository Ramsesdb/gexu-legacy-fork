#!/usr/bin/env bash
set -euo pipefail

# Simple upstream sync script - only syncs with mihonapp/mihon
# Used by the upstream-sweep workflow

UPSTREAM_URL="https://github.com/mihonapp/mihon.git"

log() { echo "[sync] $*"; }

main() {
  git config user.name "${GIT_USER:-github-actions[bot]}"
  git config user.email "${GIT_EMAIL:-41898282+github-actions[bot]@users.noreply.github.com}"

  # Ensure upstream remote exists
  if git remote get-url upstream >/dev/null 2>&1; then
    git remote set-url upstream "$UPSTREAM_URL"
  else
    log "Adding upstream remote -> $UPSTREAM_URL"
    git remote add upstream "$UPSTREAM_URL"
  fi
  
  # Block pushes to upstream (safety)
  git remote set-url --push upstream no_push

  # Fetch upstream
  log "Fetching upstream..."
  git fetch upstream --no-tags

  log "Upstream sync complete"
  git remote -v
}

main "$@"
