#!/bin/bash
# 打点构建：官方源码 + dura64_defconfig + 在 do_one_initcall 里画 16 位二进制方块到真实 framebuffer
set -e

WS="${GITHUB_WORKSPACE:-/w}"
cd "$WS"

echo "=== 下载/解压官方源码 ==="
if [ ! -f src/Code_Opensource/kernel-4.4/Makefile ]; then
  mkdir -p src
  if [ ! -f /tmp/dura.gz ]; then
    URL="https://download-c1.huawei.com/download/downloadCenter?downloadId=5E5F1FF8BAE2D3B6C865452D95215F2F&version=E3B4C5DDCF2BB14EF20527C810B6E308&siteCode=worldwide"
    wget -q --tries=3 --timeout=300 -U "Mozilla/5.0" -O /tmp/dura.gz "$URL"
  fi
  ls -l /tmp/dura.gz
  tar -xzf /tmp/dura.gz -C src --wildcards 'Code_Opensource/kernel-4.4/*' || true
fi
K="$WS/src/Code_Opensource/kernel-4.4"
head -5 "$K/Makefile"

echo "=== 注入打点补丁 ==="
python3 "$WS/ci/patch_fbidx.py" "$K/init/main.c"
grep -n "drapix_index" "$K/init/main.c" | head -6

cd "$K"
export ARCH=arm64
export CROSS_COMPILE="$WS/gcc49/bin/aarch64-linux-android-"
export HOSTCFLAGS="-fcommon"
export PATH="$WS/gcc49/bin:$PATH"

echo "=== 配置 ==="
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 dura64_defconfig
scripts/config --file out/.config --enable HZ_300 --set-val HZ 300 --enable FINAL_RELEASE --disable MMC_FFU --disable HUAWEI_VIRT_TO_PHYS || true
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 olddefconfig
grep -E "^CONFIG_(HZ|HZ_300|FINAL_RELEASE|MMC_FFU|FB|VT)=" out/.config || true
grep -c "CONFIG_FB=y" out/.config || true

echo "=== 编译 ==="
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" HOSTCFLAGS="-fcommon" python=python2 -j"$(nproc)" Image
ls -l out/arch/arm64/boot/Image out/System.map

echo "=== 导出 ==="
mkdir -p "$WS/out"
cp out/arch/arm64/boot/Image "$WS/out/"
cp out/System.map "$WS/out/"
cp out/.config "$WS/out/config.txt"
echo ok
