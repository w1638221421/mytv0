#!/bin/bash
# v24: LOS 配置 + 仅命名空间（最小集），用于二分定位电量/WiFi 破坏源
#      LOCALVERSION="+" 使 vermagic = 4.4.95+ 与原厂一致
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

export ARCH=arm64
export CROSS_COMPILE="$WS/gcc49/bin/aarch64-linux-android-"
export HOSTCFLAGS="-fcommon"
export PATH="$WS/gcc49/bin:$PATH"

make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 dura64_defconfig
cp "$WS/ci/los_real.config" out/.config
scripts/config --file out/.config \
  --set-str LOCALVERSION "+" \
  --set-str CUSTOM_KERNEL_IMGSENSOR "hi1333_mipi_raw s5k3l6_mipi_raw ov13855_mipi_raw hi846_mipi_raw ov8856_mipi_raw gc8034_mipi_raw gc8034a_mipi_raw hi556_mipi_raw gc5025a_mipi_raw gc5025h_mipi_raw ov5675_mipi_raw" \
  --enable SCHED_DEBUG \
  --enable NAMESPACES \
  --enable PID_NS \
  --enable UTS_NS \
  --enable IPC_NS
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 olddefconfig

echo "--- 核对：原厂特征项必须保持原样 ---"
grep -E "^CONFIG_(CMDLINE|CMDLINE_EXTEND|CMDLINE_FROM_BOOTLOADER|LOCALVERSION|SECCOMP|NAMESPACES|MEMCG|OVERLAY_FS)=" out/.config || true
echo "--- 核对：新增项 ---"
grep -E "^CONFIG_(SYSVIPC|CFS_BANDWIDTH|CGROUP_PIDS|CGROUP_NET_PRIO|MEMCG_SWAP|MACVLAN|IP6_NF_NAT)=" out/.config || true

make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" HOSTCFLAGS="-fcommon" python=python2 -j"$(nproc)" Image
ls -l out/arch/arm64/boot/Image
echo "--- vermagic ---"
strings out/arch/arm64/boot/Image | grep -m2 "^4\.4\.95"

mkdir -p "$WS/out"
cp out/arch/arm64/boot/Image "$WS/out/"
cp out/System.map "$WS/out/"
cp out/.config "$WS/out/config.txt"
echo ok
