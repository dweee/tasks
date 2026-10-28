# Public export procedure

This repository is generated from a private fork. The public branch must never
contain private infrastructure, credentials, cache endpoints or deployment
configuration. The export is reproducible and fails closed: if the private
policy, the guard checks or the secret scan are unavailable, the export stops.

## What is exported

- Upstream history up to the recorded base commit `28f55dcf8e` is preserved
  unchanged, including authors and ancestry.
- Fork commits are rewritten only in the range `28f55dcf8e..<source-commit>`.
- Private CI paths and private fork versions of the public-only files are
  removed from every fork commit:
  - `.forgejo/`
  - `ci/`
  - `.github/scripts/forgejo-askpass.sh`
  - `.github/workflows/desktop.yml`
  - `.gitleaks.toml`
  - `docs/public-export.md`
  - `scripts/export-public.sh`
  - `nix/README.md`
  - `README.md`
- Reviewed public-only versions of the last six paths are copied from the
  public tooling checkout and committed on top of the rewritten branch. The
  README keeps the upstream content after its first `---` separator and replaces
  only the fork introduction with the public introduction.
- Remaining added lines and commit metadata must pass
  `.github/scripts/check_publication.py` with the private policy enabled.
- The same range must pass gitleaks.

The template commit uses the Git `user.name` and `user.email` configured for the
private source clone. Nothing is pushed.

## Policy

`PUBLICATION_DENYLIST` is a JSON array of private identifiers. It is supplied
through the repository secret in CI and through the local environment for an
export. It must never be committed, printed or copied into the repository.

The export script also accepts `PUBLICATION_DENYLIST_FILE`, a path to a
read-only policy file outside the repository. The file is read into the
environment and is not copied into the export.

## Reproduce an export

Run the helper from a clean checkout of the reviewed public tooling, not from
the private source repository. Pass the private clone with `--source-dir` (or
`SOURCE_DIR`); without it the current working directory's repository is used as
the private source for backward compatibility.

```sh
PUBLICATION_DENYLIST_FILE=/path/to/denylist.json \
  scripts/export-public.sh --source-dir /path/to/private-clone \
  <source-ref> [export-branch]
```

The script:

1. refuses to run unless the source commit and base resolve to exact commits,
   the source worktree is clean including untracked files, the policy is
   present and valid, `git-filter-repo` is available, and the reviewed templates
   are tracked and unmodified in the public tooling checkout;
2. clones the local private repository into a new temporary export directory;
3. checks out the exact resolved source commit rather than re-resolving the
   source ref inside the clone;
4. rewrites only `28f55dcf8e..<export-branch>` and removes the private paths and
   private versions of the public templates from that history;
5. copies the reviewed public templates from the tooling checkout and commits
   them with the configured source Git identity;
6. runs the publication scanner with `REQUIRE_PRIVATE_POLICY=true`;
7. runs gitleaks over the same range; and
8. prints the export directory and branch without pushing anything.

An output directory supplied through `OUTPUT_DIR` must not already exist. The
script retains a requested output directory on failure so it can be inspected;
it only removes the temporary directory it created itself.

Review the result and push deliberately. Never force-push history and never
push `main` to a private remote. The script writes local ref hints to
`.git/unsafe-public-refs.txt` inside the temporary clone. Those hints describe
only the local clone, not the public host: an actual GitHub ref inventory
requires a separate API query by the parent. Old branches and tags that still
point at pre-export history must be replaced or deleted on the public host after
that inventory and review.

## Boundary test

`scripts/test-export-public.sh` builds a temporary private fixture, runs the real
export helper, and checks that the exported workflow equals the reviewed public
template, contains no private delivery credentials or private fetch/push paths,
and that the README keeps its upstream tail. It requires `git-filter-repo`, Nix
and gitleaks, and uses only local repositories.

## CI

`.github/workflows/desktop.yml` runs the publication scanner and gitleaks before
building. The workflow has no private remote, no private cache credentials and
no private substituter. Releases, artifacts and the manifest commit are created
and pushed only through the public GitHub repository.
