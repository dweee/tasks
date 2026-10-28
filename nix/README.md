# Tasks.org desktop public export

This is the sanitized public export of a private Tasks.org desktop fork. Native
builds run on GitHub's Linux and Apple Silicon runners, and all published
artifacts, packages and releases come from this public repository. The export
policy and reproduction steps are documented in
[docs/public-export.md](../docs/public-export.md).
`upstream` tracks https://github.com/tasks/tasks. The desktop app is upstream's
Compose client, built from this export's source for Linux (`x86_64-linux`) and
macOS (`aarch64-darwin`). Android, iOS and Conveyor store publication are outside
this desktop pipeline; their original workflows are kept in `upstream-workflows`.

## Build and delivery

Every source push to `main` and GitHub pull request compiles and tests both desktop platforms.
CI uses upstream's Gradle wrapper and JDK 25, runs the data, KMP and Compose
desktop tests, and makes a release-mode self-contained jpackage application.
Release mode uses the normal Tasks.org profile rather than Tasks.org.debug.
SQLite's bundled
JVM dependency already supports both targets; Conveyor's extra Windows ARM and
Intel Mac SQLite overrides are not needed here. `-PstandaloneDesktop=true` limits
this exception to the two supported native hosts; Conveyor builds retain their
original native-resource validation. No upstream publishing or signing
credentials are used. PostHog's application key is left empty.

The Linux and Mac bundles are imported into Nix using their SHA-256 hashes.
The Nix wrapper patches Linux ELF interpreters and supplies graphics, font and
keyring libraries; macOS retains and verifies jpackage's ad-hoc signatures.
CI builds these packages before publishing. No binary cache credentials or
private substituters are configured; builds use the public Nix cache and the
GitHub Actions cache only. Pull requests run the same checks and do not publish.

Only after both platforms pass does CI publish an immutable
`desktop-<source-commit>` GitHub prerelease and commit `nix/sources.json` with the
version, source revision, URLs and hashes. The manifest commit and release tag are
pushed to this repository's `origin` (`dweee/tasks`) only. Non-fast-forward
publication fails rather than overwriting new source commits. The package is the
tested source build from that release, rather than a build of an unpinned
upstream binary. The manifest is initially populated by the first successful
desktop workflow.

```sh
nix build .#tasks-org
nix run .
```

Nix packages consume the pinned CI artifacts. To compile edited Kotlin locally,
use JDK 25 and an Android SDK matching `gradle/libs.versions.toml`, then run:

```sh
./gradlew :data:jvmTest :kmp:jvmTest :composeApp:desktopTest
./gradlew -Prelease -PstandaloneDesktop=true :composeApp:createDistributable
```

On headless Linux run this under `xvfb-run`. `nix develop` supplies JDK 25 and Git;
it does not provision the Android SDK used during Gradle configuration.

## NixOS and nix-darwin

```nix
inputs.tasks-org.url = "git+https://github.com/dweee/tasks.git";
inputs.tasks-org.inputs.nixpkgs.follows = "nixpkgs";
# Add inputs.tasks-org.nixosModules.default to NixOS modules, or
# inputs.tasks-org.darwinModules.default to nix-darwin modules, then:
programs.tasks-org.enable = true;
```

Both modules install the package and leave accounts, database and sync settings
under the app's control. Configure cache substituters and signing keys in the
consuming system. Keep the nixpkgs revision aligned with that system to reuse the
same package closure. Updating the flake input advances to a tested release; CI
does not activate personal machines or reboot them.

## Publication checks

CI scans the exported fork history for secrets, IP addresses, local home paths
and configured private identifiers before building. `PUBLICATION_DENYLIST` is a
repository secret containing a JSON array of private identifiers; its contents
must never be committed. Pull requests run the generic checks without that
secret. The scan base is the upstream commit from which this desktop export was
created, and gitleaks scans the same range. See
[docs/public-export.md](../docs/public-export.md) for the fail-closed export
procedure.
