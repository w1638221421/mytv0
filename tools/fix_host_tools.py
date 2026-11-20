#!/usr/bin/env python3
"""
Make the in-tree host tools build with modern GCC (>=10), which defaults to
-fno-common.

linux 4.4's scripts/dtc/dtc-lexer.l declares `YYLTYPE yylloc;` while bison also
emits a definition for it in dtc-parser.tab.c -> "multiple definition of yylloc".
Upstream fixed this by dropping the redundant declaration from the lexer.

Usage: python fix_host_tools.py <kernel_root>
"""
import os
import re
import sys

root = sys.argv[1] if len(sys.argv) > 1 else '.'
changed = 0

lex = os.path.join(root, 'scripts/dtc/dtc-lexer.l')
if os.path.exists(lex):
    src = open(lex, encoding='utf-8', errors='surrogateescape').read()
    new, n = re.subn(r'^YYLTYPE\s+yylloc;\s*$', '/* yylloc is defined by the bison parser */', src, flags=re.M)
    if n:
        open(lex, 'w', encoding='utf-8', errors='surrogateescape').write(new)
        print(f'patched scripts/dtc/dtc-lexer.l: removed {n} yylloc definition(s)')
        changed += n
    else:
        print('scripts/dtc/dtc-lexer.l: no yylloc definition found (already fixed?)')
else:
    print('scripts/dtc/dtc-lexer.l: not found, skipping')

# dtc-lexer.lex.c may exist if flex already ran; drop the duplicate there too
lexc = os.path.join(root, 'scripts/dtc/dtc-lexer.lex.c')
if os.path.exists(lexc):
    src = open(lexc, encoding='utf-8', errors='surrogateescape').read()
    new, n = re.subn(r'^YYLTYPE\s+yylloc;\s*$', 'extern YYLTYPE yylloc;', src, flags=re.M)
    if n:
        open(lexc, 'w', encoding='utf-8', errors='surrogateescape').write(new)
        print(f'patched scripts/dtc/dtc-lexer.lex.c: {n} declaration(s) made extern')
        changed += n

print('host tool fixes applied:', changed)
sys.exit(0)
