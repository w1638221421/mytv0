#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
patch_pixel2.py -- 极早期像素标记：用线性映射物理地址写 framebuffer（不需要 ioremap/vmalloc）
所以能在 start_kernel 的第一行就画点，用来回答"内核到底有没有被执行"。

屏幕顶部从左到右的点（每点间隔 64 字节）：
  红点 1 = start_kernel 入口（第一行）
  红点 2 = setup_arch 入口
  红点 3 = setup_arch 之后
  绿点 4 = mm_init 之后
  绿点 5 = sched_init 之后
  绿点 6 = console_init 之后
  绿点 7 = kernel_init_freeable 入口
  蓝点 8 = smp_init 之后
  蓝点 9 = do_basic_setup 入口
  蓝点 10..17 = do_initcalls 每个 level（early/core/postcore/arch/subsys/fs/device/late）
  黄点 18 = 即将 exec /init
"""
import sys

HELPER = r"""
/* ---------- DRAPIX2：极早期像素标记（线性映射，不依赖 ioremap） ---------- */
#include <asm/memory.h>
#include <asm/barrier.h>
static inline void drapix2(int n, unsigned int color)
{
	if (n <= 0 || n > 32)
		return;
	*((volatile unsigned int *)__va(0x771e0000UL) + (unsigned long)(n * 16)) = color;
	dsb(sy);
}
#define DRAPIX_PT(n) drapix2((n), 0x000000ffu)   /* 红 */
#define DRAPIX_GR(n) drapix2((n), 0x0000ff00u)   /* 绿 */
#define DRAPIX_BL(n) drapix2((n), 0x00ff0000u)   /* 蓝 */
#define DRAPIX_YL(n) drapix2((n), 0x0000ffffu)   /* 黄 */
"""

def main():
    p = 'init/main.c'
    s = open(p, encoding='utf8').read()
    if 'drapix2' in s:
        print('[=] 已打过补丁'); return

    i = s.index('#include')
    s = s[:i] + HELPER + "\n" + s[i:]

    def put(anchor, expr, before=True):
        nonlocal s
        if anchor not in s:
            print('[!] anchor 未找到: %r' % anchor[:50]); return False
        ins = '\t%s;\n' % expr
        s = s.replace(anchor, (ins + anchor) if before else (anchor + ins), 1)
        return True

    ok = 0
    # start_kernel 第一行
    ok += put("asmlinkage __visible void __init start_kernel(void)\n{\n", "DRAPIX_PT(1)", before=False)
    # setup_arch 入口 / 之后
    ok += put("\tsetup_arch(&command_line);\n", "DRAPIX_PT(2)")
    ok += put("\tsetup_arch(&command_line);\n", "DRAPIX_PT(3)", before=False)
    ok += put("\tmm_init();\n", "DRAPIX_GR(4)", before=False)
    ok += put("\tsched_init();\n", "DRAPIX_GR(5)", before=False)
    ok += put("\tconsole_init();\n", "DRAPIX_GR(6)", before=False)
    ok += put("static noinline void __init kernel_init_freeable(void)\n{\n", "DRAPIX_GR(7)", before=False)
    ok += put("\tsmp_init();\n", "DRAPIX_BL(8)", before=False)
    ok += put("static void __init do_basic_setup(void)\n{\n", "DRAPIX_BL(9)", before=False)
    ok += put("\t\tdo_initcall_level(level);\n", "DRAPIX_BL(10 + level)")
    ok += put("\tret = run_init_process(ramdisk_execute_command);\n", "DRAPIX_YL(18)")

    open(p, 'w', encoding='utf8').write(s)
    print('[+] 注入 %d 处' % ok)
    if ok < 8:
        sys.exit(1)

if __name__ == '__main__':
    main()
