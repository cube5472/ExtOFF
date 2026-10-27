#!/usr/bin/env bash
# Stage a locally patched copy of the pinned exclave-core module and point
# go.mod's replace directive at it. Required for the NoDPI "disorder" feature:
# the patch in core-disorder.patch adds disorder/TTL support to the TLS
# fragmentation dialer. Run before `gomobile bind` (CI: ./run lib core).
set -euo pipefail
cd "$(dirname "$0")"

PATCH_FILE="core-disorder.patch"
if [ ! -f "$PATCH_FILE" ]; then
  echo "ERROR: $PATCH_FILE not found" >&2
  exit 1
fi

# 1. Temporarily strip the replace directive so `go mod download` fetches the
#    pinned upstream module into the module cache.
cp go.mod go.mod.with-replace
grep -v '^replace github.com/exclavenetwork/exclave-core' go.mod.with-replace > go.mod

cleanup() {
  # never leave the bare go.mod behind: restore the replace directive
  if [ -f go.mod.with-replace ]; then
    mv -f go.mod.with-replace go.mod
  fi
}
trap cleanup EXIT

# 2. Download the pinned module (checksums verified against go.sum).
go mod download github.com/exclavenetwork/exclave-core/v5

# 3. Copy it out of the (read-only) module cache into ./core-patched.
#    NOTE: go.mod is still the bare (replace-free) copy here, so `go list -m`
#    resolves the pinned upstream module inside the module cache.
SRC="$(go list -m -f '{{.Dir}}' github.com/exclavenetwork/exclave-core/v5)"
echo ">> staging exclave-core from ${SRC}"
rm -rf core-patched
mkdir -p core-patched
cp -a "${SRC}/." core-patched/
chmod -R u+w core-patched

# 4. Apply the disorder patch. Any failure must abort the build.
patch -d core-patched -p1 --forward < "$PATCH_FILE"

# 5. Sanity check: the patched tree must contain the new feature.
grep -q 'func NewTLSFragmentConn(ctx context.Context, conn net.Conn, splitRecord, splitPacket, disorder bool, ttl int)' \
  core-patched/transport/internet/tlsfragment/tlsfragment.go
grep -q 'instanceCache' core-patched/common/serial/typed_message.go

# 6. Restore go.mod with the replace directive.
mv -f go.mod.with-replace go.mod
trap - EXIT

echo ">> core-patched ready (disorder enabled)"
