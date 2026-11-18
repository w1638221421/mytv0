import sys, re
p = sys.argv[1]
s = open(p, encoding="utf8", errors="replace").read()
if "DRAPIX_FBTEXT" in s:
    print("[-] 已打过"); sys.exit(0)

code = r'''
/* ===================== DRAPIX_FBTEXT v3 =====================
 * 只渲染"关键行"（init/avc/adbd/zygote/system_server/Binder/vold/panic/error...），
 * 其余内核日志一律不画 —— 避免日志渲染拖慢启动，同时保留用户态进展。
 */
#include <linux/fb.h>
#include <linux/font.h>
#include <linux/io.h>

static struct fb_info *drfb;
static const struct font_desc *drfont;
static int dr_on, dr_cx, dr_cy, dr_cols, dr_rows;
static u32 dr_xres, dr_yres, dr_line, dr_bpp, dr_fw, dr_fh, dr_fbpr;

static const char *dr_keys[] = {
	"init:", "init ", " avc:", "adbd", "zygote", "Zygote",
	"system_server", "Binder", "vold", "panic", "BUG:",
	"Unable to", "error", "failed", "Kernel panic", "watchdog",
	"surfaceflinger", "SurfaceFlinger", "audit", "DRAPIX"
};

static int dr_match(const char *t, size_t len)
{
	int k, i;
	for (k = 0; k < (int)(sizeof(dr_keys)/sizeof(dr_keys[0])); k++) {
		const char *k2 = dr_keys[k];
		int kl = 0, j, ok;
		while (k2[kl]) kl++;
		if (kl > (int)len) continue;
		for (i = 0; i + kl <= (int)len; i++) {
			ok = 1;
			for (j = 0; j < kl; j++)
				if (t[i+j] != k2[j]) { ok = 0; break; }
			if (ok) return 1;
		}
	}
	return 0;
}

static void dr_px(u32 x, u32 y, u32 on)
{
	u32 off = y * dr_line + x * (dr_bpp >> 3);
	void __iomem *p = drfb->screen_base + off;
	if (dr_bpp == 32) writel(on ? 0x00ffffff : 0, p);
	else writew(on ? 0xffff : 0, p);
}

static void dr_clear_line(u32 y0, u32 h)
{
	u32 i;
	for (i = 0; i < h; i++)
		memset(drfb->screen_base + (y0 + i) * dr_line, 0, dr_line);
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
	dr_cx = 0; dr_cy = 0;
	dr_clear_line(0, dr_rows * dr_fh);
	dr_on = 1;
}

static void dr_scroll(void)
{
	u32 h = dr_fh;
	memmove(drfb->screen_base, drfb->screen_base + dr_line * h,
		dr_line * h * (dr_rows - 1));
	dr_clear_line((dr_rows - 1) * h, h);
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
		if (++dr_cy >= dr_rows) { dr_scroll(); dr_cy = dr_rows - 1; }
	}
}

static void dr_text(const char *t, size_t len)
{
	size_t i;
	if (!dr_on) dr_init();
	if (!dr_on || !t) return;
	if (!dr_match(t, len)) return;      /* 只画关键行 */
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
print("[+] 关键行渲染器 v3 注入成功")