#!/usr/bin/env python3
"""
Insert struct-layout probes into arch/arm64/kernel/asm-offsets.c so each build
emits the offsets we must compare between the baseline and the target kernel.
"""
import sys

path = sys.argv[1]
src = open(path, encoding='utf-8', errors='surrogateescape').read()

MARK = 'DS_OFFSET_PROBE'
if MARK in src:
    print('probe already present')
    sys.exit(0)

# struct nsproxy is only forward-declared in sched.h; we need the real definition
if 'linux/nsproxy.h' not in src:
    idx = src.rfind('#include')
    eol = src.index('\n', idx) + 1
    src = src[:eol] + '#include <linux/nsproxy.h>\n' + src[eol:]
    print('added #include <linux/nsproxy.h>')

PROBE = '''
	/* DS_OFFSET_PROBE: layout parity checks, fingerprint of task_struct/nsproxy */
	DEFINE(DS_TASK_SIZE, sizeof(struct task_struct));
	DEFINE(DS_TASK_FS, offsetof(struct task_struct, fs));
	DEFINE(DS_TASK_FILES, offsetof(struct task_struct, files));
	DEFINE(DS_TASK_NSPROXY, offsetof(struct task_struct, nsproxy));
	DEFINE(DS_TASK_SIGNAL, offsetof(struct task_struct, signal));
	DEFINE(DS_TASK_SIGHAND, offsetof(struct task_struct, sighand));
	DEFINE(DS_TASK_BLOCKED, offsetof(struct task_struct, blocked));
	DEFINE(DS_TASK_PENDING, offsetof(struct task_struct, pending));
	DEFINE(DS_TASK_SECCOMP, offsetof(struct task_struct, seccomp));
	DEFINE(DS_TASK_CRED, offsetof(struct task_struct, cred));
	DEFINE(DS_TASK_REAL_CRED, offsetof(struct task_struct, real_cred));
	DEFINE(DS_TASK_COMM, offsetof(struct task_struct, comm));
	DEFINE(DS_TASK_MM, offsetof(struct task_struct, mm));
	DEFINE(DS_TASK_ACTIVE_MM, offsetof(struct task_struct, active_mm));
	DEFINE(DS_TASK_PID, offsetof(struct task_struct, pid));
	DEFINE(DS_TASK_TGID, offsetof(struct task_struct, tgid));
	DEFINE(DS_TASK_TASKS, offsetof(struct task_struct, tasks));
	DEFINE(DS_TASK_THREAD, offsetof(struct task_struct, thread));
	DEFINE(DS_NSPROXY_SIZE, sizeof(struct nsproxy));
	DEFINE(DS_NSPROXY_COUNT, offsetof(struct nsproxy, count));
	DEFINE(DS_NSPROXY_UTS, offsetof(struct nsproxy, uts_ns));
	DEFINE(DS_NSPROXY_IPC, offsetof(struct nsproxy, ipc_ns));
	DEFINE(DS_NSPROXY_MNT, offsetof(struct nsproxy, mnt_ns));
	DEFINE(DS_NSPROXY_PID, offsetof(struct nsproxy, pid_ns_for_children));
	DEFINE(DS_NSPROXY_NET, offsetof(struct nsproxy, net_ns));
'''

# insert right after the opening brace of `int main(void)`
needle = 'int main(void)'
i = src.find(needle)
if i < 0:
    sys.exit('FATAL: int main(void) not found in asm-offsets.c')
brace = src.find('{', i)
if brace < 0:
    sys.exit('FATAL: opening brace not found')
src = src[:brace + 1] + '\n' + PROBE + src[brace + 1:]
open(path, 'w', encoding='utf-8', errors='surrogateescape').write(src)
print('patched: offset probes inserted into asm-offsets.c')
