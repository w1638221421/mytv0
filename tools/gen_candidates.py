#!/usr/bin/env python3
"""Generate a batched genksyms candidate file for the 5 residual MTK symbols.

Key facts driving this design:
  * genksyms derives a symbol's CRC from the declaration token stream; the
    symbol *name* is not part of the CRC.
  * The adapter functions are *defined* (with a body) and then EXPORT_SYMBOL'd,
    so candidates must be emitted as function definitions, which is the same
    grammar path the real translation unit takes.
  * include/linux/export.h keeps EXPORT_SYMBOL() literal only under
    -D__GENKSYMS__ (applied by the caller).

Self-test entries with known-good CRCs (from BUILD-0 Module.symvers) validate
that the whole pipeline reproduces the real build before any MTK answer is
trusted.

Usage: python3 gen_candidates.py <out.c> <out_map.txt>
map columns: cand_name, tag, production_crc, alt_crc, signature
"""
import sys
import itertools

POOL = [
    'struct task_struct *',
    'unsigned long *',
    'unsigned long',
    'int',
    'unsigned int',
    'unsigned',
    'char *',
    'const char *',
    'void *',
    'struct mmc_card *',
    'unsigned char',
    'bool',
    'int *',
    'unsigned int *',
]
RET = ['void', 'int', 'unsigned long', 'bool', 'struct task_struct *']

# tag, production_crc, alt_crc (BUILD-0), gpl_baseline_params, strategy, pool
TARGETS = [
    ('show_stack',             '19e8e0d1', 'd8a03984',
     ['struct task_struct *', 'unsigned long *'], 'exhaustive2', POOL),
    ('tracing_record_cmdline', '29d73b18', 'b7298605',
     ['struct task_struct *'], 'exhaustive2', POOL),
    ('mmc_io_rw_direct',       'bf9c15ec', 'f256a148',
     ['struct mmc_card *', 'int', 'unsigned', 'unsigned',
      'unsigned char', 'unsigned char *'], 'mutate1', POOL),
    ('dump_thread_state',      'dced65b2', None,
     ['const char *'], 'exhaustive2', POOL),
    ('btif_dbg_ctrl',          'd53a2b76', '864e81df',
     ['int', 'int', 'int'], 'exhaustive3',
     ['int', 'unsigned int', 'unsigned long', 'void *', 'char *',
      'unsigned char', 'unsigned', 'bool']),
]

# pipeline self-test: exact declarations taken from the kernel source, with the
# CRCs the real build produced (BUILD-0 Module.symvers).
SELFTEST = [
    ('st_cdev_add', '1d092a8a',
     'int', ['struct cdev *', 'dev_t', 'unsigned']),
    ('st_cdev_del', '020f71e8',
     'void', ['struct cdev *']),
    ('st_mutex_init', '18af4c67',
     'void', ['struct mutex *', 'const char *', 'struct lock_class_key *']),
    ('st_module_layout', '03a95cdd',
     'void', ['struct module *', 'struct modversion_info *', 'struct kernel_param *',
              'struct kernel_symbol *', 'struct tracepoint * const *']),
]

CAND = []


def add(tag, rt, ps, expect, alt):
    n = len(CAND) + 1
    name = 'cand_%s_%05d' % (tag, n)
    CAND.append((name, tag, expect, alt, [rt] + list(ps)))


def gen(tag, expect, alt, base, strategy, pool):
    if strategy == 'exhaustive2':
        combos = [[]] + [[a] for a in pool] + \
                 [list(c) for c in itertools.product(pool, repeat=2)]
    elif strategy == 'exhaustive3':
        combos = [[]] + [[a] for a in pool] + \
                 [list(c) for c in itertools.product(pool, repeat=2)] + \
                 [list(c) for c in itertools.product(pool, repeat=3)]
    else:
        combos = [list(base)]
        n = len(base)
        for i in range(n):
            for t in pool:
                if t == base[i]:
                    continue
                ps = list(base)
                ps[i] = t
                combos.append(ps)
        for i in range(n):
            ps = list(base)
            del ps[i]
            combos.append(ps)
        for t in pool:
            combos.append(list(base) + [t])
        for i in range(n + 1):
            for t in pool:
                ps = list(base)
                ps.insert(i, t)
                combos.append(ps)
    seen = set()
    for ps in combos:
        for rt in RET:
            key = (rt, tuple(ps))
            if key in seen:
                continue
            seen.add(key)
            add(tag, rt, ps, expect, alt)


def main():
    out_c, out_map = sys.argv[1], sys.argv[2]
    for tag, expect, alt, base, strategy, pool in TARGETS:
        gen(tag, expect, alt, base, strategy, pool)
    for name, expect, rt, ps in SELFTEST:
        add('selftest', rt, ps, expect, None)

    lines = [
        '#include <linux/kernel.h>',
        '#include <linux/module.h>',
        '#include <linux/sched.h>',
        '#include <linux/types.h>',
        '#include <linux/cdev.h>',
        '#include <linux/mutex.h>',
        '#include <linux/lockdep.h>',
        '#include <linux/tracepoint.h>',
        '#include <linux/mmc/card.h>',
        '',
    ]
    mapping = []
    for name, tag, expect, alt, ps in CAND:
        rt, params = ps[0], ps[1:]
        # real function DEFINITION (same grammar path as the vendor .c)
        lines.append('%s %s(%s) { }' % (rt, name, ', '.join(params) if params else 'void'))
        lines.append('EXPORT_SYMBOL(%s);' % name)
        mapping.append('%s\t%s\t%s\t%s\t%s' % (name, tag, expect, alt or '-', ' | '.join(ps)))
    open(out_c, 'w').write('\n'.join(lines) + '\n')
    open(out_map, 'w').write('\n'.join(mapping) + '\n')
    print('candidates: %d' % len(CAND))
    for tag in [t[0] for t in TARGETS] + ['selftest']:
        print('  %-24s %6d' % (tag, sum(1 for x in CAND if x[1] == tag)))


if __name__ == '__main__':
    main()
