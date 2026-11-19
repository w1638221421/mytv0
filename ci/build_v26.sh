#!/bin/bash
# v26b: LOS 配置 + 命名空间
#       + 保留 MODVERSIONS=y（vermagic 一致），但让 check_version() 直接放行 -> 厂商预编译模块可加载
#       + 打补丁去掉 SCHED_DEBUG 依赖（与 LOS 一致，避免 gauge 结构体偏移）
set -e
WS="${GITHUB_WORKSPACE:-/w}"
cd "$WS"

if [ ! -f src/Code_Opensource/kernel-4.4/Makefile ]; then
  mkdir -p src
  if [ ! -f /tmp/dura.gz ]; then
    URL="https://download-c1.huawei.com/download/downloadCenter?downloadId=5E5F1FF8BAE2D3B6C865452D95215F2F&version=E3B4C5DDCF2BB14EF20527C810B6E308&siteCode=worldwide"
    wget -q --tries=3 --timeout=300 -U "Mozilla/5.0" -O /tmp/dura.gz "$URL"
  fi
  tar -xzf /tmp/dura.gz -C src --wildcards 'Code_Opensource/kernel-4.4/*' || true
fi

K="$WS/src/Code_Opensource/kernel-4.4"
cd "$K"

echo "=== 补丁1: sched_ctl.c 允许无 SCHED_DEBUG 编译 ==="
sed -i '/sysctl_sched_migration_cost[[:space:]]*=/s/^\([[:space:]]*\)/\1\/\/ DRAPIX: /' drivers/misc/mediatek/sched/sched_ctl.c || true
grep -n "DRAPIX" drivers/misc/mediatek/sched/sched_ctl.c | head -2 || true

echo "=== 补丁2: module.c 跳过符号 CRC 校验 ==="
awk '
/^static int check_version\(/ { infunc=1 }
infunc && /^\{$/ { print; print "\treturn 1; /* DRAPIX: skip symbol CRC check */"; infunc=0; next }
{ print }
' kernel/module.c > /tmp/module.c.new
mv /tmp/module.c.new kernel/module.c
grep -n "DRAPIX: skip" kernel/module.c | head -2

export ARCH=arm64
export CROSS_COMPILE="$WS/gcc49/bin/aarch64-linux-android-"
export HOSTCFLAGS="-fcommon"
export PATH="$WS/gcc49/bin:$PATH"

make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 dura64_defconfig
cp "$WS/ci/los_real.config" out/.config
scripts/config --file out/.config \
  --set-str LOCALVERSION "+" \
  --set-str CUSTOM_KERNEL_IMGSENSOR "hi1333_mipi_raw s5k3l6_mipi_raw ov13855_mipi_raw hi846_mipi_raw ov8856_mipi_raw gc8034_mipi_raw gc8034a_mipi_raw hi556_mipi_raw gc5025a_mipi_raw gc5025h_mipi_raw ov5675_mipi_raw" \
  --enable NAMESPACES \
  --enable PID_NS \
  --enable UTS_NS \
  --enable IPC_NS
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 olddefconfig

echo "--- 核对 ---"
grep -E "^CONFIG_(MODVERSIONS|NAMESPACES|PID_NS|UTS_NS|IPC_NS|SCHED_DEBUG|LOCALVERSION)=" out/.config || true

make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" HOSTCFLAGS="-fcommon" python=python2 -j"$(nproc)" Image
ls -l out/arch/arm64/boot/Image

mkdir -p "$WS/out"
cp out/arch/arm64/boot/Image "$WS/out/"
cp out/System.map "$WS/out/"
cp out/.config "$WS/out/config.txt"
echo ok
