#!/bin/bash
# v21: v18 配置 + 白名单诊断日志上屏（定位 WiFi/电量/模块加载失败的原因）
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

python3 "$WS/ci/patch_diag.py" kernel/printk/printk.c
grep -c DRAPIX kernel/printk/printk.c || true

make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" HOSTCFLAGS="-fcommon" python=python2 -j"$(nproc)" Image
ls -l out/arch/arm64/boot/Image

mkdir -p "$WS/out"
cp out/arch/arm64/boot/Image "$WS/out/"
cp out/System.map "$WS/out/"
cp out/.config "$WS/out/config.txt"
echo ok
