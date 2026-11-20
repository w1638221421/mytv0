#!/usr/bin/env bash
# Build the MT6739 kernel either as a stock baseline or as the Droidspaces target.
#   bash build.sh stock    -> prove the tree reproduces the stock CRCs
#   bash build.sh target   -> minimal namespace delta + layout-preserving patch
set -euo pipefail

MODE="${1:?usage: build.sh stock|target}"
WS="$PWD"
SRC_REPO="${SRC_REPO:-https://github.com/paperluigis/android-huawei-dura-kernel}"
SRC_REF="${SRC_REF:-og}"
LINARO="${LINARO:-https://releases.linaro.org/components/toolchain/binaries/6.3-2017.02/aarch64-linux-gnu/gcc-linaro-6.3.1-2017.02-x86_64_aarch64-linux-gnu.tar.xz}"

echo "########## MODE=$MODE"

echo "===== 1) source tree ($SRC_REF)"
git clone --depth 1 -b "$SRC_REF" "$SRC_REPO" kernel
grep -E '^(VERSION|PATCHLEVEL|SUBLEVEL)\s*=' kernel/Makefile

echo "===== 2) toolchain: Linaro GCC 6.3.1-2017.02 (identical to the stock build)"
mkdir -p tc
curl -fsSL "$LINARO" | tar -xJ -C tc --strip-components=1
export PATH="$WS/tc/bin:$PATH"
export ARCH=arm64
export CROSS_COMPILE=aarch64-linux-gnu-
aarch64-linux-gnu-gcc --version | head -1

cd kernel
# force the '+' localversion so vermagic matches the stock modules exactly
echo "+" > .scmversion

echo "===== 3) config"
cp "$WS/configs/stock.config" .config
if [ "$MODE" = "target" ]; then
  scripts/config --file .config \
    --enable SYSVIPC --enable SYSVIPC_SYSCTL \
    --enable NAMESPACES --enable UTS_NS --enable IPC_NS --enable PID_NS
  echo "--- relocating SYSVIPC members to the tail of task_struct"
  python3 "$WS/tools/move_sysvipc.py" include/linux/sched.h
else
  echo "--- baseline: stock config left untouched"
fi

echo "===== 4) layout probes"
python3 "$WS/tools/add_offsets.py" arch/arm64/kernel/asm-offsets.c

echo "===== 5) olddefconfig"
make -j"$(nproc)" olddefconfig
echo "--- namespace / ipc options in effect:"
grep -E '^CONFIG_(NAMESPACES|UTS_NS|PID_NS|IPC_NS|SYSVIPC|SYSVIPC_SYSCTL|NET_NS|USER_NS|MODVERSIONS)=' .config || true

echo "===== 6) build"
make -j"$(nproc)" Image.gz 2>&1 | tail -30

echo "===== 7) results"
echo "--- kernel.release: $(cat include/config/kernel.release)"
mkdir -p "$WS/artifacts"
cp arch/arm64/boot/Image.gz            "$WS/artifacts/Image.gz.$MODE"
cp Module.symvers                      "$WS/artifacts/Module.symvers.$MODE"
cp .config                             "$WS/artifacts/config.$MODE"
cp include/generated/asm-offsets.h     "$WS/artifacts/asm-offsets.$MODE.h"
cp include/config/kernel.release       "$WS/artifacts/kernel.release.$MODE"

echo "===== 8) CRC gate vs stock vendor modules"
python3 "$WS/tools/check_crcs.py" Module.symvers "$WS/configs/stock-modcrcs.txt" \
  | tee "$WS/artifacts/crc-gate.$MODE.txt" || true

echo "===== 9) layout fingerprint"
grep -E '^#define DS_' include/generated/asm-offsets.h | tee "$WS/artifacts/offsets.$MODE.txt"

ls -l "$WS/artifacts"
