#!/usr/bin/env bash
set -euo pipefail

# --- Resolve repo/branch names ---
REPO="${REPO:-${GITHUB_REPOSITORY:-}}"
: "${REPO:?REPO is empty; export it or rely on GITHUB_REPOSITORY}"
: "${BASE_BRANCH:=main}"
PR_BRANCH="${PR_BRANCH:-}"
if [[ -z "${PR_BRANCH}" ]]; then
  PR_BRANCH="${HEAD_BRANCH:-}"
fi
if [[ -z "${PR_BRANCH}" ]]; then
  PR_BRANCH="${GITHUB_REF_NAME:-}"
fi

if [[ -z "${PR_BRANCH}" ]]; then
  echo "Error: PR_BRANCH is empty"
  exit 1
fi

if [[ -n "${GH_PAT:-}" ]]; then
  echo "Using GH_PAT for gh auth (targeting ${REPO})"
  echo "${GH_PAT}" | gh auth login --with-token
fi

git fetch --all --prune
# Ensure local branch exists before PR ops
if git show-ref --verify --quiet "refs/heads/${PR_BRANCH}"; then
  git checkout "${PR_BRANCH}"
else
  git checkout -b "${PR_BRANCH}" "origin/${PR_BRANCH}" || git checkout -b "${PR_BRANCH}"
fi

existing_number="$(gh pr list --repo "${REPO}" --head "${PR_BRANCH}" --state open --json number -q '.[0].number' || true)"

if [[ -n "${existing_number}" ]]; then
  echo "PR #${existing_number} exists; updating metadata"
  gh pr edit "${existing_number}" \
    --repo "${REPO}" \
    --base "${BASE_BRANCH}" \
    --title "Upstream sweep" \
    --body "Automated sweep" \
    --add-label "upstream-sweep"
else
  echo "No PR found; creating a new one"
  gh pr create \
    --repo "${REPO}" \
    --base "${BASE_BRANCH}" \
    --head "${PR_BRANCH}" \
    --title "Upstream sweep" \
    --body "Automated sweep" \
    --draft
fi

gh pr view "${PR_BRANCH}" --repo "${REPO}" --json url -q .url
