#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
patch_head.py -- 在 arch/arm64/kernel/head.S 的 stext 第一行插入"极早期打点"

MMU 还没开，直接写物理地址 0x771e0000（显存基址，LK 已初始化 DDR 与 DDP）。
屏幕上此时是 LK 的黄色提示页，所以：
  - 若屏幕顶部左上角出现一条黄色横条(64 像素)  → 内核确实被执行了
  - 若什么都没有                                → LK 根本没有跳转到我们的内核
"""
import sys

ANCHOR = 'ENTRY(stext)\n'

_ins = [
    '\t/* DRAPIX: 极早期打点（MMU 关闭，物理写 fb 0x771e0000） */',
    '\tmov\tx9, #0x771e0000',
    '\tmov\tw10, #0x00ff00',
]
for _off in range(0, 256, 4):
    _ins.append('\tstr\tw10, [x9, #%d]' % (0x80 + _off))
INS = '\n'.join(_ins) + '\n'


def main():
    p = 'arch/arm64/kernel/head.S'
    s = open(p, encoding='utf8').read()
    if 'DRAPIX' in s:
        print('[=] 已打过补丁')
        return
    if ANCHOR not in s:
        print('[!] 找不到 ENTRY(stext)')
        sys.exit(1)
    s = s.replace(ANCHOR, ANCHOR + INS, 1)
    open(p, 'w', encoding='utf8').write(s)
    lines = s.splitlines()
    for i, l in enumerate(lines):
        if 'DRAPIX' in l:
            for j in range(i, min(i + 8, len(lines))):
                print('  %4d: %s' % (j + 1, lines[j]))
            break
    print('[+] 共写入 %d 个 str' % (256 // 4))


if __name__ == '__main__':
    main()
