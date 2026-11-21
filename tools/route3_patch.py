#!/usr/bin/env python3
"""ROUTE-3 minimal patch.

1. init/Kconfig : drop `depends on (SYSVIPC || POSIX_MQUEUE)` from IPC_NS so the
   IPC namespace can exist while both SysV IPC and POSIX mqueue stay OFF
   (struct task_struct / struct user_struct therefore stay byte-identical to
   the stock kernel).
2. ipc/namespace.c : the mqueue cleanup in put_ipc_ns() references mq_lock /
   mq_clear_sbinfo() / mq_put_mnt(), which only exist when POSIX_MQUEUE is on.
   Guard that block so IPC_NS=y links with POSIX_MQUEUE=n.

Nothing else is touched: no struct, no genksyms, no CRC.
Usage: python3 route3_patch.py <kernel_root>
"""
import sys, os

root = sys.argv[1] if len(sys.argv) > 1 else '.'

# ---------------------------------------------------------------- init/Kconfig
p = os.path.join(root, 'init/Kconfig')
s = open(p, encoding='utf-8').read()

old = (
    'config IPC_NS\n'
    '\tbool "IPC namespace"\n'
    '\tdepends on (SYSVIPC || POSIX_MQUEUE)\n'
    '\tdefault y'
)
new = (
    'config IPC_NS\n'
    '\tbool "IPC namespace"\n'
    '\t# ROUTE-3: IPC namespace is usable without SysV IPC and without POSIX\n'
    '\t# mqueue. It then isolates an empty ipc_namespace, which is all a\n'
    '\t# container needs, and keeps task_struct/user_struct byte-identical.\n'
    '\tdefault y'
)
if s.count(old) != 1:
    sys.exit('FATAL: IPC_NS block not found exactly once (count=%d)' % s.count(old))
open(p, 'w', encoding='utf-8').write(s.replace(old, new))
print('[+] init/Kconfig: IPC_NS no longer depends on (SYSVIPC || POSIX_MQUEUE)')

# ------------------------------------------------------------ ipc/namespace.c
p = os.path.join(root, 'ipc/namespace.c')
s = open(p, encoding='utf-8').read()

old = (
    '\tif (atomic_dec_and_lock(&ns->count, &mq_lock)) {\n'
    '\t\tmq_clear_sbinfo(ns);\n'
    '\t\tspin_unlock(&mq_lock);\n'
    '\t\tmq_put_mnt(ns);\n'
    '\t\tfree_ipc_ns(ns);\n'
    '\t}'
)
new = (
    '#ifdef CONFIG_POSIX_MQUEUE\n'
    '\tif (atomic_dec_and_lock(&ns->count, &mq_lock)) {\n'
    '\t\tmq_clear_sbinfo(ns);\n'
    '\t\tspin_unlock(&mq_lock);\n'
    '\t\tmq_put_mnt(ns);\n'
    '\t\tfree_ipc_ns(ns);\n'
    '\t}\n'
    '#else\n'
    '\t/* ROUTE-3: no POSIX mqueue compiled in -> no mqueue sb / mq_lock. */\n'
    '\tif (atomic_dec_and_test(&ns->count))\n'
    '\t\tfree_ipc_ns(ns);\n'
    '#endif'
)
if s.count(old) != 1:
    sys.exit('FATAL: put_ipc_ns mq_lock block not found exactly once (count=%d)' % s.count(old))
s = s.replace(old, new)

# ipc/msgutil.c (the usual definition of nr_ipc_ns) is only built when
# CONFIG_SYSVIPC or CONFIG_POSIX_MQUEUE is on. Provide it here otherwise.
anchor = '#include "util.h"\n'
if s.count(anchor) != 1:
    sys.exit('FATAL: util.h include not found exactly once (count=%d)' % s.count(anchor))
define = (anchor +
          '\n'
          '#if !defined(CONFIG_SYSVIPC) && !defined(CONFIG_POSIX_MQUEUE)\n'
          '/* ROUTE-3: ipc/msgutil.c is not built when both IPC flavours are off,\n'
          ' * so the namespace counter is defined here instead.\n'
          ' */\n'
          'atomic_t nr_ipc_ns = ATOMIC_INIT(1);\n'
          '#endif\n')
s = s.replace(anchor, define)
open(p, 'w', encoding='utf-8').write(s)
print('[+] ipc/namespace.c: put_ipc_ns() mqueue cleanup guarded by CONFIG_POSIX_MQUEUE')
print('[+] ipc/namespace.c: nr_ipc_ns defined when SYSVIPC=n and POSIX_MQUEUE=n')
