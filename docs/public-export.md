# Public export procedure

This repository is generated from a private fork. The public branch must never
contain private infrastructure, credentials, cache endpoints or deployment
configuration. The export is reproducible and fails closed: if the private
policy, the guard checks or the secret scan are unavailable, the export stops.

## What is exported

- Upstream history up to the recorded base commit `28f55dcf8e` is preserved
  unchanged, including authors and ancestry.
- Fork commits are rewritten only in the range `28f55dcf8e..<source-branch>`.
- The following private CI paths are removed from every fork commit:
  - `.forgejo/`
  - `ci/`
  - `.github/scripts/forgejo-askpass.sh`
- Remaining added lines and commit metadata must pass
  `.github/scripts/check_publication.py` with the private policy enabled.
- The same range must pass gitleaks.

## Policy

`PUBLICATION_DENYLIST` is a JSON array of private identifiers. It is supplied
through the repository secret in CI and through the local environment for an
export. It must never be committed, printed or copied into the repository.

The export script also accepts `PUBLICATION_DENYLIST_FILE`, a path to a
read-only policy file outside the repository. The file is read into the
environment and is not copied into the export.

## Reproduce an export

Run from a clean checkout of the private source repository:

```sh
PUBLICATION_DENYLIST_FILE=/path/to/denylist.json \
  scripts/export-public.sh <source-ref> [export-branch]
```

The script:

1. refuses to run with a dirty worktree, a missing policy, an invalid policy or
   a missing `git-filter-repo`;
2. clones the local repository into a temporary export directory;
3. rewrites only `28f55dcf8e..<export-branch>` and removes the private paths;
4. runs the publication scanner with `REQUIRE_PRIVATE_POLICY=true`;
5. runs gitleaks over the same range; and
6. prints the export directory and branch without pushing anything.

Review the result, apply the public-only content changes, commit with the
repository identity, and push deliberately. Never force-push history and never
push `main` to a private remote. Old branches and tags that still point at the
pre-export history must be replaced or deleted on the public host after review;
the export script writes their names to `unsafe-public-refs.txt` in the export
directory so they can be handled explicitly.

## CI

`.github/workflows/desktop.yml` runs the publication scanner and gitleaks before
building. The workflow has no private remote, no private cache credentials and
no private substituter. Releases, artifacts and the manifest commit are created
and pushed only through the public GitHub repository.
