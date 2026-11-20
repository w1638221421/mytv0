#!/usr/bin/env python3
"""
Gate: compare the kernel's exported-symbol CRCs (Module.symvers) against the CRCs
that the stock 2018 vendor modules demand (stock-modcrcs.txt).

A single mismatch means the corresponding .ko will refuse to load (or, worse, be
CRC-bypassed and corrupt memory) -> WiFi / BT / GPS / FM dead.
"""
import sys

symvers_path, stock_path = sys.argv[1], sys.argv[2]

have = {}
for line in open(symvers_path, encoding='utf-8', errors='replace'):
    parts = line.rstrip('\n').split('\t')
    if len(parts) >= 2 and parts[0].startswith('0x'):
        try:
            have[parts[1]] = int(parts[0], 16) & 0xFFFFFFFF
        except ValueError:
            pass

need = {}
for line in open(stock_path, encoding='utf-8', errors='replace'):
    line = line.rstrip('\n')
    if not line or '\t' not in line:
        continue
    crc, name = line.split('\t', 1)
    need[name] = int(crc, 16)

missing = [n for n in need if n not in have]
mismatch = [(n, need[n], have[n]) for n in need if n in have and have[n] != need[n]]
ok = len(need) - len(missing) - len(mismatch)

print(f'exported symbols in build : {len(have)}')
print(f'symbols demanded by modules: {len(need)}')
print(f'matched                    : {ok}')
print(f'missing (not exported)     : {len(missing)}')
print(f'CRC mismatch               : {len(mismatch)}')

for n in missing[:20]:
    print(f'  MISSING  {n}')
for n, a, b in mismatch[:20]:
    print(f'  MISMATCH {n}: module expects {a:08x}, kernel has {b:08x}')

if missing or mismatch:
    print('\nGATE RESULT: FAIL')
    sys.exit(1)
print('\nGATE RESULT: PASS - every stock vendor module symbol matches')
