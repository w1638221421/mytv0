#!/bin/bash
# buildone.sh <src_dir> <out_name> <stock|route3>
set -e
SRC="$1"; OUT="$2"; MODE="$3"
cd "$SRC"
export ARCH=arm64
export CROSS_COMPILE="$GITHUB_WORKSPACE/gcc49/bin/aarch64-linux-android-"
echo "+" > .scmversion
cp "$GITHUB_WORKSPACE/configs/stock.config" .config

D=drivers/misc/mediatek/imgsensor/src/common/v1
L=drivers/misc/mediatek/lcm
S=$(grep -m1 '^CONFIG_CUSTOM_KERNEL_IMGSENSOR=' .config | cut -d'"' -f2)
K=""; for x in $S; do [ -d "$D/$x" ] && K="$K $x"; done
scripts/config --file .config --set-str CUSTOM_KERNEL_IMGSENSOR "$(echo $K)"
C=$(grep -m1 '^CONFIG_CUSTOM_KERNEL_LCM=' .config | cut -d'"' -f2)
K2=""; for x in $C; do [ -d "$L/$x" ] && K2="$K2 $x"; done
scripts/config --file .config --set-str CUSTOM_KERNEL_LCM "$(echo $K2)"

sed -i 's/^\([[:space:]]*\)select CMA_DEBUG$/\1# select CMA_DEBUG  # off to match stock/' mm/Kconfig
scripts/config --file .config --disable CMA_DEBUG --disable CMA_DEBUGFS

if [ "$MODE" = "route3" ]; then
  scripts/config --file .config \
    --enable NAMESPACES --enable UTS_NS --enable PID_NS --enable IPC_NS \
    --disable POSIX_MQUEUE --disable SYSVIPC --disable SYSVIPC_SYSCTL \
    --disable NET_NS --disable USER_NS --disable MEMCG
fi

make ARCH=arm64 olddefconfig
scripts/config --file .config --disable CMA_DEBUG --disable CMA_DEBUGFS
make ARCH=arm64 olddefconfig
sed -i 's/^CONFIG_CMA_DEBUG=y/# CONFIG_CMA_DEBUG is not set/' .config || true
sed -i 's/^CONFIG_CMA_DEBUGFS=y/# CONFIG_CMA_DEBUGFS is not set/' .config || true

sed -i 's/const_debug unsigned int sysctl_sched_migration_cost/unsigned int sysctl_sched_migration_cost/g' kernel/sched/fair.c
sed -i 's/extern const_debug unsigned int sysctl_sched_migration_cost/extern unsigned int sysctl_sched_migration_cost/g' kernel/sched/sched.h
sed -i 's/^\([[:space:]]*\)sysctl_sched_migration_cost = /\1*(unsigned int *)\&sysctl_sched_migration_cost = /' drivers/misc/mediatek/sched/sched_ctl.c
sed -i 's/-Wdeclaration-after-statement/-Wno-declaration-after-statement/g' Makefile
grep -q 'Wno-error=return-type' Makefile || sed -i 's/^KBUILD_CFLAGS :=/KBUILD_CFLAGS := -Wno-error=return-type -Wno-error=incompatible-pointer-types /' Makefile
for f in scripts/dtc/dtc-lexer.l scripts/dtc/dtc-lexer.lex.c scripts/dtc/dtc-parser.y scripts/dtc/dtc-parser.tab.c; do
  [ -f "$f" ] && sed -i 's/^YYLTYPE yylloc;/extern YYLTYPE yylloc;/' "$f" || true
done
python3 "$GITHUB_WORKSPACE/tools/fix_source_includes.py" .

make ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" HOSTCFLAGS="-fcommon" -j"$(nproc)" Image.gz > "$GITHUB_WORKSPACE/$OUT.log" 2>&1

mkdir -p "$GITHUB_WORKSPACE/artifacts/$OUT"
cp arch/arm64/boot/Image.gz "$GITHUB_WORKSPACE/artifacts/$OUT/Image.gz"
cp Module.symvers "$GITHUB_WORKSPACE/artifacts/$OUT/Module.symvers"
cp .config "$GITHUB_WORKSPACE/artifacts/$OUT/config.txt"
cp include/config/kernel.release "$GITHUB_WORKSPACE/artifacts/$OUT/kernel.release"
ls ipc/*.o 2>/dev/null > "$GITHUB_WORKSPACE/artifacts/$OUT/ipc-objects.txt" || true
(cd arch/arm64/boot && sha256sum Image.gz) > "$GITHUB_WORKSPACE/artifacts/$OUT/Image.gz.sha256"
cp "$GITHUB_WORKSPACE/$OUT.log" "$GITHUB_WORKSPACE/artifacts/$OUT/build.log"
echo "$OUT done"
