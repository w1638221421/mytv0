#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
patch_pixel.py -- 在 init/main.c 里注入 "像素进度条"，用于无控制台时定位卡死阶段
必须在 kernel 源码根目录下执行：python3 patch_pixel.py

屏幕左上角会依次出现绿点（每点间隔 64 字节 = 屏幕顶部一行内），点的数量代表内核走到了第几步：
  1 = mm_init 之后
  2 = kernel_init_freeable 入口
  3 = do_pre_smp_initcalls 之后
  4 = smp_init 之后
  5 = security_init 之后
  6 = do_basic_setup 入口
  7+level = do_initcalls 每个 level 之前（early=7, core=8, postcore=9, arch=10, subsys=11, fs=12, device=13, late=14）
  15 = 即将 exec /init
"""
import sys

HELPER = r"""
/* ---------- DRAPIX：直接写 framebuffer 当进度条 ---------- */
#include <linux/io.h>
#include <asm/io.h>
static void __init drapix(int n)
{
	void __iomem *p;
	if (n <= 0 || n > 32)
		return;
	p = ioremap(0x771e0000UL, 0x4000);
	if (!p)
		return;
	writel(0x0000ff00u, p + (unsigned long)(n * 64));
	wmb();
	iounmap(p);
}
"""

def main():
    p = 'init/main.c'
    s = open(p, encoding='utf8').read()
    if 'drapix' in s:
        print('[=] 已经打过补丁，跳过')
        return

    i = s.index('#include')
    s = s[:i] + HELPER + "\n" + s[i:]

    def put(anchor, expr, before=True):
        nonlocal s
        if anchor not in s:
            print('[!] anchor 未找到，跳过: %r' % anchor[:50])
            return False
        ins = '\tdrapix(%s);\n' % expr
        s = s.replace(anchor, (ins + anchor) if before else (anchor + ins), 1)
        return True

    n_ok = 0
    n_ok += put("\tmm_init();\n", "1", before=False)
    n_ok += put("static noinline void __init kernel_init_freeable(void)\n{\n", "2", before=False)
    n_ok += put("\tdo_pre_smp_initcalls();\n", "3", before=False)
    n_ok += put("\tsmp_init();\n", "4", before=False)
    n_ok += put("\tsecurity_init();\n", "5", before=False)
    n_ok += put("static void __init do_basic_setup(void)\n{\n", "6", before=False)
    n_ok += put("\t\tdo_initcall_level(level);\n", "7 + level", before=True)
    n_ok += put("\tret = run_init_process(ramdisk_execute_command);\n", "15", before=True)

    open(p, 'w', encoding='utf8').write(s)
    print('[+] 注入完成，成功 %d/8 处' % n_ok)
    if n_ok < 6:
        print('[!] 成功点太少，可能源码结构不符')
        sys.exit(1)

if __name__ == '__main__':
    main()
