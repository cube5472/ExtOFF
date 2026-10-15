#!/usr/bin/env bash

source "bin/init/env.sh"

# NextClave v15: the core ships PREBUILT - app/libs/libexclavecore.aar contains
# the byte-exact libgojni.so (md5 2180d4bb7ad752b166a6d670c7f84834) extracted
# from the known-good 0.17.56 build whose URL-test readings are correct.
# CI runners no longer compile the core: Go/NDK toolchain drift between runner
# image updates is the prime suspect for inflated URL-test readings (the same
# source pin built with go 1.27.1 vs 1.27.2 produced different binaries).
if [ -z "$NEXTCLAVE_FORCE_CORE_BUILD" ] && [ -f "$PROJECT/app/libs/libexclavecore.aar" ]; then
  echo ">> NextClave: app/libs/libexclavecore.aar is prebuilt and committed;"
  echo ">> skipping the gomobile core build. Set NEXTCLAVE_FORCE_CORE_BUILD=1 to override."
  exit 0
fi

rm -rf library/core/build
cd library/core
./build.sh || exit 1

mkdir -p "$PROJECT/app/libs"
cp -f libexclavecore.aar "$PROJECT/app/libs"
