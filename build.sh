#!/usr/bin/env bash
# Build the MT6739 kernel either as a stock baseline or as the Droidspaces target.
#   bash build.sh stock    -> prove the tree reproduces the stock CRCs
#   bash build.sh target   -> minimal namespace delta + layout-preserving patch
set -euo pipefail

MODE="${1:?usage: build.sh stock|target}"
WS="$PWD"
SRC_REPO="${SRC_REPO:-https://github.com/paperluigis/android-huawei-dura-kernel}"
SRC_REF="${SRC_REF:-og}"

step() { echo; echo "########## $*"; }
die()  { echo "FATAL: $*" >&2; exit 1; }

step "MODE=$MODE"

# ---------------------------------------------------------------- 1) kernel source
step "1) kernel source ($SRC_REF)"
rm -rf kernel
git clone --depth 1 -b "$SRC_REF" "$SRC_REPO" kernel || die "clone kernel source failed"
grep -E '^(VERSION|PATCHLEVEL|SUBLEVEL)\s*=' kernel/Makefile

# ---------------------------------------------------------------- 2) toolchain
# The stock kernel was built with "Linaro GCC 6.3-2017.02". releases.linaro.org no
# longer serves that path (it returns an HTML page with HTTP 200), so we take the
# same compiler from a public prebuilt tree and keep a snapshot mirror as fallback.
step "2) toolchain (Linaro GCC 6.3-2017.02)"
TC_DIR=""
try_tc() { # $1 = bin dir
  [ -x "$1/aarch64-linux-gnu-gcc" ] || return 1
  export PATH="$1:$PATH"
  export CROSS_COMPILE=aarch64-linux-gnu-
  "$1/aarch64-linux-gnu-gcc" --version | head -1
  TC_DIR="$1"
}

rm -rf toolrepo
if git clone --depth 1 --filter=blob:none --sparse \
     "${TOOLREPO:-https://github.com/jmpfbmx/android_kernel_mt6739.git}" toolrepo >/dev/null 2>&1; then
  ( cd toolrepo && git sparse-checkout set prebuilts/gcc/linux-x86/aarch64/aarch64-linux-gnu-6.3.1 ) >/dev/null 2>&1 || true
  try_tc "$WS/toolrepo/prebuilts/gcc/linux-x86/aarch64/aarch64-linux-gnu-6.3.1/bin" || TC_DIR=""
fi

if [ -z "$TC_DIR" ]; then
  step "2b) fallback: snapshots.linaro.org"
  URL="https://snapshots.linaro.org/gnu-toolchain/6.3-2017.02/aarch64-linux-gnu/gcc-linaro-6.3.1-2017.02-x86_64_aarch64-linux-gnu.tar.xz"
  mkdir -p tc
  curl -fsSL --retry 3 "$URL" -o tc.tar.xz || die "toolchain download failed"
  tar -xJf tc.tar.xz -C tc --strip-components=1 || die "toolchain extract failed"
  try_tc "$WS/tc/bin" || die "no usable aarch64-linux-gnu-gcc"
fi

export ARCH=arm64
command -v aarch64-linux-gnu-gcc || die "cross compiler not on PATH"
echo "toolchain: $(readlink -f "$(command -v aarch64-linux-gnu-gcc)")"

# ---------------------------------------------------------------- 3) config
step "3) config"
cd kernel
echo "+" > .scmversion          # force vermagic "4.4.95+" to match the stock modules
cp "$WS/configs/stock.config" .config
[ -s .config ] || die "stock.config missing/empty"

if [ "$MODE" = "target" ]; then
  scripts/config --file .config \
    --enable SYSVIPC --enable SYSVIPC_SYSCTL \
    --enable NAMESPACES --enable UTS_NS --enable IPC_NS --enable PID_NS
  echo "--- relocating SYSVIPC members to the tail of struct task_struct"
  python3 "$WS/tools/move_sysvipc.py" include/linux/sched.h || die "layout patch failed"
else
  echo "--- baseline: stock config left untouched"
fi

step "4) layout probes"
python3 "$WS/tools/add_offsets.py" arch/arm64/kernel/asm-offsets.c || die "probe patch failed"

step "5) olddefconfig"
make -j"$(nproc)" olddefconfig 2>&1 | tail -5
echo "--- options in effect:"
grep -E '^CONFIG_(NAMESPACES|UTS_NS|PID_NS|IPC_NS|SYSVIPC|SYSVIPC_SYSCTL|NET_NS|USER_NS|MODVERSIONS)=' .config || true

step "6) build Image.gz"
set +e
make -j"$(nproc)" Image.gz 2>&1 | tail -40
rc=${PIPESTATUS[0]}
set -e
[ "$rc" -eq 0 ] || die "kernel build failed (rc=$rc)"

step "7) results"
echo "--- kernel.release: $(cat include/config/kernel.release)"
mkdir -p "$WS/artifacts"
cp arch/arm64/boot/Image.gz        "$WS/artifacts/Image.gz.$MODE"
cp Module.symvers                  "$WS/artifacts/Module.symvers.$MODE"
cp .config                         "$WS/artifacts/config.$MODE"
cp include/generated/asm-offsets.h "$WS/artifacts/asm-offsets.$MODE.h"
cp include/config/kernel.release   "$WS/artifacts/kernel.release.$MODE"

step "8) CRC gate vs stock vendor modules"
python3 "$WS/tools/check_crcs.py" Module.symvers "$WS/configs/stock-modcrcs.txt" \
  | tee "$WS/artifacts/crc-gate.$MODE.txt" || true

step "9) layout fingerprint"
grep -E '^#define DS_' include/generated/asm-offsets.h | tee "$WS/artifacts/offsets.$MODE.txt"

ls -l "$WS/artifacts"
echo; echo "DONE $MODE"
