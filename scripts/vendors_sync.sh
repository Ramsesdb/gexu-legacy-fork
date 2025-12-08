#!/usr/bin/env bash
set -euo pipefail

# vendors_sync.sh
# Mirror a set of external remotes into origin/vendor/* (fetch-only from externals)
# - Ensures remotes exist on CI runners
# - Blocks pushes to external remotes (defense-in-depth)
# - Tolerates individual remote failures (logs warning and continues)

# --- vendor remotes (name -> URL) ---
declare -A REMOTES=(
  [upstream]="https://github.com/mihonapp/mihon.git"
  [j2k]="https://github.com/Jays2Kings/tachiyomiJ2K.git"
  [sy]="https://github.com/jobobby04/TachiyomiSY.git"
  # FIX: Yokai moved — use null2264/yokai
  [yokai]="https://github.com/null2264/yokai.git"
  [neko]="https://github.com/nekomangaorg/Neko.git"
  [komikku]="https://github.com/komikku-app/komikku.git"
)

BASE_BRANCH="${BASE_BRANCH:-main}"
GIT_USER="${GIT_USER:-github-actions[bot]}"
GIT_EMAIL="${GIT_EMAIL:-41898282+github-actions[bot]@users.noreply.github.com}"

# Tracks which remotes fetched successfully
declare -A FETCH_OK=()

log() { echo "[vendors_sync] $*"; }
warn() { echo "[vendors_sync][WARNING] $*"; }
info() { echo "[vendors_sync][INFO] $*"; }

main() {
  git config user.name  "$GIT_USER"
  git config user.email "$GIT_EMAIL"

  ensure_remotes

  # Fetch each remote individually so a failing remote doesn't abort the whole run
  for r in "${!REMOTES[@]}"; do
    info "Fetching remote: $r -> ${REMOTES[$r]}"
    if git fetch --no-tags --prune "$r"; then
      FETCH_OK[$r]=1
    else
      FETCH_OK[$r]=0
      warn "fetch failed for remote '$r' (${REMOTES[$r]}), will skip mirroring for this remote"
    fi
  done

  # Mirror branches only for remotes that fetched successfully
  for r in "${!REMOTES[@]}"; do
    if [[ "${FETCH_OK[$r]}" == "1" ]]; then
      mirror_remote "$r"
    else
      info "Skipping mirror for $r because fetch failed"
    fi
  done
}

ensure_remotes() {
  for remote in "${!REMOTES[@]}"; do
    if ! git remote get-url "$remote" >/dev/null 2>&1; then
      log "Adding remote '$remote' -> ${REMOTES[$remote]}"
      git remote add "$remote" "${REMOTES[$remote]}"
    else
      # update fetch URL in case it changed upstream
      git remote set-url "$remote" "${REMOTES[$remote]}" || true
    fi
    # Block pushes to external remotes (defense-in-depth)
    git remote set-url --push "$remote" no_push || true
  done
}

# Mirror the default branch of a remote into origin/vendor/<remote>
mirror_remote() {
  local remote="$1"
  info "Mirroring remote '$remote'"

  # Detect remote default branch (HEAD symref), fallback to main
  local head_ref
  head_ref=$(git ls-remote --symref "$remote" HEAD 2>/dev/null | awk '/^ref:/ {print $2}' | sed 's#refs/heads/##') || true
  head_ref=${head_ref:-main}

  # If the remote doesn't have the branch we want, skip
  local remote_ref="refs/remotes/${remote}/${head_ref}"
  if ! git show-ref --verify --quiet "$remote_ref"; then
    warn "remote '$remote' does not have branch '$head_ref' (skipping)"
    return
  fi

  local vendor_branch="vendor/${remote}"
  git push -f origin "$remote_ref:refs/heads/$vendor_branch"
  info "Pushed $remote_ref -> origin/$vendor_branch"
}

main "$@"
