#!/usr/bin/env bash
# Boundary test for the public export helper. It builds a temporary private
# fixture with private CI delivery content, runs the real exporter, and checks
# that only the reviewed public templates and upstream README content survive.
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
EXPORTER_ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
TMP=$(mktemp -d)
if [[ -n "${KEEP_TEST_TMP:-}" ]]; then
  echo "keeping boundary test directory: $TMP" >&2
  trap - EXIT
else
  trap 'rm -rf -- "$TMP"' EXIT
fi

fail() {
  echo "boundary test failed: $*" >&2
  exit 1
}

SRC="$TMP/source"
mkdir -p "$SRC/.github/scripts" "$SRC/.github/workflows" "$SRC/.forgejo/workflows" \
  "$SRC/ci" "$SRC/docs" "$SRC/nix" "$SRC/scripts"
git init -q -b main "$SRC"
git -C "$SRC" config user.name "Export boundary test"
git -C "$SRC" config user.email "export-boundary@example.invalid"

cp -- "$EXPORTER_ROOT/.github/scripts/check_publication.py" "$SRC/.github/scripts/"
cp -- "$EXPORTER_ROOT/.github/scripts/test_publication.py" "$SRC/.github/scripts/"
cat > "$SRC/README.md" <<'EOF'
# Base fork README

Base introduction.

---

Upstream content marker
EOF
git -C "$SRC" add .
git -C "$SRC" commit --quiet -m "Base commit"
BASE=$(git -C "$SRC" rev-parse HEAD)

cat > "$SRC/README.md" <<'EOF'
# Private fork README

private marker: canonical fork at git.monke.fail/d/tasks

---
Upstream content marker
EOF
cat > "$SRC/.github/workflows/desktop.yml" <<'EOF'
name: private desktop
on: push
jobs:
  private:
    runs-on: ubuntu-24.04
    steps:
      - run: |
          private marker
          test -n "$ATTIC_TOKEN"
          test -n "$FORGEJO_PUSH_TOKEN"
          git remote add forgejo https://git.monke.fail/d/tasks.git
          git fetch forgejo main
          git push forgejo HEAD:refs/heads/main
EOF
cat > "$SRC/.gitleaks.toml" <<'EOF'
title = "private marker gitleaks policy"
EOF
cat > "$SRC/docs/public-export.md" <<'EOF'
private marker export notes
EOF
cat > "$SRC/scripts/export-public.sh" <<'EOF'
#!/usr/bin/env bash
# private marker script
EOF
cat > "$SRC/nix/README.md" <<'EOF'
private marker Attic cache notes
EOF
cat > "$SRC/.forgejo/workflows/release.yml" <<'EOF'
# private marker forgejo workflow
EOF
cat > "$SRC/ci/channels.json" <<'EOF'
{"private marker": true}
EOF
git -C "$SRC" add .
git -C "$SRC" commit --quiet -m "Private desktop delivery"

OUT="$TMP/export"
PUBLICATION_DENYLIST='["private marker","git.monke.fail","ATTIC_TOKEN","FORGEJO_PUSH_TOKEN"]' \
EXPORT_BASE="$BASE" \
SOURCE_DIR="$SRC" \
OUTPUT_DIR="$OUT" \
  "$EXPORTER_ROOT/scripts/export-public.sh" main public/boundary-test

BRANCH=public/boundary-test
git -C "$OUT" show "$BRANCH:.github/workflows/desktop.yml" > "$TMP/exported-workflow.yml"
cmp -- "$EXPORTER_ROOT/.github/workflows/desktop.yml" "$TMP/exported-workflow.yml" \
  || fail "exported workflow differs from the reviewed template"

if grep -Eq 'ATTIC_TOKEN|FORGEJO_PUSH_TOKEN|git\.monke\.fail|remote add forgejo|fetch forgejo|push forgejo' \
  "$TMP/exported-workflow.yml"; then
  fail "exported workflow still contains private delivery content"
fi
git -C "$OUT" ls-tree -r --name-only "$BRANCH" > "$TMP/exported-files.txt"
if grep -Eq '^(\.forgejo/|ci/)' "$TMP/exported-files.txt"; then
  fail "private CI paths remain in the export"
fi
git -C "$OUT" log -p "$BASE..$BRANCH" > "$TMP/exported-history.txt"
if grep -q 'private marker' "$TMP/exported-history.txt"; then
  fail "private fixture content remains in exported history"
fi

git -C "$OUT" show "$BRANCH:README.md" > "$TMP/exported-readme.md"
grep -q '^# Tasks desktop (public export)$' "$TMP/exported-readme.md" \
  || fail "public README introduction was not applied"
grep -q '^Upstream content marker$' "$TMP/exported-readme.md" \
  || fail "upstream README content was not preserved"
if grep -q 'private marker' "$TMP/exported-readme.md"; then
  fail "private README introduction remains in the export"
fi

[[ -z "$(git -C "$OUT" status --porcelain=v1 -uall)" ]] \
  || fail "export worktree is dirty after the export"
[[ ! -e "$OUT/unsafe-public-refs.txt" ]] \
  || fail "ref hints were written into the exported worktree"
grep -q 'Local ref hints' "$OUT/.git/unsafe-public-refs.txt" \
  || fail "local ref hints report is missing or mislabeled"

echo "export boundary test passed"
