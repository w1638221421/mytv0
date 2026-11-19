#!/bin/bash
# v30: v29 + 全部容器可选项（NET_NS/USER_NS/DEVTMPFS/OVERLAY/VETH/MEMCG/limits 等）
#      + 电量计结构体补 8 字节(736->744, 与 vendor fuelgauged 握手对齐)
#      + 补 connectivity_export_dump_thread_state 符号(修复 WiFi)
#      + check_version 放行(厂商预编译模块可加载) + 无 SCHED_DEBUG
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

echo "=== 补丁A: sched_ctl.c 允许无 SCHED_DEBUG ==="
sed -i '/sysctl_sched_migration_cost[[:space:]]*=/s/^\([[:space:]]*\)/\1\/\/ DRAPIX: /' drivers/misc/mediatek/sched/sched_ctl.c || true

echo "=== 补丁B: module.c 跳过符号 CRC 校验 ==="
sed -i 's/if (!crc)/if (1)/' kernel/module.c

echo "=== 补丁C: 电量计 custom 结构体补 8 字节 (736->744) ==="
python3 - <<'PY'
p="drivers/power/mediatek/battery/mtk_battery_internal.h"
s=open(p,encoding="utf8",errors="ignore").read()
# 撤销可能存在的旧误补丁
s=s.replace("\tunsigned long long drapix_reserved_8; /* +8 bytes: 对齐 firmware GM3.0 结构体 */\n","")
i = s.find("int apsleep_battery_voltage_compensate;")
assert i != -1, "apsleep field not found"
j = s.find("};", i)
assert j != -1, "struct end not found"
s = s[:j] + "\tunsigned long long drapix_reserved8; /* +8 bytes: fuel_gauge_custom_data 736->744 */\n" + s[j:]
open(p,"w",encoding="utf8").write(s)
print("patched custom struct", p)
PY

echo "=== 补丁D: WiFi 符号 connectivity_export_dump_thread_state ==="
python3 - <<'PY'
h="drivers/misc/mediatek/connectivity/common/connectivity_build_in_adapter.h"
c="drivers/misc/mediatek/connectivity/common/connectivity_build_in_adapter.c"
hs=open(h,encoding="utf8",errors="ignore").read()
hs=hs.replace("void connectivity_export_show_stack(struct task_struct *tsk, unsigned long *sp);",
  "void connectivity_export_show_stack(struct task_struct *tsk, unsigned long *sp);\nvoid connectivity_export_dump_thread_state(struct task_struct *tsk);")
open(h,"w",encoding="utf8").write(hs)
cs=open(c,encoding="utf8",errors="ignore").read()
cs=cs.replace("EXPORT_SYMBOL(connectivity_export_show_stack);",
  "EXPORT_SYMBOL(connectivity_export_show_stack);\n\nvoid connectivity_export_dump_thread_state(struct task_struct *tsk)\n{\n\t/* no-op stub for newer vendor wmt_drv */\n}\nEXPORT_SYMBOL(connectivity_export_dump_thread_state);")
open(c,"w",encoding="utf8").write(cs)
print("patched", h, c)
PY

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
  --enable IPC_NS \
  --enable POSIX_MQUEUE \
  --enable NET_NS \
  --enable USER_NS \
  --enable DEVTMPFS \
  --enable OVERLAY_FS \
  --enable VETH \
  --enable BRIDGE \
  --enable BRIDGE_NETFILTER \
  --enable MEMCG \
  --enable MEMCG_SWAP \
  --enable MEMCG_SWAP_ENABLED \
  --enable CGROUP_DEVICE \
  --enable CGROUP_NET_PRIO \
  --enable CFS_BANDWIDTH \
  --enable CGROUP_PIDS \
  --enable SYSVIPC \
  --enable MACVLAN \
  --enable IP6_NF_NAT \
  --enable NF_NAT_IPV6 \
  --enable IP6_NF_TARGET_MASQUERADE \
  --enable NF_NAT_MASQUERADE_IPV6
make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" python=python2 olddefconfig

echo "--- 核对 ---"
grep -E "^CONFIG_(MODVERSIONS|NAMESPACES|PID_NS|UTS_NS|IPC_NS|POSIX_MQUEUE|SCHED_DEBUG|LOCALVERSION|MTK_GAUGE_VERSION)=" out/.config || true

make O=out ARCH=arm64 CROSS_COMPILE="$CROSS_COMPILE" HOSTCFLAGS="-fcommon" python=python2 -j"$(nproc)" Image
ls -l out/arch/arm64/boot/Image

mkdir -p "$WS/out"
cp out/arch/arm64/boot/Image "$WS/out/"
cp out/System.map "$WS/out/"
cp out/.config "$WS/out/config.txt"
echo ok
