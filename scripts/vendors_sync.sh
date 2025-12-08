#!/usr/bin/env bash
set -euo pipefail

# Remotos externos a espejar en vendor/* (solo fetch)
REMOTES=(
  upstream
  j2k
  sy
  yokai
  neko
  komikku
)

BASE_BRANCH="main"
GIT_USER="gexu-bot"
GIT_EMAIL="gexu-bot@users.noreply.github.com"

main() {
  git config user.name  "$GIT_USER"
  git config user.email "$GIT_EMAIL"

  git fetch --all --prune

  for remote in "${REMOTES[@]}"; do
    sync_remote "$remote"
  done

  git checkout "$BASE_BRANCH" || true
}

sync_remote() {
  local remote="$1"
  local head_ref

  head_ref=$(git ls-remote --symref "$remote" HEAD | awk '/^ref:/ {print $2}' | sed 's#refs/heads/##')
  head_ref=${head_ref:-main}

  local vendor_branch="vendor/${remote}"

  if git rev-parse --verify "$vendor_branch" >/dev/null 2>&1; then
    git checkout "$vendor_branch"
    git reset --hard "$remote/$head_ref"
  else
    git checkout -B "$vendor_branch" "$remote/$head_ref"
  fi

  git push origin "$vendor_branch" --force
}

main "$@"

