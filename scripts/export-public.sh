#!/usr/bin/env bash
# Reproduce a sanitized public export from the private fork.
#
# Run this helper from the reviewed public tooling checkout. It reads the
# private commit graph from a local private clone and writes a new, temporary
# export clone. The public-only templates in this checkout are applied to the
# export before the privacy scanner and gitleaks run. Nothing is pushed.
set -euo pipefail

BASE="${EXPORT_BASE:-28f55dcf8e}"
SOURCE_DIR="${SOURCE_DIR:-}"
SOURCE_REF=""
EXPORT_BRANCH=""
POLICY_FILE="${PUBLICATION_DENYLIST_FILE:-}"

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
EXPORTER_ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)

usage() {
  cat >&2 <<'EOF'
usage: PUBLICATION_DENYLIST_FILE=/path/to/denylist.json \
  scripts/export-public.sh [--source-dir /path/to/private-clone] <source-ref> [export-branch]

Run from the reviewed public tooling checkout. If --source-dir is omitted, the
current working directory's Git repository is used as the private source.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-dir)
      [[ $# -ge 2 && -n "$2" ]] || { usage; exit 2; }
      SOURCE_DIR="$2"
      shift 2
      ;;
    --source-dir=*)
      SOURCE_DIR="${1#*=}"
      [[ -n "$SOURCE_DIR" ]] || { usage; exit 2; }
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "blocked: unknown option: $1" >&2
      usage
      exit 2
      ;;
    *)
      break
      ;;
  esac
done

SOURCE_REF="${1:-}"
EXPORT_BRANCH="${2:-public/sanitized-export-$(date -u +%Y%m%d)}"

if [[ -z "$SOURCE_REF" ]]; then
  usage
  exit 2
fi
if [[ -z "$SOURCE_DIR" ]]; then
  SOURCE_DIR=$(git rev-parse --show-toplevel)
fi
[[ -d "$SOURCE_DIR" ]] || {
  echo "blocked: source directory does not exist: $SOURCE_DIR" >&2
  exit 1
}
SOURCE_DIR=$(cd -- "$SOURCE_DIR" && pwd -P)

# Paths that must never appear in exported fork history. The reviewed public
# templates replace the private fork versions of the same paths.
PRIVATE_PATHS=(
  ".forgejo/"
  "ci/"
  ".github/scripts/forgejo-askpass.sh"
)
TEMPLATE_PATHS=(
  ".github/workflows/desktop.yml"
  ".gitleaks.toml"
  "docs/public-export.md"
  "scripts/export-public.sh"
  "nix/README.md"
)

for path in "${TEMPLATE_PATHS[@]}" README.md; do
  [[ -f "$EXPORTER_ROOT/$path" ]] || {
    echo "blocked: reviewed template is missing: $EXPORTER_ROOT/$path" >&2
    exit 1
  }
  git -C "$EXPORTER_ROOT" ls-files --error-unmatch -- "$path" >/dev/null 2>&1 || {
    echo "blocked: reviewed template is not tracked: $path" >&2
    exit 1
  }
done
if ! git -C "$EXPORTER_ROOT" diff --quiet -- "${TEMPLATE_PATHS[@]}" README.md \
  || ! git -C "$EXPORTER_ROOT" diff --cached --quiet -- "${TEMPLATE_PATHS[@]}" README.md; then
  echo "blocked: reviewed templates have uncommitted changes in $EXPORTER_ROOT" >&2
  exit 1
fi

if [[ -z "${PUBLICATION_DENYLIST:-}" && -n "$POLICY_FILE" ]]; then
  PUBLICATION_DENYLIST=$(cat "$POLICY_FILE")
fi
export PUBLICATION_DENYLIST
export REQUIRE_PRIVATE_POLICY=true
# Keep helper tests from leaving untracked __pycache__ in the exported worktree.
export PYTHONDONTWRITEBYTECODE=1

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

SOURCE_COMMIT=$(git -C "$SOURCE_DIR" rev-parse --verify "$SOURCE_REF^{commit}") || {
  echo "blocked: source ref does not resolve to a commit: $SOURCE_REF" >&2
  exit 1
}
BASE_COMMIT=$(git -C "$SOURCE_DIR" rev-parse --verify "$BASE^{commit}") || {
  echo "blocked: export base does not resolve to a commit: $BASE" >&2
  exit 1
}
git -C "$SOURCE_DIR" cat-file -e "$SOURCE_COMMIT:README.md" 2>/dev/null || {
  echo "blocked: source commit has no README.md" >&2
  exit 1
}
if [[ -n "$(git -C "$SOURCE_DIR" status --porcelain=v1 -uall)" ]]; then
  echo "blocked: source working tree is dirty (including untracked files)" >&2
  exit 1
fi
git -C "$SOURCE_DIR" merge-base --is-ancestor "$BASE_COMMIT" "$SOURCE_COMMIT" || {
  echo "blocked: export base is not an ancestor of the source commit" >&2
  exit 1
}

EXPORT_GIT_NAME="${EXPORT_GIT_NAME:-$(git -C "$SOURCE_DIR" config --get user.name || true)}"
EXPORT_GIT_EMAIL="${EXPORT_GIT_EMAIL:-$(git -C "$SOURCE_DIR" config --get user.email || true)}"
if [[ -z "$EXPORT_GIT_NAME" || -z "$EXPORT_GIT_EMAIL" ]]; then
  echo "blocked: configure a Git user.name and user.email for the export commit" >&2
  exit 1
fi

SOURCE_README=$(mktemp)
OUTPUT_DIR="${OUTPUT_DIR:-}"
AUTO_OUTPUT=false
cleanup() {
  status=$?
  rm -f -- "$SOURCE_README"
  if [[ $status -ne 0 && "$AUTO_OUTPUT" == true && -n "$OUTPUT_DIR" ]]; then
    rm -rf -- "$OUTPUT_DIR"
    echo "export failed; temporary output removed" >&2
  elif [[ $status -ne 0 && -n "$OUTPUT_DIR" ]]; then
    echo "export failed; retained requested output directory: $OUTPUT_DIR" >&2
  fi
}
trap cleanup EXIT

if [[ -n "${OUTPUT_DIR:-}" ]]; then
  if [[ -e "$OUTPUT_DIR" ]]; then
    echo "blocked: output directory already exists: $OUTPUT_DIR" >&2
    exit 1
  fi
  mkdir -p "$(dirname "$OUTPUT_DIR")"
else
  OUTPUT_DIR="$(mktemp -d)/tasks-public-export"
  AUTO_OUTPUT=true
fi
git -C "$SOURCE_DIR" show "$SOURCE_COMMIT:README.md" > "$SOURCE_README"

# Capture the exact source commit before cloning. A local clone only exposes a
# remote-tracking branch for non-default refs, so never resolve SOURCE_REF again
# inside the clone.
git clone --quiet --no-local "$SOURCE_DIR" "$OUTPUT_DIR"
if ! git -C "$OUTPUT_DIR" cat-file -e "$SOURCE_COMMIT^{commit}" 2>/dev/null; then
  git -C "$OUTPUT_DIR" fetch --quiet --no-tags "$SOURCE_DIR" "$SOURCE_COMMIT"
fi
git -C "$OUTPUT_DIR" checkout --quiet -B "$EXPORT_BRANCH" "$SOURCE_COMMIT"

cd "$OUTPUT_DIR"
filter_args=(
  --force
  --refs "$BASE_COMMIT..$EXPORT_BRANCH"
  --invert-paths
)
for path in "${PRIVATE_PATHS[@]}" "${TEMPLATE_PATHS[@]}" README.md; do
  filter_args+=(--path "$path")
done
"$FILTER_REPO" "${filter_args[@]}"

# Apply the reviewed public-only templates from this tooling checkout. README
# keeps the upstream tail from the private source and replaces only the fork
# introduction with the public introduction.
for path in "${TEMPLATE_PATHS[@]}"; do
  mkdir -p "$OUTPUT_DIR/$(dirname "$path")"
  cp -- "$EXPORTER_ROOT/$path" "$OUTPUT_DIR/$path"
done
python3 - "$EXPORTER_ROOT/README.md" "$SOURCE_README" "$OUTPUT_DIR/README.md" <<'PY'
from pathlib import Path
import sys

public_path, source_path, output_path = map(Path, sys.argv[1:])

def split_at_separator(text):
    lines = text.splitlines(keepends=True)
    for index, line in enumerate(lines):
        if line.strip() == "---":
            return "".join(lines[:index + 1]), "".join(lines[index + 1:])
    raise SystemExit("blocked: README template is missing its '---' separator")

public_prefix, _ = split_at_separator(public_path.read_text())
_, source_tail = split_at_separator(source_path.read_text())
output_path.write_text(public_prefix + source_tail)
PY

git -C "$OUTPUT_DIR" add -- "${TEMPLATE_PATHS[@]}" README.md
if ! git -C "$OUTPUT_DIR" diff --cached --quiet; then
  git -C "$OUTPUT_DIR" \
    -c user.name="$EXPORT_GIT_NAME" \
    -c user.email="$EXPORT_GIT_EMAIL" \
    commit --quiet -m "Public export: apply reviewed public templates"
fi

cd "$OUTPUT_DIR"
python3 -m unittest discover -s .github/scripts -p 'test_publication.py'
python3 .github/scripts/check_publication.py --base "$BASE_COMMIT" --head "$EXPORT_BRANCH"
nix shell --inputs-from "$EXPORTER_ROOT" nixpkgs#gitleaks -c \
  gitleaks git . --log-opts="$BASE_COMMIT..$EXPORT_BRANCH" --redact --no-banner

REPORT="$OUTPUT_DIR/.git/unsafe-public-refs.txt"
{
  echo "# Local ref hints from this temporary clone."
  echo "# These are local refs only, not an inventory of refs on the public host."
  echo "# Actual GitHub ref inventory requires a separate parent/API query."
  echo "# Review before changing any public refs; no remote mutation was performed."
  echo "# Sanitized branch: $EXPORT_BRANCH @ $(git rev-parse --short "$EXPORT_BRANCH")"
  git for-each-ref --format='%(refname) %(objectname)' refs/heads refs/tags refs/remotes |
    while IFS=' ' read -r ref tip; do
      case "$ref" in
        "refs/heads/$EXPORT_BRANCH"|"refs/remotes/origin/$EXPORT_BRANCH"|refs/remotes/origin/HEAD)
          continue
          ;;
      esac
      if ! git merge-base --is-ancestor "$tip" "$EXPORT_BRANCH" 2>/dev/null; then
        echo "$ref $tip"
      fi
    done
} > "$REPORT"

echo "Sanitized export ready: $OUTPUT_DIR"
echo "Branch: $EXPORT_BRANCH"
echo "Local ref hints: $REPORT"
echo "Actual public ref inventory requires a separate GitHub query by the parent."
echo "No changes were pushed."
