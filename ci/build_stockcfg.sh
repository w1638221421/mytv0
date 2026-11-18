#!/bin/bash
# 保真度最高：用设备自己导出的原厂 config（/proc/config.gz）原样编译，不做任何改动
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

echo "=== 用原厂 config 覆盖 ==="
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 dura64_defconfig
cp "$WS/ci/config_stock.txt" out/.config
# 2018 包里不存在的传感器/LCM 驱动必须去掉（原厂 2022 树才有 gc5025aw/s5k3l6mam/s5k5e9yx/hjc_ili9881c）
scripts/config --file out/.config \
  --set-str CUSTOM_KERNEL_IMGSENSOR "hi1333_mipi_raw s5k3l6_mipi_raw ov13855_mipi_raw hi846_mipi_raw ov8856_mipi_raw gc8034_mipi_raw gc8034a_mipi_raw hi556_mipi_raw gc5025a_mipi_raw gc5025h_mipi_raw ov5675_mipi_raw" \
  --set-str CUSTOM_KERNEL_LCM "txd_ili9881c_5p45_hd_video truly_nt35521z_5p45_hd_video holitech_ili9881c_5p45_hd_video sky_ili9881p_5p45_hd_video txd_ft8006u_5p45_hd_video boyi_hx8394f_5p45_hd_video"
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 olddefconfig
echo "--- 关键项 ---"
grep -E "^CONFIG_(VT|VT_CONSOLE|FRAMEBUFFER_CONSOLE|CMDLINE|CMDLINE_EXTEND|CMDLINE_FROM_BOOTLOADER|HZ|FINAL_RELEASE|LOCALVERSION)=" out/.config || true

echo "=== 编译 ==="
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" HOSTCFLAGS="-fcommon" python=python2 -j"$(nproc)" Image
ls -l out/arch/arm64/boot/Image

mkdir -p "$WS/out"
cp out/arch/arm64/boot/Image "$WS/out/"
cp out/System.map "$WS/out/"
cp out/.config "$WS/out/config.txt"
echo ok