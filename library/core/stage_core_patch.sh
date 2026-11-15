#!/usr/bin/env bash
# Stage a locally patched copy of the pinned exclave-core module and point
# go.mod's replace directive at it. Required for:
#  - the NoDPI "disorder" feature: core-disorder.patch adds disorder/TTL
#    support to the TLS fragmentation dialer;
#  - hotfix-12: core-finalmask.patch adds byte-length ClientHello
#    fragmentation (the Xray finalmask TCP "fragment" mask, fm=) — per-node
#    lengths/delays are parsed from sockopt.tlsFragmentation and applied in
#    tlsFragmentConn instead of the fixed SNI-label splits.
# Run before `gomobile bind` (CI: ./run lib core).
set -euo pipefail
cd "$(dirname "$0")"

PATCH_FILE="core-disorder.patch"
if [ ! -f "$PATCH_FILE" ]; then
  echo "ERROR: $PATCH_FILE not found" >&2
  exit 1
fi
PATCH_FILE2="core-finalmask.patch"
if [ ! -f "$PATCH_FILE2" ]; then
  echo "ERROR: $PATCH_FILE2 not found" >&2
  exit 1
fi
# hotfix-14: the REALITY client reported a frozen Xray version (25.5.16,
# May 2025) in the encrypted session_id; up-to-date panel servers reject
# such clients with a min-version check and every REALITY handshake fell
# back to the camouflage site ("REALITY: processed invalid connection",
# 100% dead nodes while NekoBox/Xray worked). This patch tracks the client
# version marker to the current Xray release (26.10.31).
PATCH_FILE3="core-realityver.patch"
if [ ! -f "$PATCH_FILE3" ]; then
  echo "ERROR: $PATCH_FILE3 not found" >&2
  exit 1
fi
# NextClave (NC_AWG): AmneziaWG outbound support. core-awg.patch switches
# proxy/wireguard from golang.zx2c4.com/wireguard to amneziawg-go v3
# (github.com/amnezia-vpn/amneziawg-go/v3) and plumbs obfuscation parameters
# (jc/jmin/jmax/s1-s4/h1-h4/i1-i5, header_protection_key, ...  protocol 3.1)
# from the JSON config ("awgParams") through the wireguard ClientConfig proto
# into the device IPC string. The app side (WireGuardBean/WireGuardFmt, .conf
# and mihomo amnezia-wg-option import) serializes the same keys; see the
# NC_AWG markers in the app sources. All AWG edits carry NC_AWG comments so
# they survive upstream core pin bumps (re-apply by regenerating this patch
# against the new pristine tree; every hunk targets code upstream never
# touches except config.pb.go, which is regenerated with protoc).
PATCH_FILE4="core-awg.patch"
if [ ! -f "$PATCH_FILE4" ]; then
  echo "ERROR: $PATCH_FILE4 not found" >&2
  exit 1
fi
# NextClave (NC_MASQUE): Cloudflare WARP MASQUE dialect for the MASQUE
# outbound. The core's masque client speaks only RFC 9298 ("connect-ip",
# extended-CONNECT settings handshake), while Cloudflare WARP — the target of
# the mihomo "type: masque" configs imported by the app (see the NC_MASQUE
# markers in the app sources) — expects :protocol "cf-connect-ip", no
# extended-CONNECT negotiation, the legacy draft H3_DATAGRAM setting (0x276),
# disabled compression and an empty User-Agent. The dialect is selected by the
# request URI host (usque/mihomo dial https://cloudflareaccess.com), which the
# importer sets; every edit carries an NC_MASQUE comment so it survives core
# pin bumps (regenerate core-masque.patch against the post-core-awg.patch
# tree: it applies AFTER all other patches, in stage order 1..5).
PATCH_FILE5="core-masque.patch"
if [ ! -f "$PATCH_FILE5" ]; then
  echo "ERROR: $PATCH_FILE5 not found" >&2
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

# 4. Apply the patches. Any failure must abort the build.
patch -d core-patched -p1 --forward < "$PATCH_FILE"
patch -d core-patched -p1 --forward < "$PATCH_FILE2"
patch -d core-patched -p1 --forward < "$PATCH_FILE3"
patch -d core-patched -p1 --forward < "$PATCH_FILE4"
patch -d core-patched -p1 --forward < "$PATCH_FILE5"

# 5. Sanity check: the patched tree must contain the new features.
#    -F (fixed strings) is REQUIRED here: the signature contains "[]int32",
#    and a regex grep parses "[" as the start of a bracket expression with
#    no closing "]" -> "grep: Unmatched [" (exit 2) on every CI run
#    (hotfix-12 first run, 2026-11-05).
grep -qF 'func NewTLSFragmentConn(ctx context.Context, conn net.Conn, splitRecord, splitPacket, disorder bool, ttl int, lengths, delays []int32)' \
  core-patched/transport/internet/tlsfragment/tlsfragment.go
grep -qF 'instanceCache' core-patched/common/serial/typed_message.go
grep -qF 'writeByLengths' core-patched/transport/internet/tlsfragment/tlsfragment.go
grep -qF 'parseRangeSpecs' core-patched/infra/conf/cfgcommon/socketcfg/socket.go
grep -qF 'var realityClientVersion = [3]byte{26, 10, 31}' core-patched/transport/internet/reality/uclient.go
grep -qF 'ClientVersion: realityClientVersion' core-patched/transport/internet/reality/client.go
# NextClave (NC_AWG): the AmneziaWG swap must be present after patching.
grep -qF 'github.com/amnezia-vpn/amneziawg-go/v3/conn' core-patched/proxy/wireguard/device.go
grep -qF 'awg_params = 8' core-patched/proxy/wireguard/config.proto
grep -qF 'parseAWGParams' core-patched/infra/conf/v4/wireguard.go
# NextClave (NC_MASQUE): the WARP dialect must be present after patching.
grep -qF 'cf-connect-ip' core-patched/proxy/masque/internal/connectip/request.go
grep -qF 'cloudflareaccess.com' core-patched/proxy/masque/internal/connectip/request.go
grep -qF 'IsWarpRequestURL' core-patched/proxy/masque/client.go

# 6. Restore go.mod with the replace directive.
mv -f go.mod.with-replace go.mod
trap - EXIT

echo ">> core-patched ready (disorder + byte-length finalmask + reality client version fix + AmneziaWG + WARP MASQUE)"
