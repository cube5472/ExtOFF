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
# NextClave (NC_AWG) fix(26f): bounded padding sweep fallback after the
# exact-offset checks. AWG expects identical S1-S4 padding on both peers;
# when the peer's config drifts, the type word sits at the sender's padding
# offset instead of ours and every genuine inbound packet dies as "Received
# message with unknown type". The HP keystream is padding-independent (the
# word is always XORed with keystream bytes 0-3), so candidate offsets can
# be scanned safely; the padding found on verified packets is adopted for
# our own TX. Upstream behavior on matching configs is byte-identical
# (exact offset first). This revision of the patch is debug-free: the 26e
# instrumentation is retired (the weasel root cause was found in the core
# bind and is fixed by fix(26h); the 26e log also printed the header
# protection key, which must not ship).
PATCH_FILE8="core-awg-rxscan.patch"
if [ ! -f "$PATCH_FILE8" ]; then
  echo "ERROR: $PATCH_FILE8 not found" >&2
  exit 1
fi
# NextClave (NC_AWG) fix(26g): stop appending random trailers to HANDSHAKE
# packets (initiation / response / cookie reply) in the vendored
# amneziawg-go. RandomTrailers is a TWO-SIDED parameter - a receiver without
# the flag classifies handshake messages strictly by "Sx + fixed size" and
# silently drops every lengthened datagram. Exact-size handshakes are
# accepted by BOTH receiver kinds (rt=on receivers gate on size >= expected),
# so trailerLen=0 is universally interoperable and matches clients that do
# not implement handshake trailers (wgtunnel, amneziawg-go v3.0 line). NOTE:
# 26g was NOT the actual weasel blocker - the server answered both the
# trailer'd (312B) and the exact-size (160B) initiations - the real root
# cause is fix(26h) below; 26g stays as interoperability hardening. The UAPI
# flag itself is kept: on RX it only widens our own size gates, and
# transport "trailers" are applied inside the AEAD where the peer's length
# gate never sees them.
PATCH_FILE9="core-awg-26g-notrailers.patch"
if [ ! -f "$PATCH_FILE9" ]; then
  echo "ERROR: $PATCH_FILE9 not found" >&2
  exit 1
fi

# NextClave (NC_AWG) fix(26h): THE root cause of the weasel/AmneziaWG 3.1
# outage. proxy/wireguard/bind.go (netBindClient receive goroutine) zeroed
# bytes 1..3 of EVERY received datagram unconditionally. That strip is the
# Cloudflare WARP reserved-field undo and is correct ONLY when the reserved
# feature is configured; for AmneziaWG profiles the first 12 bytes of a
# packet are the header protection nonce (the leading S1-S4 crypto padding),
# so the strip destroyed the nonce of every inbound datagram: the device
# decoded garbage type words and dropped each genuine handshake response as
# "Received message with unknown type" (log signature: a nonce that looks
# like XX 00 00 00 ... - the real first byte with bytes 1..3 wiped). The
# server parsed our (send side already guarded) initiation and answered
# every attempt; the answer never decoded; the same profile completes a
# handshake through the pristine amneziawg-go v3.1 device against the same
# server, and throneproj/sing-box guards the identical strip with a
# "reserved in use" check on its ClientBind. The fix mirrors the send-side
# guard on the receive path (and skips the all-zero reserved like
# throneproj/sing-box does).
PATCH_FILE10="core-awg-26h-bindfix.patch"
if [ ! -f "$PATCH_FILE10" ]; then
  echo "ERROR: $PATCH_FILE10 not found" >&2
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
# NextClave (NC_AWG) fix(26h): the wireguard client bind reserved-strip
# guard - the root-cause fix for the AmneziaWG 3.1 handshake outage.
patch -d core-patched -p1 --forward < "$PATCH_FILE10"

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
# NextClave (NC_AWG) fix(26h): the bind reserved-strip guard must be present.
grep -qF 'fix(26h)' core-patched/proxy/wireguard/bind.go

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
grep -qF '(NC_AWG) fix' "${AWG_DIR}/device/send.go"
# NextClave (NC_AWG) fix(26f): the RX padding sweep must be present.
patch -d "${AWG_DIR}" -p1 --forward < "$PATCH_FILE8"
grep -qF 'ncRxScanMaxPadding' "${AWG_DIR}/device/receive.go"
grep -qF 'fix(26f)' "${AWG_DIR}/device/receive.go"
# NextClave (NC_AWG) fix(26g): handshake trailers must be gone.
patch -d "${AWG_DIR}" -p1 --forward < "$PATCH_FILE9"
grep -qF 'fix(26g)' "${AWG_DIR}/device/send.go"
if grep -qF 'trailerLen := max(peer.randomTrailer(padding+MessageInitiationSize)' "${AWG_DIR}/device/send.go"; then
  echo "ERROR: handshake trailer still present after fix(26g)" >&2
  exit 1
fi
grep -qF "replace ${AWG_MOD}" go.mod.with-replace ||
	echo "replace ${AWG_MOD} => ./core-patched/third_party/amneziawg-go-v3" >> go.mod.with-replace

# 6. Restore go.mod with the replace directive.
mv -f go.mod.with-replace go.mod
trap - EXIT

echo ">> core-patched ready (disorder + byte-length finalmask + reality client version fix + AmneziaWG + WARP MASQUE + AWG 26f rx padding scan + AWG 26g handshake trailers off + AWG 26h bind reserved-strip guard)"
