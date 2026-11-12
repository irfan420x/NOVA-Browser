#!/usr/bin/env python3
"""Extract AARs/JARs into a build tree for the manual aapt2/d8 build.

Output:
  build/jars/<name>.jar        - classes for kotlinc classpath + d8
  build/aar/<name>/res/        - resources for aapt2 compile
  build/aar/<name>/AndroidManifest.xml
  build/aar-packages.txt       - "<package> <resdir>" per library with res
  build/jars.txt               - list of jars (one per line)
"""
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEPS = os.path.join(ROOT, "deps")
BUILD = os.path.join(ROOT, "build")
JARS = os.path.join(BUILD, "jars")
AAR = os.path.join(BUILD, "aar")
AAPT2 = os.path.expanduser("~/android-sdk/build-tools/35.0.0/aapt2")

os.makedirs(JARS, exist_ok=True)
os.makedirs(AAR, exist_ok=True)

# Dedupe: keep only the newest version of each artifact in deps/.
def _ver_key(v):
    parts = []
    for p in re.split(r"[.\-]", v):
        parts.append(int(p) if p.isdigit() else 0)
    return tuple(parts)

_groups = {}
for fname in os.listdir(DEPS):
    m = re.match(r"^(.+)-(\d+\.\d+.*?)\.(aar|jar)$", fname)
    if m:
        _groups.setdefault(m.group(1), []).append((m.group(2), fname))
for name, versions in _groups.items():
    if len(versions) > 1:
        versions.sort(key=lambda t: _ver_key(t[0]))
        for _, old in versions[:-1]:
            print(f"dedupe: removing {old} (keeping {versions[-1][1]})")
            os.remove(os.path.join(DEPS, old))

jars = []
packages = []


def axml_package(path):
    """Extract the package name from an AAR AndroidManifest.xml.

    Library manifests are plain text XML; fall back to binary AXML
    string-pool parsing if needed.
    """
    with open(path, "rb") as f:
        data = f.read()
    if data.startswith(b"<?xml"):
        m = re.search(rb'package="([^"]+)"', data)
        return m.group(1).decode() if m else None
    import struct
    if len(data) < 36 or struct.unpack_from("<I", data, 0)[0] != 0x00080003:
        return None
    ctype, hsize, csize = struct.unpack_from("<HHI", data, 8)
    if ctype != 0x0001:
        return None
    scount, stylecount, flags, sstart, stylesstart = struct.unpack_from(
        "<IIIII", data, 8 + 8)
    utf8 = bool(flags & 0x100)
    off_base = 8 + 28
    strings = []
    for i in range(scount):
        off = struct.unpack_from("<I", data, off_base + 4 * i)[0]
        pos = 8 + sstart + off
        try:
            if utf8:
                ln = data[pos]
                pos += 1
                if ln & 0x80:
                    ln = ((ln & 0x7F) << 8) | data[pos]
                    pos += 1
                ln2 = data[pos]
                pos += 1
                if ln2 & 0x80:
                    pos += 1
                s = data[pos:pos + ln].decode("utf-8", "replace")
            else:
                ln = struct.unpack_from("<H", data, pos)[0]
                pos += 2
                if ln & 0x8000:
                    ln = ((ln & 0x7FFF) << 16) | struct.unpack_from("<H", data, pos)[0]
                    pos += 2
                s = data[pos:pos + ln * 2].decode("utf-16-le", "replace")
        except Exception:
            continue
        strings.append(s)
    pat = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")
    for s in strings:
        if pat.match(s) and (s.startswith(("androidx.", "coil.", "com.", "io.",
                                           "org.", "kotlin."))):
            return s
    return None

for fname in sorted(os.listdir(DEPS)):
    src = os.path.join(DEPS, fname)
    base = fname.rsplit(".", 1)[0]
    if fname.endswith(".jar"):
        dst = os.path.join(JARS, base + ".jar")
        shutil.copyfile(src, dst)
        jars.append(dst)
    elif fname.endswith(".aar"):
        out = os.path.join(AAR, base)
        if os.path.exists(out):
            shutil.rmtree(out)
        os.makedirs(out)
        try:
            with zipfile.ZipFile(src) as z:
                z.extractall(out)
        except zipfile.BadZipFile:
            print(f"SKIP bad zip: {fname}")
            continue
        cj = os.path.join(out, "classes.jar")
        if os.path.exists(cj) and os.path.getsize(cj) > 0:
            dst = os.path.join(JARS, base + ".jar")
            shutil.copyfile(cj, dst)
            jars.append(dst)
        manifest = os.path.join(out, "AndroidManifest.xml")
        resdir = os.path.join(out, "res")
        if os.path.isdir(resdir) and os.path.exists(manifest):
            pkg = axml_package(manifest)
            if pkg:
                packages.append((pkg, resdir))
            else:
                print(f"  !! no package found in {fname}")

with open(os.path.join(BUILD, "jars.txt"), "w") as f:
    f.write("\n".join(jars) + "\n")
with open(os.path.join(BUILD, "aar-packages.txt"), "w") as f:
    for pkg, resdir in packages:
        f.write(f"{pkg} {resdir}\n")

print(f"jars: {len(jars)}")
print(f"resource packages: {len(packages)}")
for pkg, _ in packages:
    print("  ", pkg)
