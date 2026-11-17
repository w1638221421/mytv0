#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
patch_head2.py -- 在 head.S 的 stext 入口插入"双通道"极早期标记

通道 1（不依赖屏幕）：把 ASCII "DRAEXEC1" 写进 MTK 的 SRAM ram_console 区
    CONFIG_MTK_RAM_CONSOLE_ADDR=0x0010DC00, SIZE=0x800
    偏移 0x600/0x604 写入，避开头部结构；下次开机 TWRP 里 /proc/last_kmsg 可读回

通道 2（依赖屏幕）：往 3 个候选显存基址各写一条 64 像素绿色横条
    0x771e0000（DTB 里的 mtkfb 基址）、0x77000000、0x77e00000
"""
import sys

ANCHOR = 'ENTRY(stext)\n'

_ins = [
    '\t/* DRAPIX2: SRAM 标记 + 多候选显存打点（MMU 关闭，物理写） */',
    '\tmov\tx9, #0x10dc',
    '\tlsl\tx9, x9, #8',
    '\tmov\tw10, #0x5244',
    '\tmovk\tw10, #0x4541, lsl #16',
    '\tstr\tw10, [x9, #0x600]',
    '\tmov\tw11, #0x4558',
    '\tmovk\tw11, #0x3143, lsl #16',
    '\tstr\tw11, [x9, #0x604]',
    '\tmov\tx12, #0x771e0000',
]
for _off in range(0, 128, 4):
    _ins.append('\tstr\tw10, [x12, #%d]' % (0x80 + _off))   # 0x45415244 醒目色
_ins.append('\tmov\tx12, #0x77000000')
for _off in range(0, 128, 4):
    _ins.append('\tstr\tw10, [x12, #%d]' % (0x80 + _off))
_ins.append('\tmov\tx12, #0x77e00000')
for _off in range(0, 128, 4):
    _ins.append('\tstr\tw10, [x12, #%d]' % (0x80 + _off))
INS = '\n'.join(_ins) + '\n'


def main():
    p = 'arch/arm64/kernel/head.S'
    s = open(p, encoding='utf8').read()
    if 'DRAPIX2' in s:
        print('[=] 已打过补丁')
        return
    if ANCHOR not in s:
        print('[!] 找不到 ENTRY(stext)')
        sys.exit(1)
    s = s.replace(ANCHOR, ANCHOR + INS, 1)
    open(p, 'w', encoding='utf8').write(s)
    lines = s.splitlines()
    for i, l in enumerate(lines):
        if 'DRAPIX2' in l:
            for j in range(i, min(i + 10, len(lines))):
                print('  %4d: %s' % (j + 1, lines[j]))
            break
    print('[+] 完成：SRAM 标记 DRAEXEC1 + 3 处显存打点')


if __name__ == '__main__':
    main()
