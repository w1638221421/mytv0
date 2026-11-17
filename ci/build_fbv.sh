#!/bin/bash
# VT(屏幕亮) + 不输出文字 + 直接在 framebuffer 画 16 位方块计数器
# 关键：不加 console=tty0，避免文字滚动把方块刷掉；VT 保证屏幕被内核接管（企鹅会出现）
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

echo "=== 注入 framebuffer 方块打点 ==="
python3 "$WS/ci/patch_fbidx.py" init/main.c
grep -n "drapix_index" init/main.c | head -4

export ARCH=arm64
export CROSS_COMPILE="$WS/gcc49/bin/aarch64-linux-android-"
export HOSTCFLAGS="-fcommon"
export PATH="$WS/gcc49/bin:$PATH"

echo "=== 配置：VT 开、console 不动 ==="
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 dura64_defconfig
scripts/config --file out/.config \
  --enable VT --enable VT_CONSOLE --enable FRAMEBUFFER_CONSOLE \
  --enable DUMMY_CONSOLE --enable FB --enable LOGO \
  --enable HZ_300 --set-val HZ 300 --enable FINAL_RELEASE \
  --disable MMC_FFU --disable HUAWEI_VIRT_TO_PHYS || true
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 olddefconfig
grep -E "^CONFIG_(VT|FRAMEBUFFER_CONSOLE|FB|HZ|FINAL_RELEASE|CMDLINE|CMDLINE_EXTEND|CMDLINE_FROM_BOOTLOADER)=" out/.config || true
grep -q "^CONFIG_VT=y" out/.config || { echo "FATAL: VT 没开"; exit 1; }
grep -q "^CONFIG_FRAMEBUFFER_CONSOLE=y" out/.config || { echo "FATAL: fbcon 没开"; exit 1; }
# 必须走 LK 的 cmdline（FROM_BOOTLOADER=y）；CONFIG_CMDLINE 字符串里自带 console=tty0 是正常的
grep -q "^CONFIG_CMDLINE_FROM_BOOTLOADER=y" out/.config || { echo "FATAL: 应该使用 LK 的 cmdline"; exit 1; }
grep -q "^CONFIG_CMDLINE_EXTEND=y" out/.config && { echo "FATAL: 不该 EXTEND"; exit 1; }
echo "[ok] cmdline 来源 = LK"

echo "=== 编译 ==="
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" HOSTCFLAGS="-fcommon" python=python2 -j"$(nproc)" Image
ls -l out/arch/arm64/boot/Image
mkdir -p "$WS/out"
cp out/arch/arm64/boot/Image "$WS/out/"
cp out/System.map "$WS/out/"
cp out/.config "$WS/out/config.txt"
echo ok
