<p align="center">
  <img src="assets/solderpy-loader-logo.png" alt="SolderPy Loader logo" width="192">
</p>

# SolderPy Loader

## Workflow with solder.py
1. Find a mod on Modrinth and import it into solder.py.
2. solder.py stores the Modrinth project and version IDs.
3. When creating a Modrinth pack, solder.py uses those IDs to reference the native Modrinth files.
4. solder.py also downloads the selected version and packages it for distribution through Technic.
5. If the mod has been linked to its CurseForge project, solder.py resolves the corresponding CurseForge modpack format.
6. When the mod is updated through Modrinth, solder.py keeps one version record that can be exported in the native formats for Modrinth and CurseForge while remaining available through Technic.

### Where SolderPy Loader comes in
Modrinth, CurseForge, and Technic all have different distribution capabilities and limitations. Native platform downloads is used whenever possible, while SolderPy Loader handles content and features that the platforms cannot provide consistently:
- Rich optional-content selection for players.
- Distribution of configuration files, custom mods, resource packs, and other pack-specific content without publishing each item as a separate Modrinth or CurseForge project.
- Server installation and updating.
- Delivery of content managed through Maven or GitHub integrations.
- Automated updates through solder.py’s write API.
  This approach lets solder.py remain the single source of truth while each platform uses its native download system wherever possible.

SolderPy Loader is a loader-neutral Minecraft bootstrap mod for the
[solder.py bootstrap API](https://github.com/Thorfusion/solder.py/blob/dev/docs/bootstrap-api.md).
It updates a pack before mod discovery, presents API-defined optional content,
and works on both clients and dedicated servers.

The loader uses [Relauncher](https://github.com/juanmuscaria/relauncher) to
perform one controlled JVM restart with SolderPy Loader attached as a Java
agent. This lets it prepare files before Forge, NeoForge, Fabric, or Quilt scans
the instance.

> [!IMPORTANT]
> Use native Modrinth and CurseForge downloads whenever their policies require
> them. Only host artifacts through solder.py when you have permission to
> redistribute them. SolderPy Loader does not grant redistribution rights.

## Why use it?

- One solder.py build can serve clients, dedicated servers, and Technic packs.
- Basic and advanced optional content is defined by the API, not duplicated in
  a local configuration file.
- Changed packages are downloaded concurrently, verified, staged, and committed
  as one recoverable update.
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
5. Let SolderPy Loader deliver pack-specific content that native manifests
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

1. Put the SolderPy Loader release JAR in the instance's top-level `mods/`
   directory.
2. Put the matching Relauncher 1.1.1 JAR beside it.
3. Create `config/solderpy-loader.json` using the example below.
4. Start the client or dedicated server normally.

Use `relauncher-universal-1.1.1.jar` for manual and Modrinth installations.
For CurseForge, use `relauncher-universal-1.1.1-curseforge.jar`. The
CurseForge edition intentionally omits bundled native libraries and uses
Relauncher's pure-Java fallback.

SolderPy Loader does not bundle Relauncher or its native libraries. Its own
runtime dependencies are shaded and relocated inside the release JAR.

## Configuration

Create `config/solderpy-loader.json` inside the Minecraft instance:

```json
{
  "enabled": true,
  "api": "https://solder.example.com/api/",
  "modpack": "example-pack",
  "build": "recommended",
  "target": "auto",
  "clientId": null,
  "bootstrapJava": null,
  "bootstrapJavaMajor": 25,
  "failOpen": false,
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
| `clientId` | `null` | Non-secret Solder client UUID (`cid`) for a private pack |
| `bootstrapJava` | `null` | Java home or executable used only by the bootstrap worker |
| `bootstrapJavaMajor` | `25` | Preferred automatically detected worker Java major |
| `failOpen` | `false` | Allows launch after an update failure only when the previous installation is complete and hash-verified |
| `limits` | Shown above | Resource ceilings and download/extraction concurrency |

Solder API keys are deliberately not accepted in the local configuration.
Plain HTTP is rejected except for loopback development servers.

The resource ceilings may be lowered but not raised beyond the built-in
defaults. Download and extraction concurrency can be set from 1 to 16. Four
downloads and one extraction at a time are conservative defaults for slower
SSDs; faster storage can use a manual override.

Do not add a `selections` field. Optional definitions, rules, and defaults are
authoritative API data, and the legacy local field is rejected.

## Optional content

On the first graphical client launch, SolderPy Loader displays the optionals
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

After a successful install, selected memberships are stored in
`.solderpy-loader/state.json`. An unchanged manifest reuses them without
opening the screen again. When the manifest changes, existing options retain
their saved choices and new options start from the current API defaults.
Required memberships and dependency closure are always enforced.

Dedicated servers do not open a GUI. They request the server manifest, use the
API defaults, and install only `SERVER` and `BOTH` packages. A headless
client reuses compatible saved choices where possible and otherwise uses API
defaults.

## Download and installation behavior

The bootstrap is split into distinct phases so network, verification, and disk
work remain visible:

1. Resolve the manifest and optional memberships.
2. Download up to four changed packages concurrently by default. The progress
   window shows the active downloads and live throughput.
3. Verify completed artifacts against solder.py's stored MD5 and size.
4. Extract or stage packages, one at a time by default.
5. Commit the complete update transaction.
6. Start normal mod discovery.

When the API supplies ordered platform sources, the loader tries them in order.
For example, it can try a native Modrinth file first and fall back to the
canonical Solder URL if the download or verification fails.

Verified raw JARs are installed directly for `MOD` packages. Multi-file
`CONFIG`, `RES`, and `NONE` packages continue to use ZIP archives. Legacy
`MOD` packages without an available raw JAR also use their Solder ZIP.

If 7-Zip is installed, the loader can use its multithreaded extraction for
normal archives. Archives containing thousands of small files use the built-in
single-pass extractor because it avoids costly per-file process work. On
Windows, normal 7-Zip installation directories are checked before `PATH`; on
other systems, `7zz`, `7z`, and `7za` are checked. Set `SOLDERPY_7ZIP`
or the `solderpy.loader.7zip` JVM property to select an executable explicitly.
7-Zip is optional and is never bundled.

Every archive is inspected before extraction and audited afterward. Path
traversal, symbolic links, oversized artifacts, excessive expanded data, and
excessive entry counts are rejected.

## State, updates, and reset

Runtime state and file ownership receipts are stored under
`.solderpy-loader/`. The loader uses an HTTP `304 Not Modified` response or
an identical build and manifest hash to reuse the cached manifest and optional
choices.

Each installed package receipt records its version, artifact MD5, install
location, and installed file hashes. Files that still match are left in place;
missing or locally modified managed files are downloaded again. The loader
does not wipe and reinstall the whole pack on every launch.

Updates are staged away from the live instance and committed only after all
required work succeeds. A failed commit restores the previous files and state.
With `failOpen: false`, any update failure stops the launch. With
`failOpen: true`, startup may continue only if the complete previous
installation can be verified.

To make SolderPy Loader forget the saved build and optional selections, delete:

```text
.solderpy-loader/state.json
```

Keep `config/solderpy-loader.json`; deleting the state file makes the next
launch behave like a fresh reconciliation.

## Java runtimes

There are two separate Java choices:

- **Game JVM:** Relauncher starts Minecraft. SolderPy Loader declares support
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
