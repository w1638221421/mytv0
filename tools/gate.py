#!/usr/bin/env python3
"""Compare BUILD-0 / BUILD-3 Module.symvers against the 339 stock module CRCs."""
import os


def load_symvers(p):
    d = {}
    for ln in open(p, encoding='utf-8', errors='replace'):
        parts = ln.rstrip('\n').split('\t')
        if len(parts) >= 2 and parts[0].startswith('0x'):
            d[parts[1]] = int(parts[0], 16) & 0xFFFFFFFF
    return d


def load_need(p):
    d = {}
    for ln in open(p, encoding='utf-8', errors='replace'):
        ln = ln.rstrip('\n')
        if not ln or '\t' not in ln:
            continue
        c, n = ln.split('\t', 1)
        d[n] = int(c, 16)
    return d


def counts(symvers, need):
    m = mi = 0
    for n, e in need.items():
        a = symvers.get(n)
        if a is None:
            mi += 1
        elif a == e:
            m += 1
    return m, len(need) - m - mi, mi


def main():
    need = load_need('configs/stock-modcrcs.txt')
    os.makedirs('artifacts', exist_ok=True)
    builds = {}
    for name in ('BUILD-0', 'BUILD-3'):
        p = 'artifacts/%s/Module.symvers' % name
        if os.path.exists(p):
            builds[name] = load_symvers(p)
    out = []
    for name in ('BUILD-0', 'BUILD-3'):
        if name in builds:
            m, x, z = counts(builds[name], need)
            out.append('%s: %d MATCH / %d MISMATCH / %d MISSING' % (name, m, x, z))
        else:
            out.append('%s: NOT BUILT' % name)

    if 'BUILD-0' in builds and 'BUILD-3' in builds:
        s0, s3 = builds['BUILD-0'], builds['BUILD-3']
        changed = [n for n in need if s0.get(n) != s3.get(n)]
        out.append('BUILD-3 vs BUILD-0 changed symbols (of 339): %d' % len(changed))
        for n in changed[:60]:
            out.append('  CHANGED %s: %08x -> %08x' % (n, s0.get(n, 0), s3.get(n, 0)))
        for k in ('module_layout', 'cdev_add', 'cdev_del', '__mutex_init'):
            ok = s3.get(k) == need.get(k)
            out.append('  KEY %-14s expect=%08x build0=%08x build3=%08x %s'
                       % (k, need.get(k, 0), s0.get(k, 0), s3.get(k, 0),
                          'MATCH' if ok else 'FAIL'))
    txt = '\n'.join(out) + '\n'
    open('artifacts/gate-summary.txt', 'w').write(txt)
    print(txt)


if __name__ == '__main__':
    main()
