#!/usr/bin/env python3
"""Minimal genksyms pipeline probe: does our synthetic candidate reproduce a
known CRC from the real build?

We plant, in one translation unit, the real declaration of cdev_add/cdev_del
(from <linux/cdev.h>) plus a definition, under both the real name and a dummy
name, and compare the genksyms CRCs against the CRCs the real kernel build
produced (BUILD-0 Module.symvers):
    cdev_add  = 1d092a8a
    cdev_del  = 020f71e8
"""
import sys

PROBES = [
    # (candidate name, return type, params, expected crc, note)
    ('cdev_add', 'int', ['struct cdev *', 'dev_t', 'unsigned'], '1d092a8a', 'real name'),
    ('zzz_dummy_add', 'int', ['struct cdev *', 'dev_t', 'unsigned'], '1d092a8a', 'dummy name'),
    ('cdev_del', 'void', ['struct cdev *'], '020f71e8', 'real name'),
    ('zzz_dummy_del', 'void', ['struct cdev *'], '020f71e8', 'dummy name'),
    ('zzz_dummy_del2', 'void', ['struct cdev * p'], '020f71e8', 'dummy name, named param'),
]


def main():
    out_c, out_map = sys.argv[1], sys.argv[2]
    lines = [
        '#include <linux/kernel.h>',
        '#include <linux/module.h>',
        '#include <linux/cdev.h>',
        '',
    ]
    mapping = []
    for name, rt, ps, expect, note in PROBES:
        lines.append('%s %s(%s) { }' % (rt, name, ', '.join(ps)))
        lines.append('EXPORT_SYMBOL(%s);' % name)
        mapping.append('%s\tprobe\t%s\t-\t%s | %s | %s'
                       % (name, expect, rt, ' | '.join(ps), note))
    open(out_c, 'w').write('\n'.join(lines) + '\n')
    open(out_map, 'w').write('\n'.join(mapping) + '\n')
    print('probes: %d' % len(PROBES))


if __name__ == '__main__':
    main()
