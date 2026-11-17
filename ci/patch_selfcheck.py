#!/usr/bin/env python3
# 自我验证补丁：
#   1) 每个 initcall 画 16 位大方块到 registered_fb[0]（左上角）
#   2) 每个 initcall printk 一行 DI<序号> <符号>
#   3) 第 150 个 initcall 故意死循环 —— 用来验证通道是否可见
import re, sys

path = sys.argv[1] if len(sys.argv) > 1 else "init/main.c"
src = open(path, encoding="utf8", errors="replace").read()

helper = r'''
/* ===== DRAPIX 自我验证打点 ===== */
#include <linux/fb.h>
#include <linux/kallsyms.h>
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
	if (FB_MAX < 1) return;
	fi = registered_fb[0];
	if (!fi || !fi->screen_base || fi->var.xres < 480) return;
	for (i = 0; i < 16; i++) {
		b = (n >> i) & 1;
		x = 6 + (i % 8) * 56;
		y = 6 + (i / 8) * 56;
		drak_fill(fi, x - 3, y - 3, 56, 56, 0x00202020);
		drak_fill(fi, x, y, 50, 50, b ? 0x00ffffff : 0x00000000);
	}
}
unsigned int drapix_count;
/* ===== end ===== */
'''

anchor = "#include <linux/init.h>"
if anchor not in src:
    sys.exit("找不到 include 锚点")
src = src.replace(anchor, anchor + helper, 1)

inject = (
    "\n\tdrapix_index(drapix_count);"
    "\n\tprintk(KERN_EMERG \"DI%u %pS\\n\", drapix_count, (void *)fn);"
    "\n\tif (drapix_count++ == 150) { printk(KERN_EMERG \"DRAPIX HANGPOINT 150\\n\"); while (1) cpu_relax(); }"
)
pat = re.compile(r"aee_rr_rec_last_init_func\(\(unsigned long\)fn\);")
src, cnt = pat.subn(lambda m: m.group(0) + inject, src)
print("插入处数:", cnt)

src = src.replace("int __init_or_module do_one_initcall", "int do_one_initcall")

open(path, "w", encoding="utf8").write(src)
print("[+] patched", path)
