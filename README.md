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
single/multiple groups, dependency closure, ETag revalidation, MD5/size
verification, direct raw-JAR installation, safe ZIP extraction for non-mod
content, file ownership receipts, and transactional rollback. The API controls
the available choices, rules, and defaults; the local JSON configuration cannot
override them.

## Requirements

- Java 8 or newer at runtime (the build itself uses a Java 17+ toolchain).
- `relauncher-universal-1.1.1.jar` and the built loader JAR in the top-level
  `mods/` directory.
- A solder.py server exposing bootstrap schema version 1.

Relauncher supports Forge 1.6.4+, NeoForge, Fabric, and Quilt. Its upstream
launcher matrix currently supports the vanilla, Prism, CurseForge, Modrinth,
and ATLauncher launch paths. Technic Launcher V3 is **expected compatible,
but not yet verified**. MultiMC's stdin launch protocol cannot be replayed and
is not supported by Relauncher.

## Configuration

Create `config/solderpy-loader.json` in the Minecraft instance:

```json
{
  "enabled": true,
  "api": "https://solder.example.com/api/",
  "modpack": "example-pack",
  "build": "recommended",
  "target": "auto",
  "clientId": null
}
```

`target` defaults to `auto`. Relauncher identifies whether Forge, NeoForge,
Fabric, or Quilt is starting a client or dedicated server and passes that side
to the loader before the bootstrap request. Set it explicitly to `client` or
`server` only to override detection. If Relauncher reports an unknown side, the
loader stops with an actionable error instead of installing the wrong files.

`clientId` is the non-secret Solder client UUID (`cid`) used for a private
pack. Solder API keys are deliberately not accepted in the local config.
The selection screen starts from the API manifest's
`selection_policy.default_memberships`. The loader combines the user's choices
with `required_memberships`, closes required dependencies, and validates
advanced group limits. This makes the API authoritative for both basic and
advanced optionals. A legacy `selections` field in this file is rejected so a
stale local preference cannot silently override the server.

## Optional selection

On graphical client launches, the loader displays a Swing screen before mod
discovery. Basic optionals and ungrouped advanced optionals use checkboxes;
advanced single-choice groups use radio buttons, and multiple-choice groups
use checkboxes with their API-provided limits. Package and group descriptions
also come from the manifest.

The screen is initialized from the API defaults on every launch. Closing it or
selecting **Cancel Launch** aborts startup. Dedicated servers request the
server manifest, install only `SERVER` and `BOTH` packages, skip the screen,
and use the API defaults. Graphical headless clients also skip the screen and
use those defaults.

Plain HTTP is rejected except for loopback development servers. Downloads are
limited to 512 MiB per artifact and ZIPs to 2 GiB expanded / 100,000 entries
by default. The optional `limits` object can lower those values:

```json
{
  "limits": {
    "maxDownloadBytes": 536870912,
    "maxExpandedBytes": 2147483648,
    "maxArchiveEntries": 100000
  }
}
```

Runtime cache and ownership receipts live under `.solderpy-loader/`. The
cached manifest is reused only when the API returns `304 Not Modified`; it is
not a separate selection configuration. A failed install restores the
previous files and state before Minecraft continues. By default, an update
failure aborts startup; set `failOpen` to `true` only if starting the previously
installed pack is preferable to enforcing updates.

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
./gradlew clean test build
```

The distributable, dependency-contained JAR is written to `build/libs/`.
Relauncher Core is compile-only because the universal Relauncher JAR provides
its SPI at runtime.

## Launch sequence

1. Relauncher scans the top-level `mods/` directory and finds this project's
   `CommandLineProvider` service.
2. The provider adds `-javaagent:<loader jar>` and Relauncher performs its one
   controlled JVM restart.
3. `SolderPyAgent.premain` fetches and validates the authoritative bootstrap
   manifest, presents its optional choices, resolves dependencies, stages all
   files, and commits the update transaction.
4. The mod loader starts discovery and sees the reconciled `mods/` directory.

The bootstrap package itself should be represented in Solder as type `BOOTSTRAP`.
The API consequently reports it as `bootstrap_managed: false`, preventing the
loader from trying to replace its own active JAR.

## License

All rights reserved. See [LICENSE](LICENSE).
