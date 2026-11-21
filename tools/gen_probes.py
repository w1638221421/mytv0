#!/usr/bin/env python3
"""Pipeline probe generator.

Answers two questions about our synthetic genksyms harness:
  1. does a *definition* or a *declaration* produce the real CRC?
  2. does the symbol name enter the CRC?

All four variants plant the exact cdev_add / cdev_del declarations that the
real kernel build used, and compare against the CRCs that build produced
(BUILD-0 Module.symvers): cdev_add=1d092a8a, cdev_del=020f71e8.

Usage: python3 gen_probes.py <mode> <out.c> <out_map.txt>
       mode = def_real | decl_real | def_dummy | decl_dummy
"""
import sys

BASE = [
    ('add', 'int', ['struct cdev *', 'dev_t', 'unsigned'], '1d092a8a', 'cdev_add'),
    ('del', 'void', ['struct cdev *'], '020f71e8', 'cdev_del'),
]


def main():
    mode, out_c, out_map = sys.argv[1], sys.argv[2], sys.argv[3]
    use_def = mode.startswith('def')
    real_name = mode.endswith('real')

    lines = [
        '#include <linux/kernel.h>',
        '#include <linux/module.h>',
        '#include <linux/cdev.h>',
        '',
    ]
    mapping = []
    for suffix, rt, ps, expect, realname in BASE:
        name = realname if real_name else 'zzz_%s' % realname
        if use_def:
            lines.append('%s %s(%s) { }' % (rt, name, ', '.join(ps)))
        else:
            lines.append('%s %s(%s);' % (rt, name, ', '.join(ps)))
        lines.append('EXPORT_SYMBOL(%s);' % name)
        mapping.append('%s\tprobe\t%s\t-\t%s %s | %s'
                       % (name, expect, 'DEF' if use_def else 'DECL', rt, ' | '.join(ps)))
    open(out_c, 'w').write('\n'.join(lines) + '\n')
    open(out_map, 'w').write('\n'.join(mapping) + '\n')
    print('mode %s -> %d probes' % (mode, len(mapping)))


if __name__ == '__main__':
    main()
