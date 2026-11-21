#!/usr/bin/env python3
"""Preprocess the candidate file with the kernel's exact flags and run genksyms.

Usage: python3 run_candidates.py <workspace>     (cwd = kernel tree)
"""
import os
import re
import shlex
import subprocess
import sys

ws = sys.argv[1] if len(sys.argv) > 1 else '.'
cmd_path = os.path.join(ws, 'adapt.cmd')

text = open(cmd_path, encoding='utf-8', errors='replace').read()
m = re.search(r':=(.*)', text, re.S)
if not m:
    sys.exit('cannot parse adapt.cmd')
full = m.group(1).replace('\\\n', ' ')
full = ' '.join(full.split())

toks = shlex.split(full)
cc = toks[0]
keep = []
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
    if t.endswith('.c'):
        continue
    keep.append(t)

print('cc =', cc)
print('cflags tokens =', len(keep))

src = os.path.join(ws, 'cand.c')
i_out = os.path.join(ws, 'cand.i')
r = subprocess.run([cc] + keep + ['-E', '-o', i_out, src], capture_output=True, text=True)
if r.returncode != 0:
    print('--- preprocess stderr (first 60 lines) ---')
    print('\n'.join(r.stderr.splitlines()[:60]))
    sys.exit('preprocess failed')
print('preprocessed ->', i_out, os.path.getsize(i_out), 'bytes')

gk = './scripts/genksyms/genksyms'
with open(i_out, 'rb') as f:
    r2 = subprocess.run([gk], stdin=f, capture_output=True)
print('genksyms rc =', r2.returncode)
err = r2.stderr.decode('utf-8', 'replace')
if err.strip():
    print('--- genksyms stderr (first 30 lines) ---')
    print('\n'.join(err.splitlines()[:30]))
ver = r2.stdout.decode('utf-8', 'replace')
open(os.path.join(ws, 'cand.ver'), 'w').write(ver)

crc = {}
for line in ver.splitlines():
    m2 = re.match(r'\s*__crc_(\S+)\s*=\s*0x([0-9a-fA-F]+)', line)
    if m2:
        crc[m2.group(1)] = int(m2.group(2), 16) & 0xFFFFFFFF
print('candidate CRCs produced:', len(crc))

hits = {}
base_hits = {}
for line in open(os.path.join(ws, 'cand.map')):
    name, tag, expect, b0, sig = line.rstrip('\n').split('\t')
    got = crc.get(name)
    if got is None:
        continue
    if got == int(expect, 16):
        hits.setdefault(tag, []).append((sig, got))
    if b0 != '-' and got == int(b0, 16):
        base_hits.setdefault(tag, []).append((sig, got))

print('\n=== PIPELINE SANITY: candidates reproducing the BUILD-0 (GPL) CRC ===')
order = ['show_stack', 'tracing_record_cmdline', 'mmc_io_rw_direct',
         'dump_thread_state', 'btif_dbg_ctrl']
for tag in order:
    for sig, got in base_hits.get(tag, [])[:3]:
        print('  %-24s %08x  %s' % (tag, got, sig))
    if not base_hits.get(tag):
        print('  %-24s (none)' % tag)

print('\n=== PRODUCTION CRC MATCHES ===')
for tag in order:
    h = hits.get(tag, [])
    if h:
        for sig, got in h:
            print('  %-24s %08x  %s' % (tag, got, sig))
    else:
        print('  %-24s NO MATCH' % tag)

with open(os.path.join(ws, 'sig-hits.txt'), 'w') as f:
    f.write('# production CRC matches\n')
    for tag in order:
        for sig, got in hits.get(tag, []):
            f.write('PROD\t%s\t%08x\t%s\n' % (tag, got, sig))
    f.write('# build0 baseline cross-check\n')
    for tag in order:
        for sig, got in base_hits.get(tag, []):
            f.write('BASE\t%s\t%08x\t%s\n' % (tag, got, sig))
