#!/bin/bash
# 在 ubuntu:18.04 容器内执行：用 gcc 7.5 交叉编译器按出厂 config 编译 DURA 内核
# 用法：docker run --rm -v "$GITHUB_WORKSPACE:/w" -w /w ubuntu:18.04 bash /w/ci/build_gcc75.sh
set -e

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y --no-install-recommends \
  ca-certificates git make bc bison flex libssl-dev libelf-dev \
  python2.7 python3 gcc g++ libc6-dev gcc-aarch64-linux-gnu xz-utils
ln -sf /usr/bin/python2.7 /usr/bin/python

echo "=== 编译器 ==="
aarch64-linux-gnu-gcc --version | head -2

cd /w
rm -rf kernel
git clone --depth=1 -b og https://github.com/paperluigis/android-huawei-dura-kernel kernel
cp stock.config kernel/stock.config
mkdir -p kernel/out
cp stock.config kernel/out/.config
cd kernel

# ---------- 必需修补（不修就编不过） ----------
sed -i 's/const_debug unsigned int sysctl_sched_migration_cost/unsigned int sysctl_sched_migration_cost/g' kernel/sched/fair.c
sed -i 's/extern const_debug unsigned int sysctl_sched_migration_cost/extern unsigned int sysctl_sched_migration_cost/g' kernel/sched/sched.h
sed -i 's/^\([[:space:]]*\)sysctl_sched_migration_cost = /\1*(unsigned int *)\&sysctl_sched_migration_cost = /' drivers/misc/mediatek/sched/sched_ctl.c
sed -i 's/-Wdeclaration-after-statement/-Wno-declaration-after-statement/g' Makefile
for f in scripts/dtc/dtc-lexer.l scripts/dtc/dtc-lexer.lex.c scripts/dtc/dtc-parser.y scripts/dtc/dtc-parser.tab.c; do
  [ -f "$f" ] && sed -i 's/^YYLTYPE yylloc;/extern YYLTYPE yylloc;/' "$f" || true
done

# 剔除源码树里不存在的 imgsensor / LCM
D=drivers/misc/mediatek/imgsensor/src/common/v1
L=drivers/misc/mediatek/lcm
S=$(grep -m1 '^CONFIG_CUSTOM_KERNEL_IMGSENSOR=' out/.config | cut -d'"' -f2)
K=""
for x in $S; do [ -d "$D/$x" ] && K="$K $x"; done
scripts/config --file out/.config --set-str CUSTOM_KERNEL_IMGSENSOR "$(echo $K)"
C=$(grep -m1 '^CONFIG_CUSTOM_KERNEL_LCM=' out/.config | cut -d'"' -f2)
K2=""
for x in $C; do [ -d "$L/$x" ] && K2="$K2 $x"; done
scripts/config --file out/.config --set-str CUSTOM_KERNEL_LCM "$(echo $K2)"
echo "LCM kept: $K2"

sed -i 's/^\([[:space:]]*\)select CMA_DEBUG$/\1# select CMA_DEBUG  # off to match stock/' mm/Kconfig
scripts/config --file out/.config --disable CMA_DEBUG --disable CMA_DEBUGFS

export ARCH=arm64
export CROSS_COMPILE=aarch64-linux-gnu-
make O=out ARCH=arm64 CROSS_COMPILE=$CROSS_COMPILE olddefconfig
sed -i 's/^CONFIG_CMA_DEBUG=y/# CONFIG_CMA_DEBUG is not set/' out/.config || true

# ---------- 压制 -Werror（gcc7 警告更严） ----------
# 这棵树用的是裸 -Werror（GCC 报错信息里显示成 -Werror=类别），直接从 Makefile 里剥掉
GW=$(grep -rl -- '-Werror' --include='Makefile*' --include='*.mk' . 2>/dev/null || true)
echo "=== 含 -Werror 的文件 ==="
echo "$GW"
for f in $GW; do
  sed -i 's/-Werror-/-W/g' "$f"
  sed -i 's/-Werror//g' "$f"
done
GW2=$(grep -rl -- '-Werror' --include='Makefile*' --include='*.mk' . 2>/dev/null || true)
echo "=== 处理后仍含 -Werror 的文件（应为空） ==="
echo "$GW2"

echo "=== 关键配置 ==="
grep -E "^CONFIG_(ARM64_PHYS_OFFSET|ARM64_VA_BITS|ARM64_4K_PAGES|ARM64_ERRATUM_843419|LOCALVERSION)=" out/.config || true

# ---------- 编译 ----------
make O=out ARCH=arm64 CROSS_COMPILE=$CROSS_COMPILE \
     HOSTCFLAGS="-fcommon" \
     KCFLAGS="-Wno-error" \
     -j"$(nproc)" Image

ls -l out/arch/arm64/boot/Image
aarch64-linux-gnu-gcc --version | head -1 > out/GCC_VERSION.txt
echo "=== 完成 ==="
cat out/GCC_VERSION.txt
