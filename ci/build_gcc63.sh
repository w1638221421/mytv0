#!/bin/bash
# 用 GCC 6.3（Debian 9 交叉编译器）+ "确认能开机"的 LOS 内核 config 重编官方源码
# 关键：这次同步产出 Image.gz-dtb（内核自己 append 的 DTB），避免手工拼 DTB 出错
set -e

echo "=== 编译器信息 ==="
aarch64-linux-gnu-gcc --version | head -2 || true

ROOT="${ROOT:-/w}"
SRC="$ROOT/src"; WORK="$ROOT/work"; OUT="$ROOT/out"
mkdir -p "$SRC" "$WORK" "$OUT"

echo "=== 官方源码包 ==="
if [ ! -f "$SRC/dura.gz" ]; then
  URL="https://download-c1.huawei.com/download/downloadCenter?downloadId=5E5F1FF8BAE2D3B6C865452D95215F2F&version=E3B4C5DDCF2BB14EF20527C810B6E308&siteCode=worldwide"
  wget -q --tries=3 --timeout=300 -U "Mozilla/5.0" -O "$SRC/dura.gz" "$URL"
fi
ls -l "$SRC/dura.gz"

rm -rf "$WORK"; mkdir -p "$WORK"
tar -xzf "$SRC/dura.gz" -C "$WORK" --wildcards 'Code_Opensource/kernel-4.4/*' || true
K="$WORK/Code_Opensource/kernel-4.4"
cd "$K"

echo "=== 构建修补（只保留编过所必需的） ==="
sed -i 's/-Wdeclaration-after-statement/-Wno-declaration-after-statement/g' Makefile
grep -q 'Wno-error=return-type' Makefile || \
  sed -i 's/^KBUILD_CFLAGS :=/KBUILD_CFLAGS := -Wno-error=return-type -Wno-error=incompatible-pointer-types -Wno-error=misleading-indentation -Wno-error=format-overflow -Wno-error=format-truncation /' Makefile
for f in scripts/dtc/dtc-lexer.l scripts/dtc/dtc-lexer.lex.c scripts/dtc/dtc-parser.y scripts/dtc/dtc-parser.tab.c; do
  [ -f "$f" ] || continue
  sed -i 's/^YYLTYPE yylloc;/extern YYLTYPE yylloc;/' "$f" || true
done
if grep -q 'extern YYLTYPE yylloc' scripts/dtc/dtc-parser.tab.c 2>/dev/null; then
  sed -i '0,/extern YYLTYPE yylloc;/s//YYLTYPE yylloc;/' scripts/dtc/dtc-parser.tab.c || true
fi
grep -q 'HOSTCFLAGS.*-fcommon' Makefile || echo 'export HOSTCFLAGS += -fcommon' >> Makefile

# sched_ctl const 冲突（SCHED_DEBUG=n 时 const_debug=const）
f=drivers/misc/mediatek/sched/sched_ctl.c
if [ -f "$f" ]; then
  sed -i 's/sysctl_sched_migration_cost = /*(unsigned int *)\&sysctl_sched_migration_cost = /g' "$f"
fi
for f in kernel/sched/core.c kernel/sched/fair.c; do
  [ -f "$f" ] || continue
  sed -i 's/^const unsigned int sysctl_sched_migration_cost/unsigned int sysctl_sched_migration_cost/' "$f" || true
  sed -i 's/^static const unsigned int sysctl_sched_migration_cost/unsigned int sysctl_sched_migration_cost/' "$f" || true
done

echo "=== 用'确认能开机'的 config（来自 LOS 内核） ==="
mkdir -p out
cp "$ROOT/los_kernel_config.txt" out/.config
grep -E "^CONFIG_(HZ|HZ_300|FINAL_RELEASE|NAMESPACES|CGROUP_DEVICE|DEVTMPFS|POSIX_MQUEUE|OVERLAY_FS)=" out/.config || true

export ARCH=arm64
export CROSS_COMPILE=aarch64-linux-gnu-
export HOSTCFLAGS="-fcommon"

echo "=== 编译 ==="
make O=out ARCH=arm64 CROSS_COMPILE=$CROSS_COMPILE HOSTCFLAGS="-fcommon" -j"$(nproc)" Image
make O=out ARCH=arm64 CROSS_COMPILE=$CROSS_COMPILE HOSTCFLAGS="-fcommon" -j"$(nproc)" Image.gz-dtb || echo "[warn] Image.gz-dtb 失败"

echo "=== 产物 ==="
ls -l out/arch/arm64/boot/Image out/arch/arm64/boot/Image.gz-dtb 2>/dev/null || true
cp out/arch/arm64/boot/Image "$OUT"/
[ -f out/arch/arm64/boot/Image.gz-dtb ] && cp out/arch/arm64/boot/Image.gz-dtb "$OUT"/ || true
cp out/System.map "$OUT"/ 2>/dev/null || true
cp out/.config "$OUT"/config.txt
echo "=== 最终 config 关键项 ==="
grep -E "^CONFIG_(HZ|FINAL_RELEASE)=" "$OUT"/config.txt || true
