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
lines = text.splitlines()

# The .cmd file holds several make variables; we only want the compiler command
# line (savedcmd_<obj> := <cc> ...), including its '\'-continued lines, and must
# NOT swallow the later deps_<obj> := $(wildcard ...) variables.
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
    sys.exit('cannot find a command line in %s' % cmd_path)

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
full = ' '.join(full.split())
print('cmd line chars =', len(full))

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

# include/linux/export.h keeps EXPORT_SYMBOL() as a literal token only when
# __GENKSYMS__ is defined (that is what the kernel's own gensymtypes rule does).
keep = ['-D__GENKSYMS__'] + keep

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

# dump every candidate CRC so nothing is lost in the log
with open(os.path.join(ws, 'allcrc.txt'), 'w') as f:
    for k in sorted(crc):
        f.write('%s\t%08x\n' % (k, crc[k]))
print('--- all candidate CRCs ---')
for k in sorted(crc):
    print('  %-34s %08x' % (k, crc[k]))

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

print('\n=== PIPELINE SELF-TEST (known BUILD-0 CRCs) ===')
for tag in ['selftest']:
    h = hits.get(tag, [])
    if h:
        for sig, got in h:
            print('  SELFTEST OK  %08x  %s' % (got, sig))
    else:
        print('  SELFTEST FAILED -- no known-good CRC reproduced; results unreliable')
        # show what the self-test candidates actually produced
        for line in open(os.path.join(ws, 'cand.map')):
            name, t, expect, alt, sig = line.rstrip('\n').split('\t')
            if t == 'selftest' and name in crc:
                print('    got %08x want %s  %s' % (crc[name], expect, sig))

print('\n=== BASELINE (GPL) CANDIDATE CRCs vs BUILD-0 ===')
order = ['show_stack', 'tracing_record_cmdline', 'mmc_io_rw_direct',
         'dump_thread_state', 'btif_dbg_ctrl']
for tag in order:
    for sig, got in base_hits.get(tag, [])[:3]:
        print('  %-24s %08x  %s' % (tag, got, sig))
    if not base_hits.get(tag):
        print('  %-24s (no candidate reproduced the BUILD-0 CRC)' % tag)

print('\n=== PRODUCTION CRC MATCHES ===')
for tag in order:
    h = hits.get(tag, [])
    if h:
        for sig, got in h:
            print('  %-24s %08x  %s' % (tag, got, sig))
    else:
        print('  %-24s NO MATCH' % tag)

with open(os.path.join(ws, 'sig-hits.txt'), 'w') as f:
    f.write('# pipeline self-test\n')
    for sig, got in hits.get('selftest', []):
        f.write('SELFTEST\t%08x\t%s\n' % (got, sig))
    f.write('# production CRC matches\n')
    for tag in order:
        for sig, got in hits.get(tag, []):
            f.write('PROD\t%s\t%08x\t%s\n' % (tag, got, sig))
    f.write('# build0 baseline cross-check\n')
    for tag in order:
        for sig, got in base_hits.get(tag, []):
            f.write('BASE\t%s\t%08x\t%s\n' % (tag, got, sig))
