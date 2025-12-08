#!/usr/bin/env bash
set -euo pipefail

# External remotes to mirror (name -> URL)
declare -A REMOTES=(
  [upstream]="https://github.com/mihonapp/mihon.git"
  [j2k]="https://github.com/Jays2Kings/tachiyomiJ2K.git"
  [sy]="https://github.com/jobobby04/TachiyomiSY.git"
  [yokai]="https://github.com/null2264/yokai.git"
  [neko]="https://github.com/nekomangaorg/Neko.git"
  [komikku]="https://github.com/komikku-app/komikku.git"
)

BASE_BRANCH="${BASE_BRANCH:-main}"
GIT_USER="${GIT_USER:-github-actions[bot]}"
GIT_EMAIL="${GIT_EMAIL:-41898282+github-actions[bot]@users.noreply.github.com}"

main() {
  git config user.name  "$GIT_USER"
  git config user.email "$GIT_EMAIL"

  ensure_remotes

  git fetch --all --prune

  for remote in "${!REMOTES[@]}"; do
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

ensure_remotes() {
  for remote in "${!REMOTES[@]}"; do
    if ! git remote get-url "$remote" >/dev/null 2>&1; then
      git remote add "$remote" "${REMOTES[$remote]}"
    fi
    git remote set-url --push "$remote" no_push || true
  done
}

main "$@"
