#!/usr/bin/env python3
"""
Generic build-system repair for the vendor driver trees.

Several Huawei/MTK driver directories include headers that live in the driver root
or in <driver>/include, while their Makefile supplies no matching -I. Instead of
patching directories one by one, walk the known vendor roots and make every
directory that builds objects self-including:

    ccflags-y += -I$(srctree)/<dir> -I$(srctree)/<dir>/include -Wno-error

Extra -I entries are harmless; -Wno-error only keeps 2018-era vendor code from
failing on warnings produced by a newer toolchain. No driver logic is touched.

Usage: python fix_source_includes.py <kernel_root>
"""
import os
import sys

root = sys.argv[1] if len(sys.argv) > 1 else '.'
ROOTS = ['drivers/devkit', 'security', 'drivers/misc/mediatek/base', 'drivers/huawei']

FLAGS = ['-I$(srctree)/{d}', '-I$(srctree)/{d}/include', '-Wno-error']
MARK = 'ccflags-y += $(droidspaces_extra_includes)'
patched = 0
visited = 0

for base in ROOTS:
    bpath = os.path.join(root, base)
    if not os.path.isdir(bpath):
        continue
    for dirpath, dirnames, filenames in os.walk(bpath):
        if 'Makefile' not in filenames:
            continue
        visited += 1
        rel = os.path.relpath(dirpath, root)
        mk = os.path.join(dirpath, 'Makefile')
        try:
            text = open(mk, encoding='utf-8', errors='surrogateescape').read()
        except OSError:
            continue
        if 'droidspaces_extra_includes' in text:
            continue
        # only touch dirs that actually build something
        if not any(l.strip().startswith(('obj-', 'obj-y', 'obj-m')) for l in text.splitlines()):
            continue
        block = ['', '# --- added by Droidspaces build kit: make "./..." includes resolvable ---',
                 'droidspaces_extra_includes := ' + ' '.join(f.format(d=rel) for f in FLAGS),
                 'ccflags-y += $(droidspaces_extra_includes)']
        text += ('\n' if not text.endswith('\n') else '') + '\n'.join(block) + '\n'
        open(mk, 'w', encoding='utf-8', errors='surrogateescape').write(text)
        patched += 1

print(f'vendor Makefiles scanned: {visited}, patched: {patched}')
