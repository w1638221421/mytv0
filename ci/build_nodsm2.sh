#!/bin/bash
# VT+fbcon 打点构建：每个 initcall printk 一行 "DI<序号> <符号名>"
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

echo "=== 注入 printk 打点 ==="
python3 "$WS/ci/patch_fbv3.py" init/main.c
python3 "$WS/ci/patch_dsm_skip.py" .
grep -n "DRAPIX: 跳过 DSM" drivers/dsm/dsm_earlyboot_client/dsm_earlyboot_client.c || true
grep -n "DI%u" init/main.c | head -3

export ARCH=arm64
export CROSS_COMPILE="$WS/gcc49/bin/aarch64-linux-android-"
export HOSTCFLAGS="-fcommon"
export PATH="$WS/gcc49/bin:$PATH"

echo "=== 配置：defconfig + 打开 VT/fbcon（屏幕才能显示字） ==="
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 dura64_defconfig
scripts/config --file out/.config \
  --enable CMDLINE_EXTEND --disable CMDLINE_FROM_BOOTLOADER --disable CMDLINE_FORCE \
  --set-str CMDLINE "androidboot.selinux=permissive printk.disable_uart=2 console=tty0 ignore_loglevel loglevel=8 keep_bootcon" \
  --enable VT --enable VT_CONSOLE --enable FRAMEBUFFER_CONSOLE \
  --enable DUMMY_CONSOLE --enable FB --enable FB_CMDLINE --enable LOGO \
  --enable VGA_CONSOLE \
  --enable HZ_300 --set-val HZ 300 --enable FINAL_RELEASE \
  --disable MMC_FFU --disable HUAWEI_VIRT_TO_PHYS || true
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 olddefconfig
echo "--- 关键项 ---"
grep -E "^CONFIG_(VT|VT_CONSOLE|FRAMEBUFFER_CONSOLE|DUMMY_CONSOLE|FB|LOGO|HZ|FINAL_RELEASE|CMDLINE)=" out/.config || true
grep -q "^CONFIG_VT=y" out/.config || { echo "FATAL: VT 没开"; exit 1; }
grep -q "^CONFIG_FRAMEBUFFER_CONSOLE=y" out/.config || { echo "FATAL: fbcon 没开"; exit 1; }
grep -q "^CONFIG_CMDLINE=" out/.config || { echo "FATAL: CMDLINE 没写进去"; exit 1; }
grep -q "console=tty0" out/.config || { echo "FATAL: CMDLINE 里没有 console=tty0"; exit 1; }

echo "=== 编译 ==="
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" HOSTCFLAGS="-fcommon" python=python2 -j"$(nproc)" Image
ls -l out/arch/arm64/boot/Image

mkdir -p "$WS/out"
cp out/arch/arm64/boot/Image "$WS/out/"
cp out/System.map "$WS/out/"
cp out/.config "$WS/out/config.txt"
echo ok
