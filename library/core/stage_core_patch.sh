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
# NextClave (NC_AWG) hotfix for amneziawg-go v3.1.20260828: transport packets
# are sent without the s4 junk prefix whenever a pooled outbound element
# carries a stale padding (pre-IpcSet pool warm-up), so any AmneziaWG profile
# with S1-S4 configured connects only intermittently and the core logs
# "Received message with unknown type". Vendored fix, see step 7 below.
PATCH_FILE6="core-awg-gopool.patch"
if [ ! -f "$PATCH_FILE6" ]; then
  echo "ERROR: $PATCH_FILE6 not found" >&2
  exit 1
fi
# NextClave (NC_AWG) dbg26e: TEMPORARY instrumentation of the vendored
# amneziawg-go for the "import ok, connection dead" investigation (fix-26d
# did NOT clear the bug: handshake initiation goes out and the server
# answers, but every inbound datagram dies as "Received message with unknown
# type" and no keypair is ever created). The patch logs, rate-limited to a
# per-device budget, on both directions: RX ingress length/nonce/head hex,
# every DeterminePacketTypeAndPadding scan attempt (offset, size gate,
# deobfuscated type bytes, hit), the UNKNOWN verdict with the full s/h/rt
# state, TX handshake/transport dumps (plain + obfuscated heads, HP nonce,
# padding), plus one cfg dump line (s1-s4/h1-h4/jc/jmin/jmax/rt/hpKey).
# Every line is prefixed "NC_AWG dbg26e". DIAGNOSTIC ONLY: it prints the
# header_protection_key into the log (use a throwaway profile, do not
# publish the log) and must be reverted after the root cause is fixed.
PATCH_FILE7="core-awg-26e-debug.patch"
if [ ! -f "$PATCH_FILE7" ]; then
  echo "ERROR: $PATCH_FILE7 not found" >&2
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
grep -qF 'SetWarpLocalAddresses' core-patched/proxy/masque/internal/connectip/conn.go

# 7. NextClave (NC_AWG) hotfix: vendor a one-file fix for amneziawg-go
#    v3.1.20260828. Upstream reuses pooled outbound elements whose transport
#    padding was captured BEFORE the UAPI set applied s1-s4 (the element pool
#    is pre-warmed at device creation), so with any S1-S4 set the transport
#    packets are sent without the s4 junk prefix and the remote drops them
#    ("Received message with unknown type" in the core log) — provider
#    profiles connect only intermittently. core-awg-gopool.patch re-aligns
#    the padding in RoutineEncryption and relocates the packet to the correct
#    buffer offset; the patched copy is vendored under third_party/ and wired
#    through a go.mod replace directive (upstream has no release with the
#    fix as of 2026-11-16).
AWG_MOD="github.com/amnezia-vpn/amneziawg-go/v3"
AWG_VER="v3.1.20260828"
AWG_DIR="core-patched/third_party/amneziawg-go-v3"
go mod download "${AWG_MOD}@${AWG_VER}"
AWG_SRC="$(go env GOMODCACHE)/${AWG_MOD}@${AWG_VER}"
rm -rf "${AWG_DIR}"
mkdir -p core-patched/third_party
cp -a "${AWG_SRC}" "${AWG_DIR}"
chmod -R u+w "${AWG_DIR}"
patch -d "${AWG_DIR}" -p1 --forward < "$PATCH_FILE6"
patch -d "${AWG_DIR}" -p1 --forward < "$PATCH_FILE7"
grep -qF '(NC_AWG) fix' "${AWG_DIR}/device/send.go"
# NextClave (NC_AWG) dbg26e: the debug instrumentation must be present.
grep -qF 'NC_AWG dbg26e' "${AWG_DIR}/device/receive.go"
grep -qF 'NC_AWG dbg26e' "${AWG_DIR}/device/send.go"
grep -qF "replace ${AWG_MOD}" go.mod.with-replace ||
	echo "replace ${AWG_MOD} => ./core-patched/third_party/amneziawg-go-v3" >> go.mod.with-replace

# 6. Restore go.mod with the replace directive.
mv -f go.mod.with-replace go.mod
trap - EXIT

echo ">> core-patched ready (disorder + byte-length finalmask + reality client version fix + AmneziaWG + WARP MASQUE + AWG dbg26e instrumentation)"
