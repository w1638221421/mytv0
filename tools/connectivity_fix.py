#!/usr/bin/env python3
"""
Connectivity symbol fix (correct signature, NOT a CRC bypass).

The 2018 vendor modules (wmt_drv.ko etc.) demand the symbol
`connectivity_export_dump_thread_state(const char *name)`. The GPL source drop on
branch 'og' ships the show_stack symbol but not this dump_thread_state symbol, so
we add it with the exact signature the modules expect. The CRC gate verifies the
result matches.
"""
import sys

H = "drivers/misc/mediatek/connectivity/common/connectivity_build_in_adapter.h"
C = "drivers/misc/mediatek/connectivity/common/connectivity_build_in_adapter.c"

hs = open(H, encoding="utf-8", errors="ignore").read()
if "connectivity_export_dump_thread_state" not in hs:
    hs = hs.replace(
        "void connectivity_export_show_stack(struct task_struct *tsk, unsigned long *sp);",
        "void connectivity_export_show_stack(struct task_struct *tsk, unsigned long *sp);\n"
        "void connectivity_export_dump_thread_state(const char *name);",
    )
    open(H, "w", encoding="utf-8").write(hs)

cs = open(C, encoding="utf-8", errors="ignore").read()
if "connectivity_export_dump_thread_state(const char *name)" not in cs:
    fn = (
        "void connectivity_export_dump_thread_state(const char *name)\n"
        "{\n"
        "\tstruct task_struct *p;\n"
        "\n"
        "\tif (!name || strlen(name) > 255)\n"
        "\t\treturn;\n"
        "\n"
        "\trcu_read_lock();\n"
        "\tfor_each_process(p) {\n"
        "\t\tif (strncmp(p->comm, name, strlen(name)) == 0) {\n"
        "\t\t\tshow_stack(p, NULL);\n"
        "\t\t\tbreak;\n"
        "\t\t}\n"
        "\t}\n"
        "\trcu_read_unlock();\n"
        "}\n"
        "EXPORT_SYMBOL(connectivity_export_dump_thread_state);\n"
    )
    cs = cs.replace(
        "EXPORT_SYMBOL(connectivity_export_show_stack);",
        "EXPORT_SYMBOL(connectivity_export_show_stack);\n\n" + fn,
    )
    open(C, "w", encoding="utf-8").write(cs)

print("[+] connectivity signature ok")
