#!/bin/bash
# 复现 uefi2333/dra-rksu-ci 中"已被验证能开机"的配置配方（去掉 KernelSU 部分）
# 关键点：
#   1) dura64_defconfig + 合并 dura64_board_AL00A_defconfig
#   2) 强制 stock 对齐：HZ=300 / FINAL_RELEASE=y / LOG_BUF_SHIFT=19
#   3) 关掉 defconfig 里默认打开的 debug 项（MMC_FFU / HUAWEI_VIRT_TO_PHYS / *_DEBUG / CMA_DEBUG）
#   4) 最后一步用 sed 强杀，之后绝不再跑 olddefconfig（否则 CMA_DEBUG 会被复活）
set -e

ROOT="${ROOT:-/w}"
SRC="$ROOT/src"
WORK="$ROOT/work"
OUT="$ROOT/out"
mkdir -p "$SRC" "$WORK" "$OUT"

echo "=== deps ==="
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y --no-install-recommends \
  ca-certificates git make bc bison flex libssl-dev libelf-dev libncurses5-dev \
  python2.7 python3 gcc g++ libc6-dev xz-utils
ln -sf /usr/bin/python2.7 /usr/bin/python || true
ln -sf /usr/bin/python2.7 /usr/bin/python2 || true
echo "python: $(command -v python) / python2: $(command -v python2) / python2.7: $(command -v python2.7)"
python --version 2>&1 || true

echo "=== 下载官方源码包 ==="
URL="https://download-c1.huawei.com/download/downloadCenter?downloadId=5E5F1FF8BAE2D3B6C865452D95215F2F&version=E3B4C5DDCF2BB14EF20527C810B6E308&siteCode=worldwide"
if [ ! -f $SRC/dura.gz ]; then
  wget -q --tries=3 --timeout=300 -U "Mozilla/5.0" -O $SRC/dura.gz "$URL"
fi
ls -l $SRC/dura.gz

echo "=== 解压 kernel-4.4 ==="
rm -rf "$WORK" && mkdir -p "$WORK"
tar -xzf "$SRC/dura.gz" -C "$WORK" --wildcards 'Code_Opensource/kernel-4.4/*' || true
K=$WORK/Code_Opensource/kernel-4.4
cd "$K"
du -sh "$K"
head -5 Makefile

echo "=== host/kernel 构建修补 ==="
sed -i 's/-Wdeclaration-after-statement/-Wno-declaration-after-statement/g' Makefile
grep -q 'Wno-error=return-type' Makefile || \
  sed -i 's/^KBUILD_CFLAGS :=/KBUILD_CFLAGS := -Wno-error=return-type -Wno-error=incompatible-pointer-types /' Makefile
for f in scripts/dtc/dtc-lexer.l scripts/dtc/dtc-lexer.lex.c scripts/dtc/dtc-parser.y scripts/dtc/dtc-parser.tab.c; do
  [ -f "$f" ] || continue
  sed -i 's/^YYLTYPE yylloc;/extern YYLTYPE yylloc;/' "$f" || true
done
if grep -q 'extern YYLTYPE yylloc' scripts/dtc/dtc-parser.tab.c 2>/dev/null; then
  sed -i '0,/extern YYLTYPE yylloc;/s//YYLTYPE yylloc;/' scripts/dtc/dtc-parser.tab.c || true
fi
grep -q 'HOSTCFLAGS.*-fcommon' Makefile || echo 'export HOSTCFLAGS += -fcommon' >> Makefile

f=drivers/misc/mediatek/sched/sched_ctl.c
if [ -f "$f" ]; then
  sed -i 's/sysctl_sched_migration_cost = /*(unsigned int *)\&sysctl_sched_migration_cost = /g' "$f"
  sed -i 's/\*(unsigned int \*)\&\*(unsigned int \*)\&/*(unsigned int *)\&/g' "$f"
