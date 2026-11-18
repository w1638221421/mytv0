import sys, re

p = sys.argv[1]
s = open(p, encoding="utf8", errors="replace").read()
if "DRAPIX_DUMP" in s:
    print("[-] 已打过")
    sys.exit(0)

code = r'''
/* ===================== DRAPIX_DUMP v2 =====================
 * 每 10 秒把内核日志里"关键行"（init/avc/adbd/zygote/...）的最后 26 行
 * 一次性画到 framebuffer。不逐条渲染，不影响启动速度。
 */
#include <linux/fb.h>
#include <linux/font.h>
#include <linux/io.h>

static struct fb_info *dra_fb;
static const struct font_desc *dra_font;
static int dra_ok, dra_cx, dra_cy, dra_cols, dra_rows;
static u32 dra_line, dra_bpp, dra_fw, dra_fh, dra_bpr;
static char dra_tail[2048];

static const char *dra_keys[] = {
	"init:", "init ", "avc:", "adbd", "zygote", "Zygote",
	"system_server", "Binder", "vold", "panic", "BUG:",
	"Unable to", "failed", "error", "Kernel panic",
	"SurfaceFlinger", "audit", "DRAPIX", "SELinux", "selinux"
};

static int dra_match(const char *t, int len)
{
	unsigned k;
	int i, j;
	for (k = 0; k < sizeof(dra_keys) / sizeof(dra_keys[0]); k++) {
		const char *k2 = dra_keys[k];
		int kl = 0;
		while (k2[kl])
			kl++;
		if (kl > len)
			continue;
		for (i = 0; i + kl <= len; i++) {
			for (j = 0; j < kl; j++)
				if (t[i + j] != k2[j])
					break;
			if (j == kl)
				return 1;
		}
	}
	return 0;
}

static void dra_px(u32 x, u32 y)
{
	u32 off = y * dra_line + x * (dra_bpp >> 3);
	if (dra_bpp == 32)
		writel(0x00ffffff, dra_fb->screen_base + off);
	else
		writew(0xffff, dra_fb->screen_base + off);
}

static void dra_clear(void)
{
	u32 i;
	for (i = 0; i < dra_rows * dra_fh; i++)
		memset_io(dra_fb->screen_base + i * dra_line, 0, dra_line);
	dra_cx = 0;
	dra_cy = 0;
}

static int dra_init(void)
{
	if (dra_ok)
		return 1;
	if (!registered_fb[0])
		return 0;
	dra_fb = registered_fb[0];
	if (!dra_fb->screen_base)
		return 0;
	dra_font = find_font("VGA8x16");
	if (!dra_font)
		return 0;
	dra_line = dra_fb->fix.line_length;
	dra_bpp = dra_fb->var.bits_per_pixel;
	if ((dra_bpp != 16 && dra_bpp != 32) || !dra_line)
		return 0;
	dra_fw = dra_font->width;
	dra_fh = dra_font->height;
	dra_bpr = (dra_fw + 7) / 8;
	dra_cols = dra_fb->var.xres / dra_fw;
	dra_rows = dra_fb->var.yres / dra_fh;
	if (dra_cols < 8 || dra_rows < 4)
		return 0;
	dra_ok = 1;
	return 1;
}

static void dra_char(char c)
{
	const unsigned char *g;
	u32 i, j;

	if (c == '\r')
		return;
	if (c == '\n') {
		dra_cx = dra_cols;
	} else if (c >= 32 && c < 127) {
		g = dra_font->data + (unsigned char)c * dra_fh * dra_bpr;
		for (j = 0; j < dra_fh; j++)
			for (i = 0; i < dra_fw; i++)
				if (g[j * dra_bpr + (i >> 3)] & (0x80 >> (i & 7)))
					dra_px(dra_cx * dra_fw + i, dra_cy * dra_fh + j);
		dra_cx++;
	}
	if (dra_cx >= dra_cols) {
		dra_cx = 0;
		if (++dra_cy >= dra_rows)
			dra_cy = 0;
	}
}

static void dra_puts(const char *q)
{
	while (*q)
		dra_char(*q++);
}

static void dra_dump(void)
{
	unsigned long flags;
	u32 idx;
	int tl = 0, i;
	char hdr[56];

	if (!dra_init())
		return;
	if (!raw_spin_trylock_irqsave(&logbuf_lock, flags))
		return;
	idx = log_first_idx;
	while (idx != log_next_idx) {
		struct printk_log *msg = log_from_idx(idx);
		const char *t = log_text(msg);
		int len = msg->text_len;

		if (dra_match(t, len)) {
			int k;
			for (k = 0; k < len; k++) {
				if (tl >= (int)sizeof(dra_tail) - 1)
					break;
				dra_tail[tl++] = t[k];
			}
			if (tl < (int)sizeof(dra_tail) - 1)
				dra_tail[tl++] = '\n';
			if (tl >= (int)sizeof(dra_tail) - 1) {
				memmove(dra_tail, dra_tail + 900, tl - 900);
				tl -= 900;
			}
		}
		idx = log_next(idx);
	}
	raw_spin_unlock_irqrestore(&logbuf_lock, flags);

	if (tl <= 0)
		return;
	dra_tail[tl] = 0;

	dra_clear();
	snprintf(hdr, sizeof(hdr), "== DRAPIX KEYLOG uptime=%us ==", (unsigned)(jiffies / HZ));
	dra_puts(hdr);
	dra_char('\n');
	for (i = 0; i < tl; i++)
		dra_char(dra_tail[i]);
}

static struct timer_list dra_timer;

static void dra_tick(unsigned long data)
{
	dra_dump();
	dra_timer.expires = jiffies + 10 * HZ;
	add_timer(&dra_timer);
}

static int __init dra_timer_init(void)
{
	init_timer(&dra_timer);
	dra_timer.function = dra_tick;
	dra_timer.data = 0;
	dra_timer.expires = jiffies + 12 * HZ;
	add_timer(&dra_timer);
	return 0;
}
late_initcall(dra_timer_init);
/* ========================================================= */
'''

m = re.search(r"static void call_console_drivers\(", s)
if not m:
    print("[-] 找不到 call_console_drivers")
    sys.exit(1)
s = s[:m.start()] + code + "\n" + s[m.start():]
open(p, "w", encoding="utf8", newline="\n").write(s)
print("[+] DRAPIX_DUMP v2（关键行过滤）注入成功")
