<p align="center">
  <img src="assets/solderpy-loader-logo.png" alt="SolderPy Loader logo" width="192">
</p>

# SolderPy Loader

A loader-neutral Minecraft bootstrap mod for the
[solder.py bootstrap API](https://github.com/Thorfusion/solder.py/blob/dev/docs/bootstrap-api.md). It uses
[Relauncher](https://github.com/juanmuscaria/relauncher) to restart Minecraft
once with this JAR attached as a Java agent. The agent reconciles the modpack
before Forge, NeoForge, Fabric, or Quilt discovers mods.

This project is an early implementation. It provides an interactive pre-launch
optional-content screen, API-provided basic optionals and advanced
single/multiple groups, dependency closure, ETag revalidation, post-download
MD5/size verification, bounded parallel downloads with live throughput, direct
raw-JAR installation, parallel safe ZIP extraction for non-mod content, optional
installed 7-Zip acceleration, file ownership receipts, and transactional
rollback. The API controls the available choices, rules, and defaults; the
local JSON configuration cannot override them.

NOTE: solder.py uses native downloads when exporting to CurseForge or Modrinth.
SolderPy Loader is intended for configs, resources, custom mods, and their
distribution while solder.py remains the single source of truth. For a
Modrinth-mapped JAR, the bootstrap API can provide ordered sources: the loader
tries the native Modrinth file first, verifies solder.py's stored MD5 and size,
and retries the Solder-hosted JAR if download or verification fails.
DO NOT HOST FILES YOURSELF WITHOUT REQUIRED LICENSE/PERMISSIONS
SOLDER.PY WHEN USED CORRECTLY allows one to use native downloads at curseforge and modrinth, aaand being on technic pack with solder, without having 3 different modpack manifest to manage.

## Requirements

- Java 8 or newer at runtime. Builds use JDK 25 with `--release 8`, so the
  distributed JAR remains compatible with Java 8.
- Relauncher 1.1.1 and the built loader JAR in the top-level `mods/` directory.
  Modrinth and manual installations use the full
  `relauncher-universal-1.1.1.jar`. CurseForge installs
  `relauncher-universal-1.1.1-curseforge.jar`, which omits native libraries
  and uses Relauncher's pure-Java fallback strategy.
- A solder.py server exposing bootstrap schema version 1.

Relauncher supports Forge 1.6.4+, NeoForge, Fabric, and Quilt. Its upstream
launcher matrix currently supports the vanilla, Prism, CurseForge, Modrinth,
and ATLauncher launch paths. Technic Launcher V3 is **expected compatible,
but not yet verified**. MultiMC's stdin launch protocol cannot be replayed and
is not supported by Relauncher.

The CurseForge dependency declaration points to the Relauncher project, whose
main CurseForge file is its policy-compliant `-curseforge` edition. SolderPy
Loader does not bundle Relauncher or any native libraries itself.

## Configuration

Create `config/solderpy-loader.json` in the Minecraft instance:

```json
{
  "enabled": true,
  "api": "https://solder.example.com/api/",
  "modpack": "example-pack",
  "build": "recommended",
  "target": "auto",
  "clientId": null,
  "bootstrapJava": null,
  "bootstrapJavaMajor": 25
}
```

`target` defaults to `auto`. Relauncher identifies whether Forge, NeoForge,
Fabric, or Quilt is starting a client or dedicated server and passes that side
to the loader before the bootstrap request. Set it explicitly to `client` or
`server` only to override detection. If Relauncher reports an unknown side, the
loader stops with an actionable error instead of installing the wrong files.

`clientId` is the non-secret Solder client UUID (`cid`) used for a private
pack. Solder API keys are deliberately not accepted in the local config.
`bootstrapJava` may name a Java home or Java executable used only by SolderPy's
bootstrap worker; it does not change Minecraft's runtime. Leave it `null` to
let Relauncher select an installed runtime for the worker. Java 25 is preferred
by default, even when Minecraft itself must remain on Java 8. If Java 25 is not
installed, the newest compatible installed JVM is used. `bootstrapJavaMajor`
changes the preferred automatic major. For example, this pins only the worker
to a particular launcher-provided Mojang JRE:

```json
{
  "bootstrapJava": "F:\\Technic\\runtimes\\jre-legacy"
}
```

On a pack's first launch, the selection screen starts from the API manifest's
`selection_policy.default_memberships`. The successful build, manifest hash,
ETag, selected memberships, and installed-file receipts are saved in
`.solderpy-loader/state.json`. An unchanged manifest reuses those exact choices
without opening the screen. When the manifest changes, the screen opens with
remembered choices for options that still exist and current API defaults for
new options. The loader combines the user's choices with
`required_memberships`, closes required dependencies, and validates advanced
group limits. The API remains authoritative for the available choices and
rules; **Restore API Defaults** discards the remembered choices shown on the
screen. A legacy `selections` field in this file is rejected so configuration
cannot override the server.

## Optional selection

On graphical client launches, the loader displays a Swing screen before mod
discovery on the first install and when the API manifest changes. Basic
optionals and ungrouped advanced optionals use checkboxes;
advanced single-choice groups use radio buttons, and multiple-choice groups
use checkboxes with their API-provided limits. Package and group descriptions
also come from the manifest. Package descriptions open through bounded,
scrollable **Details** dialogs so long API text cannot hide later choices.
Basic optionals are split into six-item pages, and each page is scrollable.

The screen is initialized from remembered choices for existing options and API
defaults for new options. Closing it or selecting **Cancel Launch** aborts
startup. Dedicated servers request the server manifest, install only `SERVER`
and `BOTH` packages, skip the screen, and always use the API defaults.
Graphical headless clients skip the screen and reuse remembered choices where
possible.

Plain HTTP is rejected except for loopback development servers. Downloads are
limited to 512 MiB per artifact and ZIPs to 2 GiB expanded / 100,000 entries
by default. The optional `limits` object can lower those resource ceilings and
tune download/extraction concurrency:

```json
{
  "limits": {
    "maxDownloadBytes": 536870912,
    "maxExpandedBytes": 2147483648,
    "maxArchiveEntries": 100000,
    "maxConcurrentDownloads": 4,
    "maxConcurrentExtractions": 1
  }
}
```

The loader downloads up to four changed packages at a time by default and
reuses HTTP connections across packages on the same host. Its compact progress
window shows up to four active downloads instead of building a long package
list. After all downloads finish, it verifies their MD5 hashes in a separate phase, then
extracts/stages one package at a time. Set `maxConcurrentDownloads` and
`maxConcurrentExtractions` between 1 and 16 to tune network, CPU, and disk use
for faster hardware such as an NVMe SSD. The final transactional commit remains
ordered.

The premain agent waits for a short-lived bootstrap worker JVM before allowing
mod discovery to continue. This keeps downloads, verification, and extraction
outside the interpreter-only early startup window found in legacy Java 8
runtimes while preserving the requirement that managed files are ready before
Minecraft scans them.

SolderPy Loader also registers a Java 8-or-newer requirement with Relauncher.
That requirement participates in selecting Minecraft's runtime, but SolderPy's
worker runtime is independent. SolderPy prefers the newest installed Java 25
runtime for the isolated worker, then falls back to the newest detected Java 8
or newer. For example, Minecraft can remain on Mojang Java 8u51 while downloads
run through Java 25. The explicit `bootstrapJava` path overrides automatic
detection; this also covers launchers that store private runtimes outside the
locations Relauncher scans. Relauncher passes an ordered list of up to eight
compatible runtimes to the agent: the requested major first, then other modern
JVMs, then older compatible JVMs, with the launcher's current Java always
retained as a fallback. If a runtime executable cannot start, the worker tries
the next one. Bootstrap work is never moved back into the slow premain thread.
The policy is based on detected JVMs rather than launcher branding, so the same
logic applies when Relauncher is started by different Minecraft launchers.

If the 7-Zip command-line program is installed, the loader discovers it and
uses `-mmt=on` for normal ZIP extraction. Archives with thousands of small
files use the faster built-in single-pass extractor to avoid a separate
per-file 7-Zip audit. On Windows it checks the normal 7-Zip
installation directories before `PATH`; on other systems it checks `7zz`,
`7z`, and `7za`. `SOLDERPY_7ZIP` or the `solderpy.loader.7zip` JVM property can
name an explicit executable. Every archive is inspected with Commons Compress
before 7-Zip runs and the isolated output is audited afterward. If 7-Zip is
unavailable or exits unsuccessfully, the loader cleans that attempt and falls
back to its built-in extractor. 7-Zip is invoked as an optional user-installed
program and is not bundled in the loader JAR.

Runtime cache and ownership receipts live under `.solderpy-loader/`. A `304 Not
Modified` response or an identical API manifest hash reuses the saved optional
choices without showing the selection screen. Packages whose version, artifact
MD5, install path, and installed file hashes still match their receipts are not
downloaded or extracted again. Package downloads and installed-file receipts
use MD5; the manifest itself retains its API-provided SHA-256 hash. A missing or
locally changed managed file is downloaded again. A
failed install restores the previous files and state before Minecraft
continues. By default, an update failure aborts startup; `failOpen: true`
continues only after failures known to have left a complete, hash-verified
previous installation intact. First installs, lock failures, corrupt local
state, and incomplete rollbacks always abort.

## Artifact delivery

Minecraft `MOD` packages use the verified raw JAR exposed by solder.py. The
loader streams and verifies that JAR, then moves it directly into `mods/`
without opening a ZIP. Content that inherently contains multiple paths, such
as `CONFIG`, `RES`, and `NONE` packages, uses the normal Solder ZIP. Legacy
`MOD` packages without a verified raw JAR also fall back to their Solder ZIP so
older builds keep working; creating a raw JAR later automatically restores the
faster direct-install path.

## Build

```text
./gradlew clean check shadowJar
```

The distributable, dependency-contained JAR is written to `build/libs/`. Its
libraries are relocated to private package names so Minecraft's older bundled
libraries cannot override them. The `-thin.jar` is not a release artifact.
Relauncher Core is compile-only because the universal Relauncher JAR provides
its SPI at runtime.

## Launch sequence

1. Relauncher scans the top-level `mods/` directory and finds this project's
   `CommandLineProvider` service.
2. The provider adds `-javaagent:<loader jar>` and Relauncher performs its one
   controlled JVM restart.
3. `SolderPyAgent.premain` fetches and validates the authoritative bootstrap
   manifest, presents optional choices when the manifest changed, resolves
   dependencies, stages changed files, and commits the update transaction.
4. The mod loader starts discovery and sees the reconciled `mods/` directory.

The bootstrap package itself should be represented in Solder as type `BOOTSTRAP`.
The API consequently reports it as `bootstrap_managed: false`, preventing the
loader from trying to replace its own active JAR.

## License

All rights reserved. See [LICENSE](LICENSE).
