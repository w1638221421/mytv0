#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
patch_initidx.py -- 在 do_one_initcall() 里把"当前 initcall 序号"画到屏幕顶部
  屏幕左上角：11 个 8x8 方块 = 二进制表示当前序号（bit0 在最左）
  白色(0x00ffffff)=1，黑色=0
  另外在 y=16 画一个 8x8 方块表示 initcall level（二进制 3 位，bit0 最左）
这样一张照片就能读出"内核执行到第几个 initcall"，再配合 System.map 定位函数。
"""
import sys

HELPER = r"""
/* ---------- DRAPIX-IDX: 把 initcall 序号画到 framebuffer ---------- */
#include <linux/io.h>
#include <asm/memory.h>

static void drapix_block(int x0, int y0, unsigned int color, int size)
{
	void __iomem *fb = (void __iomem *)__va(0x771e0000UL);
	int x, y;
	for (y = 0; y < size; y++) {
		void __iomem *row = fb + (unsigned long)((y0 + y) * 720 + x0) * 4UL;
		for (x = 0; x < size; x++)
			writel(color, row + (unsigned long)x * 4UL);
	}
}

static void drapix_index(int idx)
{
	int i;
	unsigned int white = 0x00ffffffu, black = 0x00000000u;
	if (idx < 0)
		return;
	/* bit0 在最左：11 个方块 */
	for (i = 0; i < 11; i++)
		drapix_block(8 + i * 16, 8, (idx & (1 << i)) ? white : black, 8);
}

static int drapix_count;
"""

def main():
    p = 'init/main.c'
    s = open(p, encoding='utf8').read()
    if 'DRAPIX-IDX' in s:
        print('[=] 已打过补丁'); return
    i = s.index('#include')
    s = s[:i] + HELPER + "\n" + s[i:]

    anchor = "\taee_rr_rec_last_init_func((unsigned long)fn);\n"
    if anchor not in s:
        print('[!] 找不到 aee_rr_rec_last_init_func 锚点'); sys.exit(1)
    s = s.replace(anchor, anchor + "\tdrapix_index(drapix_count++);\n", 1)

    open(p, 'w', encoding='utf8').write(s)
    lines = s.splitlines()
    for i, l in enumerate(lines):
        if 'drapix_index(0' in l:
            for j in range(max(0, i-4), min(i+3, len(lines))):
                print('  %5d: %s' % (j+1, lines[j]))
            break

if __name__ == '__main__':
    main()
