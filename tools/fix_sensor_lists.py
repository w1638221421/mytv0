#!/usr/bin/env python3
"""
Drop camera sensor / LCM names that the config requests but the source tree does
not contain. MTK's media stack turns every name into a sub-make target, so a single
missing directory aborts the whole kernel build.

Usage: python fix_sensor_lists.py <kernel_root>
"""
import os
import subprocess
import sys

root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else '.')

TARGETS = [
    ('CONFIG_CUSTOM_KERNEL_IMGSENSOR', 'drivers/misc/mediatek/imgsensor/src/common/v1'),
    ('CONFIG_CUSTOM_KERNEL_LCM', 'drivers/misc/mediatek/lcm'),
]


def read_value(sym):
    cfg = os.path.join(root, '.config')
    for line in open(cfg, encoding='utf-8', errors='replace'):
        if line.startswith(sym + '='):
            return line.split('=', 1)[1].strip().strip('"')
    return None


for sym, base in TARGETS:
    val = read_value(sym)
    if not val:
        print(f'{sym}: not set, skip')
        continue
    keep, drop = [], []
    for name in val.split():
        (keep if os.path.isdir(os.path.join(root, base, name)) else drop).append(name)
    if not drop:
        print(f'{sym}: all {len(keep)} entries present')
        continue
    print(f'{sym}: dropping {len(drop)} missing -> {" ".join(drop)}')
    subprocess.run(['scripts/config', '--file', '.config', '--set-str', sym, ' '.join(keep)],
                   cwd=root, check=True)
