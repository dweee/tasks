# Tasks.org desktop fork

Fork: https://github.com/dweee/tasks.
`upstream` tracks https://github.com/tasks/tasks. The desktop app is upstream's
Compose client, built from this fork's source for Linux x86_64 (`x86_64-linux`) and
macOS Apple Silicon (`aarch64-darwin`). Android, iOS and Conveyor store publication are outside
this desktop pipeline; their original workflows are kept in `upstream-workflows`.

## Build and delivery

Every push to `main` and pull request compiles and tests both desktop platforms.
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
CI builds these packages before publishing. Trusted `main` builds push complete
Nix closures to `the configured binary cache` using the repository secret
`ATTIC_TOKEN`. Pull requests cannot use that secret or publish artifacts/cache.

Only after both platforms pass does CI publish an immutable
`desktop-<source-commit>` GitHub prerelease and commit `nix/sources.json` with the
version, source revision, URLs and hashes. GitHub's workflow token does not
retrigger CI for this manifest-only commit. The package is the tested source
build from that release, rather than a build of an unpinned upstream binary.
The manifest is initially populated by the first successful desktop workflow.

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
inputs.tasks-org.url = "github:dweee/tasks";
inputs.tasks-org.inputs.nixpkgs.follows = "nixpkgs";
# Add inputs.tasks-org.nixosModules.default to NixOS modules, or
# inputs.tasks-org.darwinModules.default to nix-darwin modules, then:
programs.tasks-org.enable = true;
```

Both modules install the package and leave accounts, database and sync settings
under the app's control. The fleet already configures the the configured binary cache cache public
key and host-specific pull credentials. Keep the nixpkgs revision aligned with
the fleet to reuse the same package closure. Updating the fleet input advances
to a tested release; CI does not activate personal machines or reboot them.
