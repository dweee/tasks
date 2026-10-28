#!/usr/bin/env bash
# Reproduce a sanitized public export from the private fork.
# Fails closed: requires the private policy, a clean tree, git-filter-repo,
# the publication scanner and gitleaks. Never pushes or force-pushes.
set -euo pipefail

BASE="${EXPORT_BASE:-28f55dcf8e}"
SOURCE_REF="${1:-}"
EXPORT_BRANCH="${2:-public/sanitized-export-$(date -u +%Y%m%d)}"
POLICY_FILE="${PUBLICATION_DENYLIST_FILE:-}"

usage() {
  echo "usage: PUBLIC_DENYLIST_FILE=/path/to/denylist.json $0 <source-ref> [export-branch]" >&2
}

if [[ -z "$SOURCE_REF" ]]; then
  usage
  exit 2
fi

if [[ -z "${PUBLICATION_DENYLIST:-}" && -n "$POLICY_FILE" ]]; then
  PUBLICATION_DENYLIST=$(cat "$POLICY_FILE")
fi
export PUBLICATION_DENYLIST
export REQUIRE_PRIVATE_POLICY=true

python3 - <<'PY'
import json
import os
import sys

raw = os.environ.get("PUBLICATION_DENYLIST", "")
try:
    policy = json.loads(raw)
except json.JSONDecodeError:
    print("blocked: publication policy is not valid JSON", file=sys.stderr)
    sys.exit(1)
if (not isinstance(policy, list) or not policy
        or any(not isinstance(item, str) or not item.strip() for item in policy)):
    print("blocked: publication policy is empty or invalid", file=sys.stderr)
    sys.exit(1)
PY

FILTER_REPO="${GIT_FILTER_REPO:-$(command -v git-filter-repo || true)}"
if [[ -z "$FILTER_REPO" && -x "$HOME/.local/bin/git-filter-repo" ]]; then
  FILTER_REPO="$HOME/.local/bin/git-filter-repo"
fi
if [[ -z "$FILTER_REPO" ]]; then
  echo "blocked: git-filter-repo is required" >&2
  exit 1
fi
command -v nix >/dev/null || {
  echo "blocked: nix is required for the gitleaks scan" >&2
  exit 1
}
git rev-parse --verify "$BASE^{commit}" >/dev/null
git rev-parse --verify "$SOURCE_REF^{commit}" >/dev/null
git diff --quiet && git diff --cached --quiet || {
  echo "blocked: working tree is dirty" >&2
  exit 1
}
git merge-base --is-ancestor "$BASE" "$SOURCE_REF" || {
  echo "blocked: export base is not an ancestor of the source ref" >&2
  exit 1
}

OUTPUT_DIR="${OUTPUT_DIR:-$(mktemp -d)/tasks-public-export}"
if [[ -e "$OUTPUT_DIR" ]]; then
  echo "blocked: output directory already exists: $OUTPUT_DIR" >&2
  exit 1
fi
mkdir -p "$(dirname "$OUTPUT_DIR")"

cleanup() {
  status=$?
  if [[ $status -ne 0 ]]; then
    rm -rf "$OUTPUT_DIR"
    echo "export failed; temporary output removed" >&2
  fi
}
trap cleanup EXIT

repo_root=$(git rev-parse --show-toplevel)
git clone --quiet --no-local "$repo_root" "$OUTPUT_DIR"
cd "$OUTPUT_DIR"
git checkout --quiet -B "$EXPORT_BRANCH" "$SOURCE_REF"

"$FILTER_REPO" --force \
  --refs "$BASE..$EXPORT_BRANCH" \
  --invert-paths \
  --path .forgejo/ \
  --path ci/ \
  --path .github/scripts/forgejo-askpass.sh

python3 -m unittest discover -s .github/scripts -p 'test_publication.py'
python3 .github/scripts/check_publication.py --base "$BASE" --head "$EXPORT_BRANCH"
nix shell --inputs-from . nixpkgs#gitleaks -c \
  gitleaks git . --log-opts="$BASE..$EXPORT_BRANCH" --redact --no-banner

{
  echo "# Refs in this clone whose history is not part of the sanitized export."
  echo "# Replace or delete matching branches and tags on the public host after review."
  git for-each-ref --format='%(refname)' refs/heads refs/tags |
    while IFS= read -r ref; do
      if [[ "$ref" == "refs/heads/$EXPORT_BRANCH" ]]; then
        continue
      fi
      tip=$(git rev-parse "$ref")
      if ! git merge-base --is-ancestor "$tip" "$EXPORT_BRANCH"; then
        echo "$ref"
      fi
    done
} > unsafe-public-refs.txt

echo "Sanitized export ready: $OUTPUT_DIR"
echo "Branch: $EXPORT_BRANCH"
echo "Review unsafe-public-refs.txt before changing any public refs."
echo "No changes were pushed."
