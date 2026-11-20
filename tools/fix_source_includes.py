#!/usr/bin/env python3
"""
Build-system repairs for vendor driver directories whose headers include each
other as "./include/xxx.h" (relative to the driver root) while their Makefile does
not add the matching -I flag.

Nothing here changes driver logic: it only supplies the include path the shipped
Makefile is missing, and relaxes the tree-wide -Werror for that directory so that a
2018-era vendor driver still compiles under this toolchain.

Usage: python fix_source_includes.py <kernel_root>
"""
import os
import sys

root = sys.argv[1] if len(sys.argv) > 1 else '.'

# dir -> extra flags to append (idempotent)
PATCHES = {
    'security/hw_root_scan': [
        'ccflags-y += -I$(srctree)/security/hw_root_scan',
        'ccflags-y += -Wno-error',
    ],
    'security/check_root': [
        'ccflags-y += -I$(srctree)/security/check_root',
        'ccflags-y += -Wno-error',
    ],
}

for rel, lines in PATCHES.items():
    mk = os.path.join(root, rel, 'Makefile')
    if not os.path.exists(mk):
        print(f'{rel}: no Makefile, skip')
        continue
    text = open(mk, encoding='utf-8', errors='surrogateescape').read()
    added = []
    for line in lines:
        key = line.split('+=', 1)[1].strip()
        if key in text:
            continue
        text += ('\n' if not text.endswith('\n') else '') + line + '\n'
        added.append(line)
    if added:
        open(mk, 'w', encoding='utf-8', errors='surrogateescape').write(text)
        print(f'{rel}: added {len(added)} flag(s) -> {" | ".join(added)}')
    else:
        print(f'{rel}: already patched')

print('source include fixes done')
