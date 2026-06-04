#!/usr/bin/env python3
# Copyright (C) 2026 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
"""
dl_glyph_decode.py — Reverse-map glyph IDs in `dumpsys gfxinfo <pkg> displaylist`
output back to UTF-8 text using fontTools.

This is a deterministic, model-free preprocessing step. The framework-side
`FlatExportOpsCanvas` emits each drawText op's runs as
  {"font": "<typeface_family>", "glyphs": [g1, g2, ...]}
and this script walks the corresponding font's cmap (plus all TTC sub-fonts)
to recover the original characters.

Usage:
  # Pull system fonts (one-time)
  adb -s <serial> pull /system/fonts/Roboto-Regular.ttf       ~/fonts/
  adb -s <serial> pull /system/fonts/NotoSansCJK-Regular.ttc  ~/fonts/

  # Optional: pull app-private fonts from APK assets
  adb -s <serial> pull /data/app/<...>/<pkg>-<...>/base.apk    /tmp/app.apk
  unzip -j /tmp/app.apk "assets/*.ttf" "assets/*.otf" -d ~/fonts/

  # Decode
  adb -s <serial> exec-out "dumpsys gfxinfo <pkg> displaylist fresh nodraw" \
    > /tmp/dl.json
  ./dl_glyph_decode.py --fonts ~/fonts /tmp/dl.json -o /tmp/dl_decoded.json

Empirical recovery rate on Settings/Zhihu/Bilibili home screens (see
README.md §DisplayList Usability):
  • Roboto (Latin):      100.0%  (1,519 / 1,519 glyphs)
  • Noto Sans CJK SC:    100.0%  (   493 /    493 glyphs)
  • App-private fonts:     0.0%  unless the corresponding TTF/OTF is supplied
"""

import argparse
import json
import os
import sys
from pathlib import Path

try:
    from fontTools.ttLib import TTFont, TTCollection
except ImportError:
    sys.stderr.write("ERROR: pip install fontTools\n")
    sys.exit(1)


# ── Font loading ───────────────────────────────────────────────────────────

def cmap_of(font):
    """Return {glyph_id: unicode_codepoint} for one TTFont.

    Uses getBestCmap() which prefers Unicode platforms. Multiple Unicodes
    that map to the same glyph (e.g. precomposed + decomposed forms) will
    keep only the first one — acceptable for agent perception.
    """
    cmap = font.getBestCmap()
    glyph_order = font.getGlyphOrder()
    name_to_gid = {n: i for i, n in enumerate(glyph_order)}
    rev = {}
    for u, name in cmap.items():
        gid = name_to_gid.get(name)
        if gid is not None and gid not in rev:
            rev[gid] = u
    return rev


def load_fonts_dir(fonts_dir):
    """Walk fonts_dir, load every .ttf/.otf/.ttc file.

    For TTC (TrueType Collection), union the cmaps of ALL sub-fonts so that
    region-specific glyphs (SC/TC/JP/KR/HK variants of Noto Sans CJK) are
    all available under the same family name.

    Returns a dict {family_name (lowercased): merged_cmap_dict}. Family name
    is taken from the font's `name` table to match what
    SkTypeface::getFamilyName() reports.
    """
    families = {}  # lowercased name -> {gid: unicode}

    def register(font, src):
        try:
            name_table = font['name']
            family = None
            # Prefer English (langID 0x409) family name (id=1) or
            # preferred family (id=16) on Windows platform (3).
            for rec in name_table.names:
                if rec.nameID in (1, 16) and rec.platformID == 3:
                    family = rec.toUnicode().strip()
                    break
            if not family:
                family = font['name'].getDebugName(1) or src.stem
        except Exception:
            family = src.stem
        key = family.lower()
        m = cmap_of(font)
        if key in families:
            for gid, u in m.items():
                families[key].setdefault(gid, u)
        else:
            families[key] = m
        return family, len(m)

    loaded = []
    for path in sorted(Path(fonts_dir).rglob('*')):
        if not path.is_file():
            continue
        suffix = path.suffix.lower()
        try:
            if suffix in ('.ttf', '.otf'):
                font = TTFont(str(path), lazy=True)
                fam, n = register(font, path)
                loaded.append((path.name, fam, n))
            elif suffix == '.ttc':
                ttc = TTCollection(str(path), lazy=True)
                for i, sub in enumerate(ttc.fonts):
                    fam, n = register(sub, path)
                    loaded.append((f"{path.name}#{i}", fam, n))
        except Exception as e:
            print(f"  ! skipped {path.name}: {e}", file=sys.stderr)

    return families, loaded


# ── Decoding ───────────────────────────────────────────────────────────────

