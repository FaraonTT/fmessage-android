#!/usr/bin/env python3
"""Post-processes generated icon drawables: normalizes SVG path data so Android's
VectorDrawable parser reads it identically on every API level (explicit arc flags and
separators), and adds the extra icons used by the redesigned UI."""
import glob, os, re

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable")
CMD = "MmLlHhVvCcSsQqTtAaZz"
ARITY = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}
NUM = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def normalize(d):
    i, n, out = 0, len(d), []

    def skip():
        nonlocal i
        while i < n and d[i] in " ,\t\r\n":
            i += 1

    def num():
        nonlocal i
        skip()
        m = NUM.match(d, i)
        if not m:
            raise ValueError("bad path near: " + d[i:i + 12])
        i = m.end()
        return m.group(0)

    def flag():
        nonlocal i
        skip()
        if i >= n or d[i] not in "01":
            raise ValueError("bad arc flag near: " + d[i:i + 12])
        f = d[i]
        i += 1
        return f

    cmd = None
    while True:
        skip()
        if i >= n:
            break
        if d[i] in CMD:
            cmd = d[i]
            i += 1
            if cmd in "Zz":
                out.append("Z")
                continue
        elif cmd is None or cmd in "Zz":
            raise ValueError("path must start with a command")
        first = True
        while True:
            skip()
            if i >= n or d[i] in CMD:
                if first and ARITY[cmd.upper()] != 0:
                    raise ValueError("missing args for " + cmd)
                break
            c = cmd.upper()
            if c == "A":
                args = [num(), num(), num(), flag(), flag(), num(), num()]
            else:
                args = [num() for _ in range(ARITY[c])]
            letter = cmd
            if not first:
                letter = {"M": "L", "m": "l"}.get(cmd, cmd)
            out.append(letter + " " + " ".join(args))
            first = False
    return " ".join(out)


# name -> list of (pathData, filled)
EXTRA = {
    "ic_shuffle": [("M16 3h5v5M4 20L21 3M21 16v5h-5M15 15l6 6M4 4l5 5", False)],
    "ic_repeat": [("M17 1l4 4-4 4M3 11V9a4 4 0 014-4h14M7 23l-4-4 4-4M21 13v2a4 4 0 01-4 4H3", False)],
    "ic_repeat_one": [("M17 1l4 4-4 4M3 11V9a4 4 0 014-4h14M7 23l-4-4 4-4M21 13v2a4 4 0 01-4 4H3M11 10h1v4", False)],
    "ic_heart": [("M20.84 4.61a5.5 5.5 0 00-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 00-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 000-7.78z", False)],
    "ic_heart_filled": [("M20.84 4.61a5.5 5.5 0 00-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 00-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 000-7.78z", True)],
    "ic_refresh": [("M23 4v6h-6M1 20v-6h6M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15", False)],
    "ic_chevron_down": [("M6 9l6 6 6-6", False)],
    "ic_volume": [("M11 5L6 9H2v6h4l5 4V5z", False), ("M15.54 8.46a5 5 0 010 7.07M19.07 4.93a10 10 0 010 14.14", False)],
    "ic_play_filled": [("M7 4.5v15a1 1 0 001.5.86l12-7.5a1 1 0 000-1.72l-12-7.5A1 1 0 007 4.5z", True)],
    "ic_pause_filled": [("M7 4h3v16H7zM14 4h3v16h-3z", True)],
    "ic_skip_prev": [("M19 20L9 12l10-8zM5 19V5", False)],
    "ic_music_note": [("M9 18V5l12-2v13M6 15a3 3 0 100 6 3 3 0 000-6zM18 13a3 3 0 100 6 3 3 0 000-6z", False)],
}

HEAD = ('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" '
        'android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">')


def write_extra():
    for name, paths in EXTRA.items():
        xml = HEAD
        for d, filled in paths:
            xml += '<path android:pathData="%s"' % normalize(d)
            if filled:
                xml += ' android:fillColor="#FF000000"'
            xml += (' android:strokeColor="#FF000000" android:strokeWidth="2" '
                    'android:strokeLineCap="round" android:strokeLineJoin="round"/>')
        xml += "</vector>\n"
        with open(os.path.join(OUT, name + ".xml"), "w") as f:
            f.write(xml)


def fix_existing():
    count = 0
    for f in glob.glob(os.path.join(OUT, "ic_*.xml")):
        s = open(f).read()
        fixed = re.sub(r'android:pathData="([^"]*)"', lambda m: 'android:pathData="%s"' % normalize(m.group(1)), s)
        if fixed != s:
            open(f, "w").write(fixed)
            count += 1
    return count


if __name__ == "__main__":
    write_extra()
    print("normalized", fix_existing(), "drawables;", len(EXTRA), "extra icons")
