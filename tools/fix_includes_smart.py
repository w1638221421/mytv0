#!/usr/bin/env python3
"""
Resolve vendor includes by existence, not by guessing.

The GPL kernel drop ships driver sources whose Makefiles lack include paths that the
original Android build system supplied. For every Makefile directory we read the
#include directives of the sources it builds and, for each name, test candidate
directories (the directory itself, its include//inc, and up to two parents). The
first candidate that actually contains the header is added as -I.

This is exact: a path is added only when the file provably exists there, so no
unrelated header can be shadowed. -Wno-error is appended so 2018-era vendor code is
not failed by warnings from a newer toolchain.

Usage: python fix_includes_smart.py <kernel_root>
"""
import os
import re
import sys

root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else '.')
INC_RE = re.compile(rb'^\s*#\s*include\s*[<"]([^>"]+)[>"]', re.M)
MARK = 'droidspaces_includes'
MAX_ANCESTORS = 2

visited = patched = 0
for dirpath, dirnames, filenames in os.walk(root):
    dirnames[:] = [d for d in dirnames if d not in ('.git', 'out')]
    if 'Makefile' not in filenames:
        continue
    visited += 1
    try:
        mk = open(os.path.join(dirpath, 'Makefile'), encoding='utf-8', errors='surrogateescape').read()
    except OSError:
        continue
    if MARK in mk:
        continue
    if not any(l.strip().startswith(('obj-', 'obj-y', 'obj-m')) for l in mk.splitlines()):
        continue

    rel = os.path.relpath(dirpath, root).replace(os.sep, '/')
    candidates = [rel, rel + '/include', rel + '/inc']
    anc = rel
    for _ in range(MAX_ANCESTORS):
        anc = os.path.dirname(anc).replace(os.sep, '/')
        if not anc or anc == '.':
            break
        candidates += [anc, anc + '/include', anc + '/inc']
    candidates = list(dict.fromkeys(candidates))

    names = set()
    for fn in filenames:
        if not fn.endswith(('.c', '.h', '.S')):
            continue
        try:
            data = open(os.path.join(dirpath, fn), 'rb').read()
        except OSError:
            continue
        for m in INC_RE.finditer(data):
            n = m.group(1).decode('utf-8', 'replace').strip()
            n = n[2:] if n.startswith('./') else n
            if n and not n.startswith('/') and '/' in n or n.endswith('.h'):
                names.add(os.path.normpath(n))

    needed = set()
    for n in names:
        for c in candidates:
            if os.path.isfile(os.path.join(root, c, n)):
                needed.add(c)
                break

    flags = ' '.join(f'-I$(srctree)/{d}' for d in sorted(needed))
    block = ('\n# --- added by Droidspaces build kit (include resolution) ---\n'
             f'{MARK} := {flags} -Wno-error\n'
             'ccflags-y += $(' + MARK + ')\n')
    open(os.path.join(dirpath, 'Makefile'), 'w', encoding='utf-8', errors='surrogateescape').write(mk + block)
    patched += 1

print(f'Makefiles scanned: {visited}, patched: {patched}')
