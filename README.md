<p align="center">
  <img src="assets/solderpy-loader-logo.png" alt="SolderPy Modpack Loader logo" width="192">
</p>

# SolderPy Modpack Loader

## Workflow with solder.py

1. Find a mod on Modrinth and import it into solder.py.
2. solder.py stores the Modrinth project and version IDs.
3. When creating a Modrinth pack, solder.py uses those IDs to reference the native Modrinth files.
4. solder.py also downloads the selected version and packages it for distribution through Technic.
5. If the mod has been linked to its CurseForge project, solder.py resolves the corresponding CurseForge modpack format.
6. When the mod is updated through Modrinth, solder.py keeps one version record that can be exported in the native formats for Modrinth and CurseForge while remaining available through Technic.

### Where SolderPy Modpack Loader comes in

Modrinth, CurseForge, and Technic all have different distribution capabilities and limitations. Native platform downloads are used whenever possible, while SolderPy Modpack Loader handles content and features that the platforms cannot provide consistently:

- Rich optional-content selection for players.
- Distribution of configuration files, custom mods, resource packs, and other pack-specific content without publishing each item as a separate Modrinth or CurseForge project.
- Server installation and updating.
- Delivery of content managed through Maven or GitHub integrations.
- Consumption of builds maintained through solder.py's write API.

This approach lets solder.py remain the single source of truth while each platform uses its native download system wherever possible.

