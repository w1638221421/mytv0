#!/bin/bash
# v17: v14 + CONFIG_LOCALVERSION="+" 使 vermagic 与 vendor 模块一致 (4.4.95+)
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
cp "$WS/ci/config_v14.txt" out/.config
scripts/config --file out/.config \
  --set-str CUSTOM_KERNEL_IMGSENSOR "hi1333_mipi_raw s5k3l6_mipi_raw ov13855_mipi_raw hi846_mipi_raw ov8856_mipi_raw gc8034_mipi_raw gc8034a_mipi_raw hi556_mipi_raw gc5025a_mipi_raw gc5025h_mipi_raw ov5675_mipi_raw" \
  --set-str CUSTOM_KERNEL_LCM "txd_ili9881c_5p45_hd_video truly_nt35521z_5p45_hd_video holitech_ili9881c_5p45_hd_video sky_ili9881p_5p45_hd_video txd_ft8006u_5p45_hd_video boyi_hx8394f_5p45_hd_video" \
  --set-str LOCALVERSION "+"
  --enable SCHED_DEBUG
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 olddefconfig

echo "--- 关键项核对 ---"
grep -E "^CONFIG_(CMDLINE|SECCOMP|SECCOMP_FILTER|NAMESPACES|PID_NS|UTS_NS|IPC_NS|NET_NS|USER_NS|CGROUPS|CGROUP_DEVICE|CGROUP_SCHED|CGROUP_PIDS|CGROUP_FREEZER|MEMCG|CFS_BANDWIDTH|DEVTMPFS|BLK_DEV_LOOP|EXT4_FS|OVERLAY_FS|SYSVIPC|POSIX_MQUEUE|VT|FRAMEBUFFER_CONSOLE|SECURITY_SELINUX)=" out/.config || true

make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" HOSTCFLAGS="-fcommon" python=python2 -j"$(nproc)" Image
ls -l out/arch/arm64/boot/Image

mkdir -p "$WS/out"
cp out/arch/arm64/boot/Image "$WS/out/"
cp out/System.map "$WS/out/"
cp out/.config "$WS/out/config.txt"
echo ok
