#!/usr/bin/env python3
"""
Generic build-system repair for the vendor driver trees.

MTK/Huawei driver code includes headers as if the driver root (and sometimes its
parents) were on the include path, while the shipped Makefiles never add those -I
flags. Rather than patching directories one failure at a time, walk the vendor
roots and give every object-building directory:

    -I<dir>  -I<dir>/include  -I<dir>/inc  -I<parent>  -I<grandparent>  -Wno-error

Extra include paths are harmless (they only resolve names that would otherwise not
be found) and no driver logic is modified.

Usage: python fix_source_includes.py <kernel_root>
"""
import os
import sys

root = sys.argv[1] if len(sys.argv) > 1 else '.'
ROOTS = [
    'drivers/devkit',
    'security',
    'drivers/mmc/host/mediatek',
    'drivers/watchdog/mediatek',
    'drivers/misc/mediatek',
]
CLIMB = 2          # how many parent levels to add as include paths
MARK = 'droidspaces_extra_includes'

added_flags = 0
patched = 0
visited = 0

for base in ROOTS:
    bpath = os.path.join(root, base)
    if not os.path.isdir(bpath):
        continue
    for dirpath, _dirnames, filenames in os.walk(bpath):
        if 'Makefile' not in filenames:
            continue
        visited += 1
        rel = os.path.relpath(dirpath, root).replace(os.sep, '/')
        try:
            text = open(os.path.join(dirpath, 'Makefile'), encoding='utf-8', errors='surrogateescape').read()
        except OSError:
            continue
        if MARK in text:
            continue
        if not any(l.strip().startswith(('obj-', 'obj-y', 'obj-m')) for l in text.splitlines()):
            continue

        paths = [rel, rel + '/include', rel + '/inc']
        parent = rel
        for _ in range(CLIMB):
            parent = os.path.dirname(parent).replace(os.sep, '/')
            if not parent or parent in ('.', ''):
                break
            paths.append(parent)

        flags = ' '.join(f'-I$(srctree)/{p}' for p in dict.fromkeys(paths))
        block = ('\n# --- added by Droidspaces build kit: resolve vendor "./..." includes ---\n'
                 f'{MARK} := {flags} -Wno-error\n'
                 'ccflags-y += $(' + MARK + ')\n')
        text += ('' if text.endswith('\n') else '\n') + block
        open(os.path.join(dirpath, 'Makefile'), 'w', encoding='utf-8', errors='surrogateescape').write(text)
        patched += 1
        added_flags += len(paths)

print(f'vendor Makefiles scanned: {visited}, patched: {patched}, include paths: {added_flags}')
