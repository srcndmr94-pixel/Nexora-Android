#!/usr/bin/env python3
"""Repair the mojibake that crept into tools/fix_l10.py during upload.

The patch script was stored with UTF-8 bytes decoded as cp1252, so every
Turkish character turned into two latin characters. Reverse that exactly.
"""
import io

PATH = "tools/fix_l10.py"


def encode(ch):
    try:
        return ch.encode("cp1252")
    except Exception:
        return ch.encode("latin-1")


raw = io.open(PATH, encoding="utf-8").read()
text = b"".join(encode(c) for c in raw).decode("utf-8")
# One character was lost by the transport; restore it.
text = text.replace("\u201dya se\u00e7", "\u201dy\u0131 se\u00e7")
io.open(PATH, "w", encoding="utf-8").write(text)
print("repaired bytes:", len(text.encode("utf-8")))
