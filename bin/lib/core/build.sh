#!/usr/bin/env bash

source "bin/init/env.sh"

# NextClave: the core is ALWAYS built from source on CI. Two go.mod replace
# directives shape the build before gomobile bind:
#   1. github.com/exclavenetwork/libexclavecore => ./patched/libexclavecore
#      (UrlTest fix: two warm keep-alive attempts, report the minimum -
#      v2rayNG method; the pristine single-shot HEAD inflates readings 3-5x)
#   2. github.com/exclavenetwork/exclave-core/v5 => ./core-patched
#      (staged by library/core/stage_core_patch.sh BEFORE this script:
#      pinned exclave-core + NoDPI disorder patch)
# The committed prebuilt AAR of the v15/v16 era was removed on purpose:
# an AAR older than the patches silently ships a core without the UrlTest
# fix or without NoDPI disorder. The guards below fail the build instead.

rm -rf library/core/build
cd library/core
./build.sh || exit 1

# Post-build guards: both patch markers must be inside the produced
# libgojni.so, otherwise abort instead of shipping a silently broken core.
# gomobile stores natives as jni/<abi>/libgojni.so - match any layout/abi.
if ! unzip -p libexclavecore.aar '*libgojni.so' | grep -aq "invalid status: %s"; then
  echo ">> FAIL: patched UrlTest NOT found in libgojni.so - the core was" >&2
  echo ">> built from the pristine wrapper (replace lost in go.mod?). Abort." >&2
  unzip -l libexclavecore.aar | grep libgojni >&2 || true
  exit 1
fi
if ! unzip -p libexclavecore.aar '*libgojni.so' | grep -aq "setSocketTTL"; then
  echo ">> FAIL: NoDPI disorder (setSocketTTL) NOT found in libgojni.so -" >&2
  echo ">> core-disorder.patch was not applied (stage_core_patch.sh). Abort." >&2
  unzip -l libexclavecore.aar | grep libgojni >&2 || true
  exit 1
fi
echo ">> OK: UrlTest patch + NoDPI disorder verified inside libgojni.so"

mkdir -p "$PROJECT/app/libs"
cp -f libexclavecore.aar "$PROJECT/app/libs"
