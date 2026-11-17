#!/usr/bin/env python3
# 在每个 do_one_initcall() 处 printk 一行：序号 + 函数符号名
# 配合 console=tty0 + fbcon，屏幕会冻结在最后一个驱动
import re, sys

path = sys.argv[1] if len(sys.argv) > 1 else "init/main.c"
src = open(path, encoding="utf8", errors="replace").read()

anchor = "#include <linux/init.h>"
if anchor not in src:
    sys.exit("找不到 include 锚点")
src = src.replace(anchor, anchor + "\n#include <linux/kallsyms.h>\nstatic unsigned int drapix_n;\n", 1)

pat = re.compile(r"aee_rr_rec_last_init_func\(\(unsigned long\)fn\);")
src, cnt = pat.subn(
    lambda m: m.group(0) +
    '\n\tprintk(KERN_EMERG "DI%u %pS\\n", drapix_n++, (void *)fn);',
    src)
print("插入 printk 处数:", cnt)

src = src.replace("int __init_or_module do_one_initcall", "int do_one_initcall")

open(path, "w", encoding="utf8").write(src)
print("[+] patched", path)