SolderPy Modpack Loader is a loader-neutral Minecraft bootstrap mod for the
[solder.py bootstrap API](https://github.com/Thorfusion/solder.py/blob/dev/docs/bootstrap-api.md).
It updates a pack before mod discovery, presents API-defined optional content,
and works on both clients and dedicated servers.

The loader uses [Relauncher](https://github.com/juanmuscaria/relauncher) to
perform one controlled JVM restart with SolderPy Modpack Loader attached as a Java
agent. This lets it prepare files before Forge, NeoForge, Fabric, or Quilt scans
the instance.

> [!IMPORTANT]
> Use native Modrinth and CurseForge downloads whenever their policies require
> them. Only host artifacts through solder.py when you have permission to
> redistribute them. SolderPy Modpack Loader does not grant redistribution rights.

## Why use it?

- One solder.py build can serve clients, dedicated servers, and Technic packs.
- Basic and advanced optional content is defined by the API, not duplicated in
  a local configuration file.
- Changed packages are downloaded concurrently, verified, staged, and committed
  as one recoverable update.
- Available disk space is checked before network downloads and again before
  extraction and rollback data are created.
- Unchanged packages and remembered optional choices are reused on later
  launches.
- Platform-native files can be tried first with a verified Solder-hosted
  fallback.
- A separate modern Java runtime can handle bootstrap work while the game
  remains compatible with Java 8.

## How it fits with solder.py

Use solder.py as the single source of truth for projects, versions, files, and
pack builds:

1. Import a Modrinth project and version into solder.py.
2. Link its CurseForge project when a corresponding project exists.
3. Export platform-native references for Modrinth and CurseForge.
4. Keep the same version available through Solder for Technic and other
   supported launch paths.
5. Let SolderPy Modpack Loader deliver pack-specific content that native manifests
   cannot express consistently, including interactive optionals, configuration,
   resource packs, custom content, Maven/GitHub-managed files, and server
   installation.

This avoids maintaining three unrelated modpack manifests while still using
each platform's native distribution system where possible.

## Compatibility

| Area | Support |
| --- | --- |
| Mod loaders | Forge 1.6.4+, NeoForge, Fabric, and Quilt through Relauncher |
| Launchers | Vanilla, Prism Launcher, CurseForge, Modrinth App, ATLauncher, and Technic Launcher V3 |
| Dedicated servers | Supported; the side is detected automatically and no selection window is opened |
| Operating systems | Windows, Linux, and macOS wherever the selected launcher, Java runtime, and Relauncher are supported |
| MultiMC | Not supported because Relauncher cannot replay its stdin launch protocol |

Technic Launcher V3 is covered by the same Relauncher path and the project
includes a Forge 1.7.10 exit-handling workaround.

## Installation

1. Put the SolderPy Modpack Loader release JAR in the instance's top-level `mods/`
   directory.
2. Put the matching Relauncher 1.1.1 JAR beside it.
3. Create `config/solderpy-loader.json` using the example below.
4. Start the client or dedicated server normally.

Use `relauncher-universal-1.1.1.jar` for manual and Modrinth installations.
For CurseForge, use `relauncher-universal-1.1.1-curseforge.jar`. The
CurseForge edition intentionally omits bundled native libraries and uses
Relauncher's pure-Java fallback.

SolderPy Modpack Loader does not bundle Relauncher or its native libraries. Its own
runtime dependencies are shaded and relocated inside the release JAR.

For upgrade compatibility, existing technical identifiers retain the original
`solderpy-loader` slug: release JAR names, the Maven artifact ID,
`config/solderpy-loader.json`, `.solderpy-loader/`, Java package names, and the
HTTP user agent. The displayed product name is SolderPy Modpack Loader.

## Configuration

Create `config/solderpy-loader.json` inside the Minecraft instance:

```json
{
  "enabled": true,
  "api": "https://solder.example.com/api/",
  "modpack": "example-pack",
  "build": "recommended",
  "target": "auto",
  "source": "hybrid",
  "platform": null,
  "launcherOwnedMemberships": null,
  "manifestVerification": {
    "required": true,
    "algorithm": "SHA256withECDSA",
    "curve": "secp256r1",
    "publicKeyFormat": "X.509",
    "encoding": "base64",
    "keyId": "sha256:<64 lowercase hexadecimal characters>",
    "publicKey": "<base64 X.509 public key from the solder.py export>"
  },
  "clientId": null,
  "bootstrapJava": null,
  "bootstrapJavaMajor": 25,
  "failOpen": false,
  "alwaysHashFiles": false,
  "limits": {
    "maxDownloadBytes": 536870912,
    "maxExpandedBytes": 2147483648,
    "maxArchiveEntries": 100000,
    "maxConcurrentDownloads": 4,
    "maxConcurrentExtractions": 1
  }
}
```

| Setting | Default | Purpose |
| --- | --- | --- |
| `enabled` | `true` | Enables the bootstrap relaunch and update |
| `api` | Required | Absolute solder.py URL ending in `/api/` |
| `modpack` | Required | Solder modpack slug |
| `build` | `recommended` | Build name or Solder recommendation channel |
| `target` | `auto` | Uses Relauncher side detection; `client` and `server` are explicit overrides |
| `source` | `hybrid` | Controls package ownership: `hybrid` lets the native platform own exact supported files, while `solder` keeps ordinary build packages Loader-owned |
| `platform` | `null` | `modrinth`, `curseforge`, `prism`, or `technic` when the platform already owns some files |
| `launcherOwnedMemberships` | `null` | Exact build membership IDs installed by a generated native export; `[]` explicitly means none |
| `manifestVerification` | Required at launch | Public ECDSA verification key pinned by the generated export; never contains the server's private key |
| `clientId` | `null` | Non-secret Solder client UUID (`cid`) for a private pack |
| `bootstrapJava` | `null` | Java home or executable used only by the bootstrap worker |
| `bootstrapJavaMajor` | `25` | Preferred automatically detected worker Java major |
| `failOpen` | `false` | Allows launch after an update failure only when the previous installation passes the configured integrity checks |
| `alwaysHashFiles` | `false` | Locally disables the file-metadata fast path and recalculates MD5 whenever an enforced Loader-owned or launcher-owned file is checked; the signed API can also require this |
| `limits` | Shown above | Resource ceilings and download/extraction concurrency |

Solder API keys are deliberately not accepted in the local configuration.
Plain HTTP is rejected except for loopback development servers.

Do not invent or copy the `manifestVerification` values between solder.py
installations. Generated exports pin the installation-wide public key. Existing
packs created before manifest signing must be re-exported or reinstalled; the
loader refuses to trust package URLs before a valid pinned key is available.

The resource ceilings may be lowered but not raised beyond the built-in
defaults. Download and extraction concurrency can be set from 1 to 16. Four
downloads and one extraction at a time are conservative defaults for slower
SSDs; faster storage can use a manual override.

Do not add a `selections` field. Optional definitions, rules, and defaults are
authoritative API data, and the legacy local field is rejected.

For every Loader-owned raw JAR, both source modes follow the API's ordered
artifact sources: a configured per-version override, a saved provider URL such
as Modrinth or Maven, and the Solder-hosted artifact as the final fallback.
`source: "solder"` means the native platform does not own ordinary build
packages; it does not force their bytes to come only from Solder hosting.
`source: "hybrid"` lets the native platform install exact supported matches
and leaves the remaining packages to SolderPy Modpack Loader.

Set `platform` only when SolderPy Modpack Loader was installed by the matching native
export. The API keeps the complete dependency graph and labels every package's
`install_owner` as `loader`, `launcher`, or `ignored`. Launcher-owned packages
remain visible for dependency resolution but are never downloaded twice.

Generated Modrinth, CurseForge, and Prism exports also write the exact native
membership IDs to `launcherOwnedMemberships`. A non-null list makes the loader
request `ownership=explicit` and apply that list over the server's ownership
inference. Do not copy these build-local IDs between builds. `null` uses server
inference, while `[]` explicitly makes every non-ignored package Loader-owned.
Technic normally uses server-owned inference: its normal required packages can
remain launcher-owned while SolderPy Modpack Loader manages optional and advanced
content. Provider file names are never used to recognize launcher-owned mods.
The first verification locates them by the raw-JAR MD5 values supplied by the
signed bootstrap manifest and records their actual local paths. Later launches
reuse an entry when that path, size, and last-modified time are unchanged; a
move or metadata change falls back to MD5 and refreshes the local record. Set
`alwaysHashFiles` to `true` to bypass this optimization. Every expected
launcher-owned package must be present under `mods/`; a missing or wrong version
stops launch with an error asking the user to repair or reinstall the pack in
their launcher. Extra user files remain allowed unless strict cleanup is active.
Manual installations should normally leave `platform` and
`launcherOwnedMemberships` as `null`.

## Optional content

On the first graphical client launch, SolderPy Modpack Loader displays the optionals
provided by the bootstrap manifest:

- Basic and ungrouped options use checkboxes.
- Advanced single-choice groups use radio buttons.
- Advanced multiple-choice groups use checkboxes with API-defined limits.
- Basic options are split into pages of at most six entries, and every page is
  scrollable.
- Long package and group descriptions open in bounded, scrollable detail
  dialogs.

Closing the screen or choosing **Cancel Launch** stops startup. **Restore API
Defaults** resets the choices currently shown to the manifest defaults.

After a successful install, selected memberships and their stable group-key /
package-slug identities are stored in `.solderpy-loader/state.json`. An
unchanged manifest reuses them without opening the screen again. When a cloned
or updated build changes database membership IDs, existing options still retain
their saved choices; new options start from the current API defaults. Required
memberships and dependency closure are always enforced.

Dedicated servers do not open a GUI. They request the server manifest, use the
API defaults, and install only `SERVER` and `BOTH` packages. A headless
client reuses compatible saved choices where possible and otherwise uses API
defaults.

## Download and installation behavior

The bootstrap is split into distinct phases so network, verification, and disk
work remain visible. On graphical clients, one SolderPy Modpack Loader status window is
shown before manifest resolution and remains open behind the optional-content
selector. After the player continues, the same window immediately shows the
installed-file check instead of leaving an unexplained blank interval:

1. Discover signed-bootstrap support, verify the complete manifest against the
   public key pinned by the export, then resolve optional memberships.
2. Hash-check launcher-owned packages and Loader-owned enforced outputs, then
   preflight the disk space required for missing cached artifacts.
3. Download up to four changed packages concurrently by default. The progress
   window shows the active downloads and live throughput.
4. Verify completed artifacts against solder.py's stored MD5 and size.
5. Inspect verified ZIPs and check exact expanded, staging, rollback, and safety
   margin requirements.
6. Extract or stage packages, one at a time by default.
7. Commit the complete update transaction.
8. Start normal mod discovery.

When the API supplies ordered platform sources, the loader tries them in order.
For example, it can try a native Modrinth file first and fall back to the
canonical Solder URL if the download or verification fails.

Downloads are persisted under `.solderpy-loader/cache/downloads` by their
expected MD5. A failed update therefore keeps every artifact that finished and
verified, and the next launch reuses those files instead of downloading the
whole pending update again. Incomplete source-specific `.part` files are also
kept and resumed with an HTTP `Range` request. If a server does not support
range requests, the loader safely restarts that file from byte zero.

Before the first download, the loader totals the manifest artifact sizes and
subtracts reusable cache files on the same storage volume. If an artifact has
no declared size, its configured maximum download size is reserved instead.
The check also keeps a safety margin of 10%, bounded between 256 MiB and 2 GiB.
An insufficient-space error reports the required, available, and missing
space, then stops before making a download request.

The manifest size describes the compressed artifact, so a ZIP's exact expanded
size is not known until its verified central directory can be inspected. After
downloads finish, the loader performs a second check before extraction. It
includes the exact declared sizes of all ZIP entries, staged JAR copies,
existing managed files that may need rollback backups, and the same safety
margin. Therefore no live modpack file is changed when either space check
fails. An optional expanded-size field in the API could move this second check
ahead of downloading, but it is not required for safe behavior.

Every successful bootstrap response must contain a `SHA256withECDSA` signature
whose key ID matches `manifestVerification`. The loader reproduces solder.py's
canonical JSON encoding and authenticates the entire response except the
detached `signature` object, including unknown additive fields and the optional
`changes` summary. Verification happens before deserialization, selection, or
use of any download URL. The signed JSON object is retained in
`.solderpy-loader/state.json` and reverified before a cached `304 Not Modified`
response or `failOpen` fallback is trusted. Tampered, unsigned, wrongly keyed,
or unsupported signatures stop launch.

A package with one download URL gets up to three total attempts for network,
HTTP, size, and MD5 failures. When several sources are available, provider
mirrors are attempted once so fallback remains fast; only the canonical Solder
source gets up to three attempts. A hash failure discards that untrusted file
before the next attempt. Short retry backoff prevents a temporary server error
from immediately aborting launch.

Failures name the affected package using its display name, version, and stable
slug where available. Download errors also identify the provider, host, attempt
number, and final reason without exposing a signed or token-bearing URL. A
graphical client keeps the Java status window open and shows a scrollable error
dialog; headless clients and servers receive the same reason in their log. The
worker saves the latest details to `.solderpy-loader/last-error.txt`, allowing
the parent Java agent and launcher log to report the real cause instead of only
a child-process exit code. The report is cleared at the start of the next
bootstrap attempt.

### JAR and ZIP package handling

SolderPy Modpack Loader verifies and stages a package before changing the live game
directory. The downloaded artifact's size and MD5 must match the manifest. A
failed verification is retried according to the download rules above and the
untrusted artifact is never installed.

Raw JAR and ZIP packages are then handled differently:

- A raw JAR normally represents one `MOD` output. The verified JAR is staged
  directly at the manifest's install path, such as `mods/example.jar`. Its
  artifact MD5 is also recorded as the installed file's MD5.
- A ZIP can produce multiple `CONFIG`, `RES`, `NONE`, or legacy `MOD` outputs.
  The loader inspects its complete entry list before extraction, extracts into
  a transaction directory outside the live instance, and calculates an MD5
  for every extracted file. Those per-file hashes are derived from the
  already-verified archive and become the installed ownership receipt.
- A legacy `MOD` package without an available raw JAR follows the ZIP rules.

If 7-Zip is installed, the loader can use its multithreaded extraction for
normal archives. Archives containing thousands of small files use the built-in
single-pass extractor because it avoids costly per-file process work. On
Windows, normal 7-Zip installation directories are checked before `PATH`; on
other systems, `7zz`, `7z`, and `7za` are checked. Set `SOLDERPY_7ZIP`
or the `solderpy.loader.7zip` JVM property to select an executable explicitly.
7-Zip is optional and is never bundled. Its command-line process stays hidden;
the Java status window reports archive inspection, background extraction,
post-extraction verification, and staging progress.

Every archive is inspected before extraction and audited afterward. Path
traversal, symbolic links, oversized artifacts, excessive expanded data, and
excessive entry counts are rejected.

The built-in extractor calculates each file's MD5 while writing it. When
7-Zip is used, SolderPy Modpack Loader separately walks the extracted result, checks
that every expected entry exists with the expected size, rejects unexpected
entries, and calculates every file's MD5 before staging it. The live game
directory is changed only after all selected packages have downloaded,
verified, and staged successfully.

### Existing files, repairs, and user-added files

SolderPy Modpack Loader records only the paths it owns, together with their installed
MD5 values and local file metadata, in `.solderpy-loader/state.json`. Later
launches normally accept an unchanged path, size, and last-modified time; if
any value changes, the Loader calculates MD5 before deciding whether to repair
the file. It does not wipe or generally clean the `mods`, `config`,
`resourcepacks`, or other game directories.

| File situation | SolderPy Modpack Loader behavior |
| --- | --- |
| The target path does not exist before its first managed installation | Installs the packaged file and records ownership and its MD5. |
| A file already exists at the target path and has the expected MD5 | Leaves the existing file in place and records ownership. Its contents do not need to be rewritten. |
| A file already exists at a required target path but has different contents | Replaces it with the packaged file when the transaction commits. The temporary transaction backup exists only for rollback and is removed after a successful commit. |
| A managed file still matches its receipt on a later launch | Leaves it in place. No package download or extraction is needed when the package version, artifact MD5, install path, and every installed file still match the receipt. |
| A managed file is missing, corrupted, or manually edited | Marks the package for repair. The verified artifact is reused from the cache when possible or downloaded again, and the package is staged again. Missing or mismatching outputs are restored; outputs that already match are left untouched. |
| A user adds a file at a path that is not listed in any ownership receipt | Ignores and preserves it, including extra files placed beside managed mods or configs. |
| A user-added file occupies a path required by a selected package | Treats the path as a package output: an identical file is adopted, while a different file is replaced by the packaged version. |
| An updated package no longer contains one of its former files | Deletes the former output only when it still matches the old receipt. A locally modified or unverifiable former output is preserved and becomes unmanaged. |
| A package is removed or deselected | Deletes its former outputs only when they still match the old receipt. Locally modified or unverifiable outputs are preserved and become unmanaged. |
| A package changes from Loader ownership to launcher ownership | Drops the Loader receipt without deleting the launcher's file. |
| A launcher-owned package is present under any filename with the expected raw-JAR MD5 | Accepts it without downloading a duplicate. |
| A launcher-owned package is missing or has different contents | Stops launch and names the affected package; no live files are changed. |
| Two selected managed packages claim the same output path | Stops the update with an error instead of choosing one package or silently overwriting the other. |

The metadata fast path avoids rereading hundreds of megabytes on every launch.
It is intended to detect ordinary edits, replacements, moves, and launcher
updates. A local program can deliberately change bytes while restoring both
the old size and timestamp; use local `alwaysHashFiles: true`, or enable the
signed API policy `update_policy.always_hash_files`, when every verification
must read and hash file contents instead of trusting unchanged metadata. Either
true value forces hashing: a local setting cannot disable an API requirement.

The table describes the default behavior when
`update_policy.remove_unlisted_mod_files` is false. When the API sets it to
true and the saved build version changes, the loader performs a strict
reconciliation of regular files under `mods/`. It preserves the newly resolved
Loader-owned outputs, files whose content matches the new build's complete
allowed-hash set, the active Loader JAR, and Relauncher/runtime JARs. The hash
set contains resolved Loader-owned output receipts and the signed manifest's
raw-JAR MD5 values for resolved packages. Other regular
files are scheduled for removal. It
does not clean configs, resource packs, saves, or any directory outside
`mods/`, does not follow symbolic links, and does nothing on first install or
repeated launches of the same build version.

Strict cleanup is part of the same persistent transaction as installation.
Unlisted files are copied into rollback storage only after every new artifact
has passed size and MD5 verification and staging. Disk space is checked for
those backups, and any commit failure restores the removed files. If a
launcher-owned package lacks any trusted installed-file hash, cleanup stops
with an actionable error rather than guessing and possibly deleting a native
mod. No CurseForge API lookup, provider filename, or filename synchronization
is required for this comparison.

Consequently, a damaged managed config is repaired automatically, but a
deliberate edit to a managed config is also considered a mismatch and is
restored from its package. Files created by Minecraft or a mod after
installation are not repaired or deleted unless their exact paths were also
declared as outputs of a managed package.

The API's `enforce` setting controls whether an unchanged
package is enforced on every launch. With the default `true`, the Loader
verifies all owned outputs and repairs missing or modified files. With `false`,
the Loader trusts the existing receipt and does not inspect, repair, download,
or extract that package while its version, artifact MD5, and install target are
unchanged. Users and mods can therefore change or remove its files between
updates. When its package identity changes, such as an update from `2.0.0` to
`2.0.1`, the new package is installed normally and its managed files are reset
to the new version. Initial installation, update transactions, and normal
ownership-safe cleanup still apply for either setting.

## State, updates, and reset

Runtime state and file ownership receipts are stored under
`.solderpy-loader/`. The loader uses an HTTP `304 Not Modified` response or
an identical build and manifest hash to reuse the cached manifest and optional
choices.

The most recent worker failure is written to
`.solderpy-loader/last-error.txt`. It is diagnostic only and does not affect
state, ownership, cache reuse, transaction recovery, or the next retry.

Verified cached artifacts for the selected build are retained after a
successful update; obsolete artifacts and completed partial files are pruned.
Deleting `.solderpy-loader/cache/downloads` is safe when a manual cache reset is
needed, but forces affected content to be downloaded again.

Each installed package receipt records its version, artifact MD5, install
location, output paths, installed file hashes, sizes, and modification times.
Launcher-owned hash discoveries are cached in the same state file. The repair
and preservation rules are described in the preceding section.

Updates are staged away from the live instance and committed only after all
required work succeeds. Before changing any live path, the loader writes a
persistent transaction journal under `.solderpy-loader/transactions/`, copies
every file that will be replaced or removed, and saves the previous
`state.json`. It then marks the backups ready, applies the files, saves the new
state, and writes the final commit marker. Backup files are flushed to storage
before live changes begin.

A normal commit failure is rolled back immediately. If the JVM, launcher, or
computer stops after live changes have started, the journal and backups remain
on disk. At the beginning of the next launch—and before loading state or
contacting the API—the loader scans for interrupted transactions:

- A transaction without its final commit marker is rolled back to the previous
  files and previous `state.json`.
- A transaction with its final commit marker is already complete; the loader
  preserves the new files and removes only the leftover transaction data.
- Recovery is repeatable. If another interruption occurs while rolling back,
  the retained copies allow the following launch to retry the same rollback.
- Missing, malformed, or unsafe recovery data stops the launch instead of
  guessing and potentially deleting user files.

The multi-file commit is therefore crash-recoverable, although it is not a
single filesystem operation: files can briefly be in an intermediate state
during the commit itself. Minecraft does not continue until the commit or any
required recovery has finished. Transaction backups require temporary disk
space approximately equal to the live files being replaced or removed and are
deleted after a successful commit or rollback.

Do not manually delete `.solderpy-loader/transactions/` while recovery is
pending; it may contain the only retained copy of a file that was being
replaced. Preserve that directory for diagnosis if automatic recovery reports
that its journal or backups are damaged.

With `failOpen: false`, any update failure stops the launch. With
`failOpen: true`, startup may continue only if the complete previous
installation can be verified.

To make SolderPy Modpack Loader forget the saved build and optional selections, delete:

```text
.solderpy-loader/state.json
```

Keep `config/solderpy-loader.json`; deleting the state file makes the next
launch behave like a fresh reconciliation.

## Java runtimes

There are two separate Java choices:

- **Game JVM:** Relauncher starts Minecraft. SolderPy Modpack Loader declares support
  for Java majors 8 through 26, so legacy packs can remain on Java 8.
- **Bootstrap worker JVM:** Downloads, hashing, and extraction run in a
  short-lived child process. The loader prefers `bootstrapJavaMajor` (Java 25
  by default), then tries compatible installed fallbacks of the same
  architecture. The launcher's current JVM is retained as a final fallback.

Set `bootstrapJava` to a Java home, `bin` directory, or Java executable to
override worker detection:

```json
{
  "bootstrapJava": "F:\\Technic\\runtimes\\jre-legacy"
}
```

This setting does not change the JVM used by Minecraft. Relauncher 1.1.1
selects the game runtime by Java major, not an exact update number. A minimum
such as `1.8.0_52` therefore cannot currently require Java 8u52 or newer for
the game, and the manifest's Java runtime fields are not used for that
selection.

## Pack-author notes

- Represent the bootstrap package in Solder as type `BOOTSTRAP`. The API then
  reports `bootstrap_managed: false`, preventing the loader from replacing
  its own active JAR.
- Keep optional packages, groups, defaults, dependency rules, and descriptions
  in the bootstrap API.
- Use `target: "auto"` unless a launcher cannot report its side. Unknown
  automatic detection fails with an actionable error instead of installing
  files for the wrong side.
- Package downloads and installed-file receipts use MD5. The manifest retains
  its API-provided SHA-256 hash.

## Build from source

The build uses a JDK 25 toolchain but emits Java 8-compatible bytecode:

```text
./gradlew clean check shadowJar
```

The dependency-contained release JAR is written to `build/libs/`. Embedded
libraries are relocated to private package names so old libraries supplied by
Minecraft cannot override them. The `-thin.jar` is not a release artifact;
Relauncher Core remains compile-only because the universal Relauncher JAR
provides its SPI at runtime.

Published releases target:

- Modrinth project `5LpwENAj`
- CurseForge project `1702825`

A GitHub prerelease is published to both platforms as a beta release.

## License

All rights reserved. See [LICENSE](LICENSE).