fi
for f in kernel/sched/core.c kernel/sched/fair.c; do
  [ -f "$f" ] || continue
  sed -i 's/^const unsigned int sysctl_sched_migration_cost/unsigned int sysctl_sched_migration_cost/' "$f" || true
  sed -i 's/^static const unsigned int sysctl_sched_migration_cost/unsigned int sysctl_sched_migration_cost/' "$f" || true
done

echo "=== CMA_DEBUG 从 Kconfig 层面禁掉 ==="
sed -i '/^config CMA_DEBUG$/{n;n;s/^/\tdepends on BROKEN\n/;}' mm/Kconfig || true
sed -i '/^config CMA_DEBUGFS$/{n;n;s/^/\tdepends on BROKEN\n/;}' mm/Kconfig || true
grep -Rnl 'select CMA_DEBUG' --include='Kconfig*' . 2>/dev/null | while read -r g; do
  sed -i 's/select CMA_DEBUG/# select CMA_DEBUG  # killed/g' "$g" || true
done

echo "=== 配置：dura64_defconfig + 板级 AL00A ==="
export ARCH=arm64
export CROSS_COMPILE="${GCC49}/bin/aarch64-linux-android-"
export PATH="${GCC49}/bin:$PATH"
make O=out ARCH=arm64 python=python2 dura64_defconfig

BOARD=arch/arm64/configs/dura64_board_AL00A_defconfig
if [ -f "$BOARD" ]; then
  scripts/kconfig/merge_config.sh -m -O out out/.config "$BOARD" 2>/dev/null || cat "$BOARD" >> out/.config
fi

echo "=== stock 对齐（第一遍） ==="
scripts/config --file out/.config \
  --enable HZ_300 --disable HZ_250 --disable HZ_100 --disable HZ_1000 --set-val HZ 300 \
  --enable FINAL_RELEASE \
  --disable SCHED_DEBUG --disable DYNAMIC_DEBUG --disable CGROUP_DEBUG --disable CMA_DEBUG \
  --disable SND_DEBUG --disable SND_DEBUG_VERBOSE --disable SND_PCM_XRUN_DEBUG \
  --disable MMC_BLOCK_IO_LOG --disable MTK_BLOCK_TAG \
  --disable MTK_LOCK_DEBUG --disable MTK_CPU_HOTPLUG_DEBUG_0 \
  --disable MTK_CPU_HOTPLUG_DEBUG_2 --disable MTK_CPU_HOTPLUG_DEBUG_3 \
  --disable SECURITY_SELINUX_DEVELOP \
  --set-val LOG_BUF_SHIFT 19 \
  --disable MMC_FFU \
  --disable HUAWEI_VIRT_TO_PHYS --disable HUAWEI_TS_KIT_TEST \
  --disable SW_BRAND_HONOR --disable SW_BRAND_HUAWEI || true

make O=out ARCH=arm64 python=python2 olddefconfig

echo "=== stock 对齐（第二遍，olddefconfig 之后） ==="
scripts/config --file out/.config \
  --enable HZ_300 --disable HZ_250 --set-val HZ 300 \
  --enable FINAL_RELEASE \
  --disable CMA_DEBUG --disable DYNAMIC_DEBUG --disable SCHED_DEBUG \
  --disable SND_DEBUG --disable SND_DEBUG_VERBOSE --disable MMC_BLOCK_IO_LOG \
  --disable MMC_FFU --disable MTK_BLOCK_TAG \
  --disable HUAWEI_VIRT_TO_PHYS --disable HUAWEI_TS_KIT_TEST || true
make O=out ARCH=arm64 python=python2 olddefconfig

