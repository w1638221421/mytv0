import sys, re
p = sys.argv[1]
s = open(p, encoding="utf8", errors="replace").read()
if "DRAPIX_NOTTYMT" in s:
    print("[-] 已打过"); sys.exit(0)
anchor = "for_each_console(con) {"
i = s.find(anchor)
if i < 0:
    print("[-] 找不到 for_each_console"); sys.exit(1)
ins = i + len(anchor)
add = "\n\t\t/* DRAPIX_NOTTYMT: 硬性跳过串口控制台，避免 921600 波特拖死系统 */\n\t\tif (con->name && !strncmp(con->name, \"ttyMT\", 5))\n\t\t\tcontinue;"
s = s[:ins] + add + s[ins:]
open(p, "w", encoding="utf8", newline="\n").write(s)
print("[+] 已插入 ttyMT 跳过逻辑")