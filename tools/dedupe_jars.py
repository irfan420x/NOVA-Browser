#!/usr/bin/env python3
"""Remove duplicate classes across jars so d8 does not fail.

Reads jars.txt (one jar per line), copies each jar to dex-jars/ with any
class already provided by an earlier jar removed. Jars are processed in
newest-version-first order per artifact so newer classes win.
Writes dex-jars.txt (list of cleaned jars).
"""
import os
import re
import shutil
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD = os.path.join(ROOT, "build")
OUT = os.path.join(BUILD, "dex-jars")


def ver_key(v):
    parts = []
    for p in re.split(r"[.\-]", v):
        parts.append(int(p) if p.isdigit() else 0)
    return tuple(parts)


def name_ver(path):
    base = os.path.basename(path)
    m = re.match(r"^(.+)-(\d+\.\d+.*?)\.jar$", base)
    if m:
        return m.group(1), m.group(2)
    return base.replace(".jar", ""), ""


def main():
    jars_file = sys.argv[1] if len(sys.argv) > 1 else os.path.join(BUILD, "jars.txt")
    with open(jars_file) as f:
        jars = [ln.strip() for ln in f if ln.strip()]
    # newest version first per artifact name
    jars.sort(key=lambda p: (name_ver(p)[0], ver_key(name_ver(p)[1])), reverse=True)
    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)
    seen = {}
    out_list = []
    for jar in jars:
        name = os.path.basename(jar)
        try:
            zin = zipfile.ZipFile(jar)
        except zipfile.BadZipFile:
            print(f"SKIP bad jar: {jar}")
            continue
        entries = zin.namelist()
        dupes = [e for e in entries if e.endswith(".class") and e in seen]
        keep = [e for e in entries if e not in dupes]
        if not keep:
            print(f"drop empty jar: {name}")
            continue
        dst = os.path.join(OUT, name)
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
            for e in keep:
                zout.writestr(e, zin.read(e))
                if e.endswith(".class"):
                    seen.setdefault(e, name)
        if dupes:
            print(f"cleaned {name}: removed {len(dupes)} duplicate classes")
        out_list.append(dst)
    with open(os.path.join(BUILD, "dex-jars.txt"), "w") as f:
        f.write("\n".join(out_list) + "\n")
    print(f"dex jars: {len(out_list)}")


if __name__ == "__main__":
    main()