echo "=== 最终 sed 强杀（此后不再 olddefconfig） ==="
kill_off() {
  local off="$1"
  sed -i "s/^CONFIG_${off}=y/# CONFIG_${off} is not set/" out/.config || true
  sed -i "s/^CONFIG_${off}=m/# CONFIG_${off} is not set/" out/.config || true
  if ! grep -q "^# CONFIG_${off} is not set" out/.config && ! grep -q "^CONFIG_${off}=" out/.config; then
    echo "# CONFIG_${off} is not set" >> out/.config
  fi
}
for off in CMA_DEBUG CMA_DEBUGFS DYNAMIC_DEBUG SCHED_DEBUG CGROUP_DEBUG \
           SND_DEBUG SND_DEBUG_VERBOSE SND_PCM_XRUN_DEBUG \
           MMC_BLOCK_IO_LOG MMC_FFU MTK_BLOCK_TAG \
           HUAWEI_VIRT_TO_PHYS HUAWEI_TS_KIT_TEST \
           MTK_LOCK_DEBUG MTK_CPU_HOTPLUG_DEBUG_0 MTK_CPU_HOTPLUG_DEBUG_2 MTK_CPU_HOTPLUG_DEBUG_3 \
           SECURITY_SELINUX_DEVELOP; do
  kill_off "$off"
done
scripts/config --file out/.config --enable HZ_300 --set-val HZ 300 --enable FINAL_RELEASE || true
for off in CMA_DEBUG MMC_FFU HUAWEI_VIRT_TO_PHYS HUAWEI_TS_KIT_TEST MTK_BLOCK_TAG DYNAMIC_DEBUG; do
  kill_off "$off"
done
mkdir -p out/include/config out/include/generated
if [ -f out/include/generated/autoconf.h ]; then
  for off in CMA_DEBUG MMC_FFU HUAWEI_VIRT_TO_PHYS HUAWEI_TS_KIT_TEST MTK_BLOCK_TAG; do
    sed -i "/#define CONFIG_${off} /d" out/include/generated/autoconf.h || true
  done
fi

echo "=== 关键配置校验 ==="
for cfg in HZ HZ_300 FINAL_RELEASE CMA_DEBUG MMC_FFU HUAWEI_VIRT_TO_PHYS LOG_BUF_SHIFT; do
  grep "CONFIG_${cfg}" out/.config | head -2 || echo "CONFIG_${cfg} not found"
done
grep -q '^CONFIG_HZ=300' out/.config || { echo "FATAL: HZ != 300"; exit 1; }
grep -q '^CONFIG_FINAL_RELEASE=y' out/.config || { echo "FATAL: FINAL_RELEASE off"; exit 1; }
if grep -q '^CONFIG_CMA_DEBUG=y' out/.config; then echo "FATAL: CMA_DEBUG on"; exit 1; fi
if grep -q '^CONFIG_MMC_FFU=y' out/.config; then echo "FATAL: MMC_FFU on"; exit 1; fi

echo "=== 预生成 drvgen dtsi（避免 Error 127）==="
make O=out ARCH=arm64 python=python2 drvgen || echo "[warn] drvgen 预生成失败，继续"
ls -l out/arch/arm64/boot/dts/dura64/ 2>/dev/null | head -5 || true

echo "=== 编译 ==="
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" HOSTCFLAGS="-fcommon" python=python2 -j"$(nproc)" Image
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" HOSTCFLAGS="-fcommon" python=python2 -j"$(nproc)" Image.gz-dtb || true
ls -l out/arch/arm64/boot/Image out/System.map 2>/dev/null || true
find out/arch/arm64/boot/dts -name '*.dtb' 2>/dev/null | head -5 || true

echo "=== 打包 ==="
mkdir -p "$OUT"
cp out/arch/arm64/boot/Image "$OUT"/
[ -f out/arch/arm64/boot/Image.gz-dtb ] && cp out/arch/arm64/boot/Image.gz-dtb "$OUT"/ || true
[ -f out/System.map ] && cp out/System.map "$OUT"/ || true
cp out/.config "$OUT"/config.txt
find out/arch/arm64/boot/dts -name '*.dtb' -exec cp {} "$OUT"/ \; 2>/dev/null || true
echo done
