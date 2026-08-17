#!/usr/bin/env bash
set -euo pipefail
umask 022

if [[ $# -ne 4 ]]; then
    echo "usage: $0 <version> <output-dir> <release-private-key-file> <release-public-key-file>" >&2
    exit 2
fi

version=${1#v}
output_dir=$2
private_key_file=$3
public_key_file=$4
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
agent_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
repo_root=$(CDPATH= cd -- "$agent_dir/.." && pwd)
go_bin=${GO_BIN:-go}
public_key=$(tr -d '\r\n ' < "$public_key_file")

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "release version must be major.minor.patch" >&2
    exit 2
fi
if [[ ! "$public_key" =~ ^[A-Za-z0-9_-]{43}$ ]]; then
    echo "release public key must be a raw Ed25519 public key in unpadded base64url" >&2
    exit 2
fi

mkdir -p "$output_dir"
output_dir=$(CDPATH= cd -- "$output_dir" && pwd)
if [[ -n "$(find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    echo "release output directory must be empty: $output_dir" >&2
    exit 2
fi
work_dir=$(mktemp -d)
trap 'rm -rf -- "$work_dir"' EXIT

targets=(
    linux/amd64
    linux/arm64
    darwin/amd64
    darwin/arm64
    windows/amd64
    windows/arm64
)

for target in "${targets[@]}"; do
    goos=${target%/*}
    goarch=${target#*/}
    bundle_name="silk-agent_${version}_${goos}_${goarch}"
    bundle_dir="$work_dir/$bundle_name"
    mkdir -p "$bundle_dir/adapters" "$bundle_dir/bridge_common" "$bundle_dir/cc_bridge" "$bundle_dir/codex_bridge"

    binary_name=silk-agent
    if [[ "$goos" == windows ]]; then
        binary_name=silk-agent.exe
    fi
    (
        cd "$agent_dir"
        CGO_ENABLED=0 GOOS=$goos GOARCH=$goarch "$go_bin" build \
            -trimpath \
            -ldflags "-s -w -X main.releasePublicKey=$public_key -X main.hostVersion=$version" \
            -o "$bundle_dir/$binary_name" .
    )

    cp "$agent_dir"/adapters/silk-*-adapter "$bundle_dir/adapters/"
    cp "$repo_root"/bridge_common/*.py "$bundle_dir/bridge_common/"
    cp "$repo_root"/cc_bridge/*.py "$repo_root"/cc_bridge/requirements.txt "$bundle_dir/cc_bridge/"
    cp "$repo_root"/codex_bridge/*.py "$repo_root"/codex_bridge/requirements.txt "$bundle_dir/codex_bridge/"
    find "$bundle_dir/cc_bridge" "$bundle_dir/codex_bridge" -maxdepth 1 -type f -name 'test_*.py' -delete
    cp "$agent_dir/README.md" "$bundle_dir/README.md"
    find "$bundle_dir" -type d -exec chmod 0755 {} +
    chmod 0755 "$bundle_dir/$binary_name" "$bundle_dir"/adapters/silk-*-adapter
    find "$bundle_dir" -type f ! -perm -0100 -exec chmod 0644 {} +
    if [[ -n "$(find "$bundle_dir" -type d -perm /0022 -print -quit)" ]]; then
        echo "release bundle contains a directory writable by group or other: $bundle_name" >&2
        exit 1
    fi

    tar -C "$work_dir" -czf "$output_dir/$bundle_name.tar.gz" "$bundle_name"
    chmod 0644 "$output_dir/$bundle_name.tar.gz"
    rm -rf -- "$bundle_dir"
done

runner="$work_dir/silk-agent-release-tool"
(
    cd "$agent_dir"
    "$go_bin" build -trimpath \
        -ldflags "-X main.releasePublicKey=$public_key -X main.hostVersion=$version" \
        -o "$runner" .
)
SILK_AGENT_HOME="$work_dir/release-tool-home" "$runner" release manifest \
    --version "$version" \
    --artifacts "$output_dir" \
    --private-key-file "$private_key_file" \
    --output "$output_dir/manifest.json" \
    --signature "$output_dir/manifest.sig"
SILK_AGENT_HOME="$work_dir/release-tool-home" "$runner" release verify \
    --manifest "$output_dir/manifest.json" \
    --signature "$output_dir/manifest.sig" \
    --artifacts "$output_dir"
chmod 0644 "$output_dir/manifest.json" "$output_dir/manifest.sig"
if [[ -n "$(find "$output_dir" -maxdepth 1 -type f -perm /0022 -print -quit)" ]]; then
    echo "release output contains a file writable by group or other" >&2
    exit 1
fi
