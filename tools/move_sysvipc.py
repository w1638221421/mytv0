#!/usr/bin/env python3
"""
Relocate the CONFIG_SYSVIPC members inside `struct task_struct` to the END of the
struct, so that enabling SYSVIPC (required by CONFIG_IPC_NS in linux 4.4) does not
shift the offsets of any pre-existing field.

Rationale: the vendor connectivity modules (wlan_drv_gen2.ko, wmt_drv.ko, bt_drv.ko)
are prebuilt 2018 binaries that access task_struct fields at baked-in offsets.
Inserting members mid-struct silently corrupts them; CRC checks cannot detect it.
"""
import sys, re

path = sys.argv[1] if len(sys.argv) > 1 else 'include/linux/sched.h'
src = open(path, encoding='utf-8', errors='surrogateescape').read()

BLOCK = '''#ifdef CONFIG_SYSVIPC
/* ipc stuff */
\tstruct sysv_sem sysvsem;
\tstruct sysv_shm sysvshm;
#endif
'''

if src.count(BLOCK) != 1:
    sys.exit(f'FATAL: expected exactly 1 SYSVIPC block, found {src.count(BLOCK)}')

# 1) cut it out of its original position
src = src.replace(BLOCK, '', 1)

# 2) find `struct task_struct {` and its matching closing brace
m = re.search(r'struct task_struct\s*\{', src)
if not m:
    sys.exit('FATAL: struct task_struct not found')
start = m.end()
depth = 1
i = start
while i < len(src) and depth:
    c = src[i]
    if c == '{':
        depth += 1
    elif c == '}':
        depth -= 1
    i += 1
if depth:
    sys.exit('FATAL: unbalanced braces in struct task_struct')
close = i - 1            # index of the closing '}'
line_start = src.rfind('\n', 0, close) + 1
indent = src[line_start:close]
if indent.strip():
    sys.exit('FATAL: unexpected text before closing brace: %r' % indent)

# 3) re-insert at the tail, keeping the struct's own indentation
tail = BLOCK.replace('\n\t', '\n' + indent + '\t').replace('#ifdef', indent + '#ifdef', 1)
src = src[:line_start] + indent + tail + src[line_start:]

open(path, 'w', encoding='utf-8', errors='surrogateescape').write(src)
print('patched: SYSVIPC members relocated to end of struct task_struct')
print('         (all pre-existing field offsets preserved)')
