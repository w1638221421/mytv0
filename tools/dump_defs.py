#!/usr/bin/env python3
"""Dump the exact genksyms token stream (via `genksyms -D`) for the MTK symbols.

The token stream is what the CRC is computed over, so having it lets us mutate
signatures and recompute CRCs in plain Python instead of re-running genksyms.

Usage: python3 dump_defs.py <ws>   (cwd = kernel tree, cand.c/cand.map prepared)
"""
import os
import re
import shlex
import subprocess
import sys

ws = sys.argv[1] if len(sys.argv) > 1 else '.'
cmd_path = os.path.join(ws, 'adapt.cmd')
lines = open(cmd_path, encoding='utf-8', errors='replace').read().splitlines()

start = None
for i, l in enumerate(lines):
    if ':=' in l and l.lstrip().startswith('savedcmd_'):
        start = i
        break
buf = []
j = start
while j < len(lines):
    l = lines[j]
    if l.endswith('\\'):
        buf.append(l[:-1]); j += 1; continue
    buf.append(l); break
full = ' '.join(buf).split(':=', 1)[1]
full = ' '.join(full.split())

toks = shlex.split(full)
cc = toks[0]
keep = ['-D__GENKSYMS__']
skip = False
for t in toks[1:]:
    if skip:
        skip = False; continue
    if t == '-o':
        skip = True; continue
    if t in ('-c',):
        continue
    if t.startswith('-Wp,-MD,') or t.startswith('-Wp,-MMD,'):
        continue
    if t.endswith('.c'):
        continue
    keep.append(t)

src = os.path.join(ws, 'cand.c')
i_out = os.path.join(ws, 'cand.i')
r = subprocess.run([cc] + keep + ['-E', '-o', i_out, src], capture_output=True, text=True)
if r.returncode != 0:
    print('\n'.join(r.stderr.splitlines()[:40]))
    sys.exit('preprocess failed')

with open(i_out, 'rb') as f:
    r2 = subprocess.run(['./scripts/genksyms/genksyms', '-D'], stdin=f, capture_output=True)
dump = r2.stderr.decode('utf-8', 'replace')
open(os.path.join(ws, 'defs-dump.txt'), 'w').write(dump)
print('dump bytes:', len(dump))

# show the Export lines for our symbols
for m in re.finditer(r'^Export (\S+) == <(.*?)>\s*$', dump, re.M | re.S):
    name, body = m.group(1), m.group(2)
    print('\n### Export %s' % name)
    print(body.strip()[:3000])
