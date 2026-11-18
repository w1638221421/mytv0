import sys
p = sys.argv[1]
s = open(p, encoding="utf8", errors="replace").read()
if "DRAPIX_FORCE_CONSOLE" in s:
    print("[-] 已打过"); sys.exit(0)
anchor = "int console_printk[4] = {"
i = s.find(anchor)
if i < 0:
    print("[-] 找不到 console_printk"); sys.exit(1)
# 插到 console_printk 定义之后（找到该数组结束的 }; ）
j = s.find("};", i)
if j < 0:
    print("[-] 找不到数组结尾"); sys.exit(1)
j += 2
inject = r'''

/* ================= DRAPIX_FORCE_CONSOLE =================
 * 强制打开控制台输出：LK 传 printk.disable_uart=1 会屏蔽主控制台，
 * 这里直接改内存变量，绕过 cmdline 解析的不确定性。
 * 同时把 console_loglevel 抬到 8，保证 init 的 kmsg 也能刷屏。
 */
static int __init drapix_force_console(void)
{
	printk_disable_uart = 2;
	console_printk[0] = 8;
	console_printk[1] = 8;
	printk("DRAPIX: console forced ON (disable_uart=2, loglevel=8)\n");
	return 0;
}
early_initcall(drapix_force_console);
/* ======================================================= */
'''
s = s[:j] + inject + s[j:]
open(p, "w", encoding="utf8", newline="\n").write(s)
print("[+] 已注入 force_console 到 printk.c")