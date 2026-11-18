import sys, re
p = sys.argv[1]
s = open(p, encoding="utf8", errors="replace").read()
if "DRAPIX_FBTEXT" in s:
    print("[-] 已打过"); sys.exit(0)

code = r'''
/* ===================== DRAPIX_FBTEXT v4 =====================
 * 只画关键行；清屏用 memset_io（iomem 专用），不滚屏（满屏即清屏重画）。
 */
#include <linux/fb.h>
#include <linux/font.h>
#include <linux/io.h>

static struct fb_info *drfb;
static const struct font_desc *drfont;
static int dr_on, dr_cx, dr_cy, dr_cols, dr_rows;
static u32 dr_xres, dr_yres, dr_line, dr_bpp, dr_fw, dr_fh, dr_fbpr;

static const char *dr_keys[] = {
	"init:", "init ", "avc:", "adbd", "zygote", "Zygote",
	"system_server", "Binder", "vold", "panic", "BUG:",
	"Unable to", "error", "failed", "Kernel panic", "watchdog",
	"SurfaceFlinger", "audit", "DRAPIX"
};

static int dr_match(const char *t, size_t len)
{
	unsigned k, i;
	for (k = 0; k < sizeof(dr_keys)/sizeof(dr_keys[0]); k++) {
		const char *k2 = dr_keys[k];
		unsigned kl = 0, j;
		while (k2[kl]) kl++;
		if (kl > len) continue;
		for (i = 0; i + kl <= len; i++) {
			for (j = 0; j < kl; j++)
				if (t[i+j] != k2[j]) break;
			if (j == kl) return 1;
		}
	}
	return 0;
}

static void dr_px(u32 x, u32 y, u32 on)
{
	u32 off = y * dr_line + x * (dr_bpp >> 3);
	if (dr_bpp == 32) writel(on ? 0x00ffffff : 0, drfb->screen_base + off);
	else writew(on ? 0xffff : 0, drfb->screen_base + off);
}

static void dr_clear_all(void)
{
	u32 i;
	for (i = 0; i < dr_rows * dr_fh; i++)
		memset_io(drfb->screen_base + i * dr_line, 0, dr_line);
	dr_cx = 0; dr_cy = 0;
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
	dr_fw = drfont->width; dr_fh = drfont->height;
	dr_fbpr = (dr_fw + 7) / 8;
	dr_cols = dr_xres / dr_fw;
	dr_rows = dr_yres / dr_fh;
	if (dr_cols < 8 || dr_rows < 4) return;
	dr_on = 1;
	dr_clear_all();
}

static void dr_char(char c)
{
	const unsigned char *g;
	u32 i, j;
	if (c == '\r') return;
	if (c == '\n') dr_cx = dr_cols;
	else if (c >= 32 && c < 127) {
		g = drfont->data + (unsigned char)c * dr_fh * dr_fbpr;
		for (j = 0; j < dr_fh; j++)
			for (i = 0; i < dr_fw; i++)
				if (g[j * dr_fbpr + (i >> 3)] & (0x80 >> (i & 7)))
					dr_px(dr_cx * dr_fw + i, dr_cy * dr_fh + j, 1);
		dr_cx++;
	}
	if (dr_cx >= dr_cols) {
		dr_cx = 0;
		if (++dr_cy >= dr_rows) { dr_clear_all(); }   /* 满屏清屏重来，不滚屏 */
	}
}

static void dr_text(const char *t, size_t len)
{
	size_t i;
	if (!dr_on) dr_init();
	if (!dr_on || !t) return;
	if (!dr_match(t, len)) return;
	for (i = 0; i < len; i++) dr_char(t[i]);
}
/* ========================================================= */
'''

m = re.search(r"static void call_console_drivers\(", s)
if not m:
    print("[-] 找不到 call_console_drivers"); sys.exit(1)
s = s[:m.start()] + code + "\n" + s[m.start():]
anchor = "trace_console_rcuidle(text, len);"
k = s.find(anchor)
if k < 0:
    print("[-] 找不到插入点"); sys.exit(1)
ins = k + len(anchor)
s = s[:ins] + "\n\tdr_text(text, len);" + s[ins:]
open(p, "w", encoding="utf8", newline="\n").write(s)
print("[+] 关键行渲染器 v4（memset_io，不滚屏）注入成功")