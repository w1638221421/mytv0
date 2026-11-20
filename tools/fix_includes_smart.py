#!/usr/bin/env python3
"""
Resolve vendor includes by *content*, not by guessing.

The GPL kernel drop ships driver sources whose Makefiles do not carry the include
paths the original Android build system supplied. A blanket -I over whole subtrees
risks shadowing; instead this script:

  1. indexes every header in the tree by basename,
  2. reads the #include directives of the sources each Makefile actually builds,
  3. for any include name that cannot be resolved from the directory itself
     (or its include//inc/parent dirs) but exists in exactly one place, adds that
     one directory to the directory's ccflags.

It also appends -Wno-error so that 2018-era vendor code is not failed by warnings
from a newer toolchain. No driver logic is touched.

Usage: python fix_includes_smart.py <kernel_root>
"""
import os
import re
import sys
from collections import defaultdict

root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else '.')

INC_RE = re.compile(rb'^\s*#\s*include\s*[<"]([^>"]+)[>"]', re.M)
MARK = 'droidspaces_includes'

# ---------------------------------------------------------------- index headers
index = defaultdict(list)
for dirpath, dirnames, filenames in os.walk(root):
    dirnames[:] = [d for d in dirnames if d not in ('.git', 'out')]
    for fn in filenames:
        if fn.endswith('.h'):
            index[fn].append(os.path.relpath(dirpath, root).replace(os.sep, '/'))
print(f'header index: {len(index)} unique names')

def resolve(name):
    """Return the single directory holding this header, or None if ambiguous/unknown."""
    locs = index.get(os.path.basename(name))
    if not locs or len(locs) != 1:
        return None
    return locs[0]

# ---------------------------------------------------------------- scan makefiles
patched = 0
visited = 0
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
    local = {rel, rel + '/include', rel + '/inc'}
    p = rel
    for _ in range(2):
        p = os.path.dirname(p).replace(os.sep, '/')
        if not p or p == '.':
            break
        local.add(p)

    needed = set()
    for fn in filenames:
        if not fn.endswith(('.c', '.h', '.S')):
            continue
        try:
            data = open(os.path.join(dirpath, fn), 'rb').read()
        except OSError:
            continue
        for m in INC_RE.finditer(data):
            name = m.group(1).decode('utf-8', 'replace').strip()
            if '/' in name.lstrip('./') and not name.startswith('./'):
                continue                      # subsystem include like linux/xxx.h
            base = os.path.basename(name)
            target = resolve(base)
            if target and target not in local:
                needed.add(target)

    if not needed and '-Wno-error' in mk:
        continue
    flags = ' '.join(f'-I$(srctree)/{d}' for d in sorted(needed))
    block = ('\n# --- added by Droidspaces build kit (include resolution) ---\n'
             f'{MARK} := {flags} -Wno-error\n'
             'ccflags-y += $(' + MARK + ')\n')
    open(os.path.join(dirpath, 'Makefile'), 'w', encoding='utf-8', errors='surrogateescape').write(mk + block)
    patched += 1

print(f'Makefiles scanned: {visited}, patched: {patched}')
