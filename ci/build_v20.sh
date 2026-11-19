#!/bin/bash
# v20: 与 v18 完全相同，但改用原厂同款编译器 Linaro GCC 6.3.1
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

echo "=== 下载 Linaro GCC 6.3.1 ==="
if [ ! -d "$WS/gcc631" ]; then
  wget -q --tries=4 --timeout=600 -O /tmp/gcc631.tar.xz \
    "https://releases.linaro.org/components/toolchain/binaries/6.3-2017.02/aarch64-linux-gnu/gcc-linaro-6.3.1-2017.02-x86_64_aarch64-linux-gnu.tar.xz"
  tar -xJf /tmp/gcc631.tar.xz -C "$WS"
  mv "$WS/gcc-linaro-6.3.1-2017.02-x86_64_aarch64-linux-gnu" "$WS/gcc631"
fi
"$WS/gcc631/bin/aarch64-linux-gnu-gcc" --version | head -1

K="$WS/src/Code_Opensource/kernel-4.4"
cd "$K"

export ARCH=arm64
export CROSS_COMPILE="$WS/gcc631/bin/aarch64-linux-gnu-"
export HOSTCFLAGS="-fcommon"
export PATH="$WS/gcc631/bin:$PATH"

make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 dura64_defconfig
cp "$WS/ci/stock_real.config" out/.config
scripts/config --file out/.config \
  --set-str LOCALVERSION "+" \
  --enable CFS_BANDWIDTH \
  --enable CGROUP_PIDS \
  --enable CGROUP_NET_PRIO \
  --enable MEMCG_SWAP \
  --enable MEMCG_SWAP_ENABLED \
  --enable SYSVIPC \
  --enable MACVLAN \
  --enable IP6_NF_NAT \
  --enable NF_NAT_IPV6 \
  --enable IP6_NF_TARGET_MASQUERADE \
  --enable NF_NAT_MASQUERADE_IPV6
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 olddefconfig

grep -E "^CONFIG_(CMDLINE|CMDLINE_EXTEND|LOCALVERSION)=" out/.config || true

make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" HOSTCFLAGS="-fcommon" python=python2 -j"$(nproc)" Image
ls -l out/arch/arm64/boot/Image
echo "--- 编译器与 vermagic ---"
strings out/arch/arm64/boot/Image | grep -m3 "Linux version 4.4.95"

mkdir -p "$WS/out"
cp out/arch/arm64/boot/Image "$WS/out/"
cp out/System.map "$WS/out/"
cp out/.config "$WS/out/config.txt"
echo ok
