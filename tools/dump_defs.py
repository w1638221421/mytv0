#!/usr/bin/env python3
"""Dump genksyms' expanded token stream (via `genksyms -D`) for a real source
file, using that file's own .cmd flags.

Usage: python3 dump_defs.py <ws> <source.c> [cmd_file]
Writes  <ws>/defs-<basename>.txt   (stderr of genksyms -D, contains
        "Export <sym> == < tokens >" blocks)
        <ws>/ver-<basename>.txt    (stdout, __crc_* assignments)
"""
import os
import re
import shlex
import subprocess
import sys


def load_cmd(cmd_path):
    lines = open(cmd_path, encoding='utf-8', errors='replace').read().splitlines()
    start = None
    for i, l in enumerate(lines):
        if ':=' in l and l.lstrip().startswith('savedcmd_'):
            start = i
            break
    if start is None:
        for i, l in enumerate(lines):
            if ':=' in l:
                start = i
                break
    if start is None:
        sys.exit('no command line in %s' % cmd_path)
    buf = []
    j = start
    while j < len(lines):
        l = lines[j]
        if l.endswith('\\'):
            buf.append(l[:-1])
            j += 1
            continue
        buf.append(l)
        break
    full = ' '.join(buf).split(':=', 1)[1]
    return ' '.join(full.split())


def split_flags(full, src_name):
    toks = shlex.split(full)
    cc = toks[0]
    keep = ['-D__GENKSYMS__']
    skip = False
    for t in toks[1:]:
        if skip:
            skip = False
            continue
        if t == '-o':
            skip = True
            continue
        if t == '-c':
            continue
        if t.startswith('-Wp,-MD,') or t.startswith('-Wp,-MMD,'):
            continue
        if t.endswith('.c') or t == src_name:
            continue
        keep.append(t)
    return cc, keep


def main():
    ws = sys.argv[1]
    src = sys.argv[2]
    cmd_path = sys.argv[3] if len(sys.argv) > 3 else os.path.join(ws, 'adapt.cmd')
    base = os.path.basename(src)

    full = load_cmd(cmd_path)
    cc, flags = split_flags(full, src)
    print('cc =', cc)
    print('flags =', len(flags))

    i_out = os.path.join(ws, 'dump-%s.i' % base)
    r = subprocess.run([cc] + flags + ['-E', '-o', i_out, src], capture_output=True, text=True)
    if r.returncode != 0:
        print('--- preprocess stderr (first 40) ---')
        print('\n'.join(r.stderr.splitlines()[:40]))
        sys.exit('preprocess failed')
    print('preprocessed bytes:', os.path.getsize(i_out))

    with open(i_out, 'rb') as f:
        r2 = subprocess.run(['./scripts/genksyms/genksyms', '-D'], stdin=f, capture_output=True)
    dump = r2.stderr.decode('utf-8', 'replace')
    ver = r2.stdout.decode('utf-8', 'replace')
    open(os.path.join(ws, 'defs-%s.txt' % base), 'w').write(dump)
    open(os.path.join(ws, 'ver-%s.txt' % base), 'w').write(ver)
    print('dump bytes:', len(dump), 'ver bytes:', len(ver))

    names = re.findall(r'__crc_(\S+)\s*=\s*0x', ver)
    print('crc symbols:', len(names))
    for n in names:
        if 'connectivity' in n or 'cdev' in n or 'btif' in n:
            m = re.search(r'__crc_%s\s*=\s*0x([0-9a-fA-F]+)' % re.escape(n), ver)
            print('  %-50s %s' % (n, m.group(1) if m else '?'))


if __name__ == '__main__':
    main()
