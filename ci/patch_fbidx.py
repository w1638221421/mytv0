#!/usr/bin/env python3
# 在 do_one_initcall() 里插入打点：用 registered_fb[0]->screen_base 在屏幕左上角画 16 位二进制方块
# 每调用一个 initcall，方块数 +1；内核死在哪，屏幕就冻结在哪个序号
import re, sys

path = sys.argv[1] if len(sys.argv) > 1 else "init/main.c"
src = open(path, encoding="utf8", errors="replace").read()

helper = r'''
/* ===== DRAPIX 打点：直接画到内核已注册的 framebuffer ===== */
#include <linux/fb.h>
static void drak_fill(struct fb_info *fi, int x, int y, int w, int h, unsigned int c)
{
	unsigned int *p = (unsigned int *)((char *)fi->screen_base);
	int i, j;
	if (!p) return;
	for (j = 0; j < h; j++)
		for (i = 0; i < w; i++)
			p[(y + j) * fi->var.xres + (x + i)] = c;
}
void drapix_index(unsigned int n)
{
	struct fb_info *fi;
	int i, b, x, y;
	int W_MARGIN;
	if (FB_MAX < 1) return;
	fi = registered_fb[0];
	if (!fi || !fi->screen_base || fi->var.xres < 480) return;
	W_MARGIN = fi->var.xres - 8 * 46 - 8;
	for (i = 0; i < 16; i++) {
		b = (n >> i) & 1;
		x = W_MARGIN + (i % 8) * 46;
		y = 8 + (i / 8) * 46;
		/* 先画黑底边框再用白/黑填心，保证在亮/暗背景下都能看出来 */
		drak_fill(fi, x - 3, y - 3, 46, 46, 0x00202020);
		drak_fill(fi, x, y, 40, 40, b ? 0x00ffffff : 0x00000000);
	}
}
unsigned int drapix_count;
/* ===== DRAPIX end ===== */
'''

anchor = "#include <linux/init.h>"
if anchor not in src:
    sys.exit("找不到 include 锚点")
src = src.replace(anchor, anchor + helper, 1)

# 在 aee_rr_rec_last_init_func 之后插入计数打点
n = 0
def ins(m):
    global n
    n += 1
    return m.group(0) + "\n\tdrapix_index(drapix_count++);"

pat = re.compile(r"aee_rr_rec_last_init_func\(\(unsigned long\)fn\);")
src, cnt = pat.subn(lambda m: m.group(0) + "\n\tdrapix_index(drapix_count++);", src)
print("插入打点处数:", cnt)

# do_one_initcall 若带 __init 会 section mismatch，去掉
src = src.replace("int __init_or_module do_one_initcall", "int do_one_initcall")
src = re.sub(r"\bint __init do_one_initcall", "int do_one_initcall", src)

open(path, "w", encoding="utf8").write(src)
print("[+] patched", path)
