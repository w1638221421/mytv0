import sys, re
p = sys.argv[1]
s = open(p, encoding="utf8", errors="replace").read()
if "DRAPIX_FBTEXT" in s:
    print("[-] 已打过"); sys.exit(0)

code = r'''
/* ===================== DRAPIX_FBTEXT =====================
 * 把内核日志逐字渲染到 framebuffer（完全绕开 console / VT 子系统）
 */
#include <linux/fb.h>
#include <linux/font.h>
#include <linux/io.h>

static struct fb_info *drfb;
static const struct font_desc *drfont;
static int dr_on, dr_cx, dr_cy, dr_cols, dr_rows;
static u32 dr_xres, dr_yres, dr_line, dr_bpp;

static void dr_px(u32 x, u32 y, u32 on)
{
	void __iomem *base = drfb->screen_base;
	u32 off = y * dr_line + x * (dr_bpp >> 3);
	if (dr_bpp == 32)
		writel(on ? 0x00ffffff : 0, base + off);
	else
		writew(on ? 0xffff : 0, base + off);
}

static void dr_clear_rows(int from, int n)
{
	int j; u32 i;
	for (j = 0; j < n * drfont->height; j++)
		for (i = 0; i < dr_xres; i++)
			dr_px(i, from * drfont->height + j, 0);
}

static void dr_init(void)
{
	if (dr_on) return;
	if (!registered_fb[0]) return;
	drfb = registered_fb[0];
	if (!drfb->screen_base) return;
	drfont = find_font("VGA8x16");
	if (!drfont) return;
	dr_xres = drfb->var.xres;
	dr_yres = drfb->var.yres;
	dr_line = drfb->fix.line_length;
	dr_bpp  = drfb->var.bits_per_pixel;
	if (dr_bpp != 16 && dr_bpp != 32) return;
	if (!dr_line || !dr_xres || !dr_yres) return;
	dr_cols = dr_xres / drfont->width;
	dr_rows = dr_yres / drfont->height;
	if (dr_cols < 8 || dr_rows < 4) return;
	dr_cx = 0; dr_cy = 0;
	dr_clear_rows(0, dr_rows);
	dr_on = 1;
}

static void dr_scroll(void)
{
	u32 h = drfont->height;
	memmove(drfb->screen_base,
		drfb->screen_base + dr_line * h,
		dr_line * h * (dr_rows - 1));
	dr_clear_rows(dr_rows - 1, 1);
}

static void dr_char(char c)
{
	const unsigned char *g;
	int i, j, w = drfont->width, h = drfont->height;
	int bpr = (w + 7) / 8;
	if (c == '\r') return;
	if (c == '\n') {
		dr_cx = dr_cols;
	} else if (c >= 32 && c < 127) {
		g = drfont->data + (unsigned char)c * h * bpr;
		for (j = 0; j < h; j++)
			for (i = 0; i < w; i++)
				if (g[j * bpr + (i >> 3)] & (0x80 >> (i & 7)))
					dr_px(dr_cx * w + i, dr_cy * h + j, 1);
		dr_cx++;
	}
	if (dr_cx >= dr_cols) {
		dr_cx = 0;
		if (++dr_cy >= dr_rows) { dr_scroll(); dr_cy = dr_rows - 1; }
	}
}

static void dr_text(const char *t, size_t len)
{
	size_t i;
	if (!dr_on) dr_init();
	if (!dr_on || !t) return;
	for (i = 0; i < len; i++) dr_char(t[i]);
}
/* ========================================================= */
'''

m = re.search(r"static void call_console_drivers\(", s)
if not m:
    print("[-] 找不到 call_console_drivers 定义"); sys.exit(1)
s = s[:m.start()] + code + "\n" + s[m.start():]
m = re.search(r"static void call_console_drivers\([^)]*\)\s*\{", s)
if not m:
    print("[-] 找不到函数体起始"); sys.exit(1)
ins = m.end()
s = s[:ins] + "\n\tdr_text(text, len);" + s[ins:]
open(p, "w", encoding="utf8", newline="\n").write(s)
if "dr_text(text, len)" in s:
    print("[+] FB 文字渲染注入成功")
else:
    print("[!] 注入失败"); sys.exit(1)