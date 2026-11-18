#!/bin/bash
# 干净版：原厂 config + 容器支持，其余一律不动（不开 VT、不改 cmdline、不碰 printk）
set -e
WS="${GITHUB_WORKSPACE:-/w}"
cd "$WS"
if [ ! -f src/Code_Opensource/kernel-4.4/Makefile ]; then
  mkdir -p src
  [ -f /tmp/dura.gz ] || wget -q --tries=3 --timeout=300 -U "Mozilla/5.0" -O /tmp/dura.gz \
    "https://download-c1.huawei.com/download/downloadCenter?downloadId=5E5F1FF8BAE2D3B6C865452D95215F2F&version=E3B4C5DDCF2BB14EF20527C810B6E308&siteCode=worldwide"
  tar -xzf /tmp/dura.gz -C src --wildcards 'Code_Opensource/kernel-4.4/*' || true
fi
K="$WS/src/Code_Opensource/kernel-4.4"
cd "$K"
export ARCH=arm64
export CROSS_COMPILE="$WS/gcc49/bin/aarch64-linux-android-"
export HOSTCFLAGS="-fcommon"
export PATH="$WS/gcc49/bin:$PATH"

make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 dura64_defconfig
cp "$WS/ci/config_stock.txt" out/.config
scripts/config --file out/.config \
  --set-str CUSTOM_KERNEL_IMGSENSOR "hi1333_mipi_raw s5k3l6_mipi_raw ov13855_mipi_raw hi846_mipi_raw ov8856_mipi_raw gc8034_mipi_raw gc8034a_mipi_raw hi556_mipi_raw gc5025a_mipi_raw gc5025h_mipi_raw ov5675_mipi_raw" \
  --set-str CUSTOM_KERNEL_LCM "txd_ili9881c_5p45_hd_video truly_nt35521z_5p45_hd_video holitech_ili9881c_5p45_hd_video sky_ili9881p_5p45_hd_video txd_ft8006u_5p45_hd_video boyi_hx8394f_5p45_hd_video" \
  --enable SCHED_DEBUG \
  --enable NAMESPACES --enable UTS_NS --enable IPC_NS --enable PID_NS --enable NET_NS --enable USER_NS \
  --enable CGROUP_DEVICE --enable CGROUP_FREEZER --enable CGROUP_SCHED --enable MEMCG \
  --enable DEVTMPFS --enable POSIX_MQUEUE --enable VETH --enable BRIDGE --enable OVERLAY_FS \
  --disable CMDLINE_EXTEND --enable CMDLINE_FROM_BOOTLOADER
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 olddefconfig
echo "--- 关键项 ---"
grep -E "^CONFIG_(NAMESPACES|NET_NS|PID_NS|USER_NS|CGROUP_DEVICE|MEMCG|VETH|BRIDGE|OVERLAY_FS|DEVTMPFS|POSIX_MQUEUE|CMDLINE|CMDLINE_FROM_BOOTLOADER|VT|FRAMEBUFFER_CONSOLE|MTK_MD1_SUPPORT)=" out/.config || true

make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" HOSTCFLAGS="-fcommon" python=python2 -j"$(nproc)" Image
mkdir -p "$WS/out"
cp out/arch/arm64/boot/Image "$WS/out/"
cp out/System.map "$WS/out/"
cp out/.config "$WS/out/config.txt"
echo ok