#!/usr/bin/env bash
set -euo pipefail

# remoto -> slug GitHub (para URLs https)
declare -A REMOTES=(
  [upstream]=mihonapp/mihon
  [j2k]=Jays2Kings/tachiyomiJ2K
  [sy]=jobobby04/TachiyomiSY
  [yokai]=null2264/yokai
  [neko]=nekomangaorg/Neko
  [komikku]=komikku-app/komikku
)

default_branch() {
  # Detecta la rama HEAD del remoto (rama por defecto)
  git remote show "$1" | sed -n 's/.*HEAD branch: //p'
}

git fetch --all --prune

for name in "${!REMOTES[@]}"; do
  # Asegura remoto (idempotente)
  if ! git remote get-url "$name" >/dev/null 2>&1; then
    git remote add "$name" "https://github.com/${REMOTES[$name]}.git"
  fi

  git fetch "$name" --prune
  head_branch="$(default_branch "$name")"
  [[ -n "$head_branch" ]] || head_branch=main   # fallback

  vendor="vendor/$name"
  # Crea/actualiza vendor/* como espejo exacto del remoto HEAD
  git checkout -B "$vendor" "$name/$head_branch"
  git push origin "$vendor" --force
 done

