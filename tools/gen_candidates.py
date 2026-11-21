#!/usr/bin/env python3
"""Generate a batched genksyms candidate file for the 5 residual MTK symbols.

Why this works: genksyms derives a symbol's CRC from the *declaration token
stream* only -- the symbol name is not part of the CRC. So we declare one
candidate per line under a unique dummy name, EXPORT_SYMBOL each of them, run
the kernel's own genksyms once over the whole file, and read every candidate
CRC back. A candidate whose CRC equals the production CRC is the production
signature.

Usage: python3 gen_candidates.py <out.c> <out_map.txt>
map columns: cand_name, tag, production_crc, build0_crc, signature
"""
import sys
import itertools

# parameter type spellings (typedefs are expanded by genksyms, so the plain
# spellings below are what actually enters the CRC)
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

# production CRC + BUILD-0 (GPL) CRC for cross-checking the pipeline
TARGETS = [
    # tag, production_crc, build0_crc, gpl_baseline_params, strategy, pool
    ('show_stack',             '19e8e0d1', 'd8a03984',
     ['struct task_struct *', 'unsigned long *'], 'exhaustive2', POOL),
    ('tracing_record_cmdline', '29d73b18', 'b7298605',
     ['struct task_struct *'], 'exhaustive2', POOL),
    ('mmc_io_rw_direct',       'bf9c15ec', 'f256a148',
     ['struct mmc_card *', 'int', 'unsigned', 'unsigned', 'unsigned char', 'unsigned char *'],
     'mutate1', POOL),
    ('dump_thread_state',      'dced65b2', None,
     ['const char *'], 'exhaustive2', POOL),
    ('btif_dbg_ctrl',          'd53a2b76', '864e81df',
     ['int', 'int', 'int'], 'exhaustive3',
     ['int', 'unsigned int', 'unsigned long', 'void *', 'char *',
      'unsigned char', 'unsigned', 'bool']),
]

CAND = []


def add(tag, rt, ps, expect, b0):
    n = len(CAND) + 1
    name = 'cand_%s_%05d' % (tag, n)
    CAND.append((name, tag, expect, b0, [rt] + list(ps)))


def gen(tag, expect, b0, base, strategy, pool):
    if strategy == 'exhaustive2':
        # 0, 1 and 2 parameters exhaustively, every return type
        combos = [[]]
        combos += [[a] for a in pool]
        combos += [list(c) for c in itertools.product(pool, repeat=2)]
    elif strategy == 'exhaustive3':
        combos = [[]]
        combos += [[a] for a in pool]
        combos += [list(c) for c in itertools.product(pool, repeat=2)]
        combos += [list(c) for c in itertools.product(pool, repeat=3)]
    else:  # mutate1 -- baseline plus single-parameter mutations
        combos = [list(base)]
        n = len(base)
        for i in range(n):
            for t in pool:
                if t == base[i]:
                    continue
                ps = list(base); ps[i] = t
                combos.append(ps)
        for i in range(n):
            ps = list(base); del ps[i]
            combos.append(ps)
        for t in pool:
            combos.append(list(base) + [t])
        for i in range(n + 1):
            for t in pool:
                ps = list(base); ps.insert(i, t)
                combos.append(ps)
    seen = set()
    for ps in combos:
        for rt in RET:
            key = (rt, tuple(ps))
            if key in seen:
                continue
            seen.add(key)
            add(tag, rt, ps, expect, b0)


def main():
    out_c, out_map = sys.argv[1], sys.argv[2]
    for tag, expect, b0, base, strategy, pool in TARGETS:
        gen(tag, expect, b0, base, strategy, pool)

    lines = [
        '#include <linux/kernel.h>',
        '#include <linux/module.h>',
        '#include <linux/sched.h>',
        '#include <linux/types.h>',
        '#include <linux/mmc/card.h>',
        '',
    ]
    mapping = []
    for name, tag, expect, b0, ps in CAND:
        rt = ps[0]
        params = ps[1:]
        lines.append('%s %s(%s);' % (rt, name, ', '.join(params) if params else 'void'))
        lines.append('EXPORT_SYMBOL(%s);' % name)
        mapping.append('%s\t%s\t%s\t%s\t%s' % (name, tag, expect, b0 or '-',
                                              ' | '.join(ps)))
    open(out_c, 'w').write('\n'.join(lines) + '\n')
    open(out_map, 'w').write('\n'.join(mapping) + '\n')
    print('candidates: %d' % len(CAND))
    for tag, expect, b0, base, strategy, pool in TARGETS:
        c = sum(1 for x in CAND if x[1] == tag)
        print('  %-24s %6d  (prod %s, build0 %s)' % (tag, c, expect, b0))


if __name__ == '__main__':
    main()
