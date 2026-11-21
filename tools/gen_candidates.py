#!/usr/bin/env python3
"""Generate a genksyms candidate file for the 5 residual MTK symbols.

Why this works: genksyms computes a symbol's CRC from the *declaration token
stream* only (the symbol name is not part of the CRC). So we can declare one
candidate per line under a unique dummy name, EXPORT_SYMBOL it, run genksyms
once on the whole file, and read every candidate CRC back. A candidate whose
CRC equals the production CRC is the production signature.

Usage: python3 gen_candidates.py <out.c> <out_map.txt>
"""
import sys, itertools

# types as they appear (already "expanded enough") in kernel declarations.
# genksyms expands typedefs, so u8 -> unsigned char etc.; both spellings are
# included where the expansion could land differently.
P = [
    'struct task_struct *',
    'unsigned long *',
    'unsigned long',
    'long unsigned int',
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
    'struct task_struct',
    'long',
    'struct sdio_func *',
    'void',
    'const unsigned long *',
]
R = ['void', 'int', 'unsigned long', 'bool', 'struct task_struct *',
     'unsigned int', 'long', 'char *']

# production CRCs (from the vendor modules' __versions / stock-modcrcs.txt)
TARGETS = {
    'show_stack':              ('19e8e0d1', ['struct task_struct *', 'unsigned long *']),
    'tracing_record_cmdline':  ('29d73b18', ['struct task_struct *']),
    'mmc_io_rw_direct':        ('bf9c15ec', ['struct mmc_card *', 'int', 'unsigned',
                                             'unsigned', 'unsigned char', 'unsigned char *']),
    'dump_thread_state':       ('dced65b2', ['const char *']),
    'btif_dbg_ctrl':           ('d53a2b76', ['int', 'int', 'int']),
}


def candidates(base):
    """Baseline + single-parameter mutations + drop/append/insert + return changes."""
    seen = set()
    out = []

    def add(rt, ps):
        key = (rt, tuple(ps))
        if key in seen:
            return
        seen.add(key)
        out.append(key)

    n = len(base)
    # exact baseline
    add('void', base)
    # return-type sweep
    for rt in R:
        add(rt, base)
    # single-parameter substitution
    for i in range(n):
        for t in P:
            if t == base[i]:
                continue
            ps = list(base); ps[i] = t
            add('void', ps)
            for rt in ('int', 'unsigned long', 'bool'):
                add(rt, ps)
    # drop one parameter
    for i in range(n):
        ps = list(base); del ps[i]
        add('void', ps)
    # append one parameter
    for t in P:
        add('void', base + [t])
    # insert one parameter at every position
    for i in range(n + 1):
        for t in P:
            ps = list(base); ps.insert(i, t)
            add('void', ps)
    # two-parameter substitution for short signatures (cheap, exhaustive)
    if n <= 2:
        idx = list(range(n))
        for combo in itertools.combinations(idx, min(2, n)):
            for ts in itertools.product(P, repeat=len(combo)):
                ps = list(base)
                for j, t in zip(combo, ts):
                    ps[j] = t
                add('void', ps)
    return out


def main():
    out_c, out_map = sys.argv[1], sys.argv[2]
    lines = []
    mapping = []
    n = 0
    hdr = [
        '#include <linux/kernel.h>',
        '#include <linux/module.h>',
        '#include <linux/sched.h>',
        '#include <linux/types.h>',
        '#include <linux/mmc/card.h>',
        '#include <linux/mmc/sdio_func.h>',
        '#include <linux/mmc/sdio.h>',
        '',
    ]
    lines.extend(hdr)
    for tag, (expect, base) in TARGETS.items():
        for rt, ps in candidates(base):
            n += 1
            name = 'cand_%s_%04d' % (tag, n)
            decl = '%s %s(%s);' % (rt, name, ', '.join(ps) if ps else 'void')
            lines.append(decl)
            lines.append('EXPORT_SYMBOL(%s);' % name)
            mapping.append('%s\t%s\t%s\t%s' % (name, tag, expect, ' | '.join([rt] + list(ps))))
    open(out_c, 'w').write('\n'.join(lines) + '\n')
    open(out_map, 'w').write('\n'.join(mapping) + '\n')
    print('candidates written: %d -> %s' % (len(mapping), out_c))


if __name__ == '__main__':
    main()
