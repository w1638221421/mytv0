#!/usr/bin/env python3
# 修正版 FB 打点：用 fix.line_length(真实 pitch) + var.bits_per_pixel，避免斜条纹
import re, sys

path = sys.argv[1] if len(sys.argv) > 1 else "init/main.c"
src = open(path, encoding="utf8", errors="replace").read()

helper = r'''
/* ===== DRAPIX FB 打点（stride 正确版） ===== */
#include <linux/fb.h>
#include <linux/io.h>
static void drak_px(struct fb_info *fi, int x, int y, unsigned int c)
{
	char __iomem *base = fi->screen_base;
	int bpp = fi->var.bits_per_pixel;
	unsigned long off;

	if (!base) return;
	if (x < 0 || y < 0 || x >= fi->var.xres || y >= fi->var.yres) return;
	off = (unsigned long)y * fi->fix.line_length + (unsigned long)x * (bpp >> 3);
	if (bpp == 32)
		writel(c, base + off);
	else if (bpp == 16)
		writew((unsigned short)c, base + off);
	else if (bpp == 24) {
		writeb(c & 0xff, base + off);
		writeb((c >> 8) & 0xff, base + off + 1);
		writeb((c >> 16) & 0xff, base + off + 2);
	}
}
static void drak_box(struct fb_info *fi, int x, int y, int w, int h, unsigned int c)
{
	int i, j;
	for (j = 0; j < h; j++)
		for (i = 0; i < w; i++)
			drak_px(fi, x + i, y + j, c);
}
void drapix_index(unsigned int n)
{
	struct fb_info *fi;
	int i, b, x, y;

	if (FB_MAX < 1) return;
	fi = registered_fb[0];
	if (!fi || !fi->screen_base || !fi->fix.line_length) return;
	if (fi->var.xres < 480 || fi->var.bits_per_pixel < 16) return;
	for (i = 0; i < 16; i++) {
		b = (n >> i) & 1;
		x = 12 + i * 44;
		y = 60;
		drak_box(fi, x - 3, y - 3, 40, 40, 0x0000ff00);   /* 亮绿边框：0/1 都能看见 */
		drak_box(fi, x, y, 34, 34, b ? 0x00ffffff : 0x00101010);
	}
}
unsigned int drapix_count;
/* ===== end ===== */
'''

anchor = "#include <linux/init.h>"
if anchor not in src:
    sys.exit("找不到 include 锚点")
src = src.replace(anchor, anchor + helper, 1)

pat = re.compile(r"aee_rr_rec_last_init_func\(\(unsigned long\)fn\);")
src, cnt = pat.subn(lambda m: m.group(0) + "\n\tdrapix_index(drapix_count++);", src)
print("插入处数:", cnt)

src = src.replace("int __init_or_module do_one_initcall", "int do_one_initcall")
open(path, "w", encoding="utf8").write(src)
print("[+] patched", path)
