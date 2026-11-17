#!/usr/bin/env python3
# 让 dsm_earlyboot_client_init() 不再注册 DSM 客户端（怀疑卡在 dsm_register_client）
import sys, re, os

root = sys.argv[1] if len(sys.argv) > 1 else "."
path = os.path.join(root, "drivers/dsm/dsm_earlyboot_client/dsm_earlyboot_client.c")
if not os.path.exists(path):
    sys.exit("找不到 " + path)

src = open(path, encoding="utf8", errors="replace").read()
orig = "dsm_register_client(&public_client_devices[i]);"
if orig not in src:
    sys.exit("找不到注册调用")

src = src.replace(orig, "/* DRAPIX: 跳过 DSM 注册，规避早期启动卡死 */ (void)public_client_devices[i];", 1)
open(path, "w", encoding="utf8").write(src)
print("[+] 已把 dsm_register_client 调用改为空操作:", path)
