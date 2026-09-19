#!/usr/bin/env bash

set -euo pipefail

artifact="${1:?usage: validate-release-artifact.sh <jar> [expected-version]}"
expected_version="${2:-}"

if [[ ! -f "$artifact" ]]; then
    echo "Expected artifact is missing: $artifact" >&2
    exit 1
fi

if (( $(wc -c < "$artifact") < 1000000 )); then
    echo "Artifact is unexpectedly small: $artifact" >&2
    exit 1
fi

artifact="$(realpath "$artifact")"
scratch="$(mktemp -d)"
listing="$scratch/listing.txt"
trap 'rm -rf -- "$scratch"' EXIT

jar tf "$artifact" | tr -d '\r' > "$listing"

require_entry() {
    local entry="$1"
    if ! grep -Fqx "$entry" "$listing"; then
        echo "Required entry '$entry' is missing from $artifact" >&2
        exit 1
    fi
}

require_entry 'io/github/thorfusion/solderpyloader/SolderPyAgent.class'
require_entry 'io/github/thorfusion/solderpyloader/SolderPyRelaunchProvider.class'
require_entry 'META-INF/services/com.juanmuscaria.relauncher.CommandLineProvider'
require_entry 'io/github/thorfusion/solderpyloader/internal/gson/Gson.class'
require_entry 'io/github/thorfusion/solderpyloader/internal/compress/archivers/zip/ZipFile.class'
require_entry 'io/github/thorfusion/solderpyloader/internal/io/IOUtils.class'
require_entry 'META-INF/LICENSE'
require_entry 'META-INF/LICENSE.txt'
require_entry 'META-INF/THIRD-PARTY-NOTICES.md'

if grep -Eq '^(com/google/gson|org/apache/commons/(compress|io))/' "$listing"; then
    echo 'Embedded dependencies must be relocated away from Minecraft libraries.' >&2
    exit 1
fi

if grep -Fqx 'com/juanmuscaria/relauncher/Relauncher.class' "$listing"; then
    echo 'Relauncher Core must remain compile-only and must not be bundled.' >&2
    exit 1
fi

(
    cd "$scratch"
    jar xf "$artifact" \
        META-INF/MANIFEST.MF \
        META-INF/services/com.juanmuscaria.relauncher.CommandLineProvider
)

if ! tr -d '\r' < "$scratch/META-INF/MANIFEST.MF" |
    grep -Fqx 'Premain-Class: io.github.thorfusion.solderpyloader.SolderPyAgent'; then
    echo 'The premain manifest entry is missing or incorrect.' >&2
    exit 1
fi

if [[ -n "$expected_version" ]] &&
    ! tr -d '\r' < "$scratch/META-INF/MANIFEST.MF" |
        grep -Fqx "Implementation-Version: $expected_version"; then
    echo "Manifest version does not match $expected_version." >&2
    exit 1
fi

if ! tr -d '\r' < "$scratch/META-INF/services/com.juanmuscaria.relauncher.CommandLineProvider" |
    grep -Fqx 'io.github.thorfusion.solderpyloader.SolderPyRelaunchProvider'; then
    echo 'The Relauncher service provider declaration is incorrect.' >&2
    exit 1
fi

bytecode="$scratch/bytecode.txt"
javap -verbose -classpath "$artifact" \
    io.github.thorfusion.solderpyloader.SolderPyAgent |
    tr -d '\r' > "$bytecode"

if ! grep -Eq 'major version: 52$' "$bytecode"; then
    echo 'The loader must target Java 8 bytecode (class major version 52).' >&2
    exit 1
fi

echo "Validated $artifact"