def decode_run(family, glyphs, font_table):
    """Translate a run's glyph IDs into a Python str.

    Unmapped glyphs are emitted as U+FFFD (replacement char) so that the
    output remains a valid string and downstream consumers can detect
    failure rate. Returns (decoded_str, num_unmapped).
    """
    cmap = font_table.get(family.lower())
    if cmap is None:
        # Try family aliases (e.g. "sans-serif" → "roboto")
        if family.lower() in ('sans-serif', 'default', 'normal'):
            cmap = font_table.get('roboto')
    if cmap is None:
        return '�' * len(glyphs), len(glyphs)
    chars = []
    miss = 0
    for g in glyphs:
        u = cmap.get(g)
        if u is None:
            chars.append('�')
            miss += 1
        else:
            chars.append(chr(u))
    return ''.join(chars), miss


def extract_dl_blocks(content):
    """Extract balanced {"frame":...} blocks from raw dumpsys output.

    `dumpsys gfxinfo <pkg> displaylist` prefixes each window's JSON with a
    profile-stats header; we walk the content and pull out every JSON
    object that starts with `{"frame"`.
    """
    blocks = []
    pos = 0
    while True:
        i = content.find('{"frame"', pos)
        if i < 0:
            break
        depth = 0
        in_str = False
        esc = False
        end = -1
        for j in range(i, len(content)):
            c = content[j]
            if esc:
                esc = False
                continue
            if c == '\\':
                esc = True
                continue
            if c == '"':
                in_str = not in_str
                continue
            if in_str:
                continue
            if c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0:
                    end = j + 1
                    break
        if end < 0:
            break
        try:
            blocks.append(json.loads(content[i:end]))
        except json.JSONDecodeError:
            pass
        pos = end
    return blocks


def decode_blocks(blocks, font_table):
    """Walk each drawText op in every block, attach a "text" field decoded
    from its runs. Returns (modified_blocks, stats_dict).
    """
    total_glyphs = 0
    miss_glyphs = 0
    per_font = {}  # family -> [glyphs, missing]

    def walk(ops):
        nonlocal total_glyphs, miss_glyphs
        for op in ops:
            if op.get('op') == 'drawText' and 'runs' in op:
                parts = []
                for run in op['runs']:
                    family = run.get('font', '')
                    glyphs = run.get('glyphs', [])
                    s, miss = decode_run(family, glyphs, font_table)
                    parts.append(s)
                    total_glyphs += len(glyphs)
                    miss_glyphs += miss
                    rec = per_font.setdefault(family, [0, 0])
                    rec[0] += len(glyphs)
                    rec[1] += miss
                op['text'] = ''.join(parts)
            # Nested drawDrawable
            if 'ops' in op:
                walk(op['ops'])

    for b in blocks:
        for node in b.get('nodes', []):
            walk(node.get('ops', []))

    return blocks, {
        'total_glyphs': total_glyphs,
        'missed_glyphs': miss_glyphs,
        'recovery_rate': (total_glyphs - miss_glyphs) / total_glyphs if total_glyphs else 1.0,
        'per_font': per_font,
    }


# ── CLI ────────────────────────────────────────────────────────────────────

def main():
    p = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    p.add_argument('input', help="Raw dumpsys gfxinfo ... displaylist output, "
                                 "or use '-' for stdin")
    p.add_argument('--fonts', required=True,
                   help="Directory of .ttf/.otf/.ttc files (pull from "
                        "/system/fonts/ and APK assets)")
    p.add_argument('-o', '--output',
                   help="Write decoded JSON here. If omitted, prints to stdout.")
    p.add_argument('--stats-only', action='store_true',
                   help="Print recovery statistics only; suppress JSON output.")
    args = p.parse_args()

    if not Path(args.fonts).is_dir():
        sys.stderr.write(f"ERROR: fonts dir does not exist: {args.fonts}\n")
        sys.exit(2)

    font_table, loaded = load_fonts_dir(args.fonts)
    print(f"Loaded {len(loaded)} font(s) covering "
          f"{len(font_table)} family name(s):", file=sys.stderr)
    for src, fam, n in loaded:
        print(f"  {src:<35} family={fam!r:<25} glyphs={n}", file=sys.stderr)

    if args.input == '-':
        content = sys.stdin.read()
    else:
        content = Path(args.input).read_text()

    blocks = extract_dl_blocks(content)
    if not blocks:
        sys.stderr.write("ERROR: no DL JSON blocks found in input\n")
        sys.exit(3)
    decoded, stats = decode_blocks(blocks, font_table)

    print(f"\nGlyph recovery: {stats['total_glyphs'] - stats['missed_glyphs']}"
          f" / {stats['total_glyphs']} "
          f"({stats['recovery_rate']*100:.1f}%)", file=sys.stderr)
    if stats['per_font']:
        print("Per-font breakdown:", file=sys.stderr)
        for fam, (total, miss) in sorted(stats['per_font'].items(),
                                         key=lambda x: -x[1][0]):
            recovered = total - miss
            pct = recovered * 100 / total if total else 0
            print(f"  {fam:<30} {recovered:>6}/{total:<6} ({pct:5.1f}%)",
                  file=sys.stderr)

    if args.stats_only:
        return

    out_json = json.dumps(decoded, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(out_json)
        print(f"\nWrote: {args.output}", file=sys.stderr)
    else:
        sys.stdout.write(out_json)


if __name__ == '__main__':
    main()
