#!/usr/bin/env python3
"""Resolve Maven artifacts (incl. transitive compile deps) using curl.

Usage: resolve_deps.py <out-dir> <group:artifact:version> [...]
Downloads .pom for each artifact, parses compile/runtime dependencies,
recurses, then downloads the artifact itself (.aar preferred, else .jar).
Uses curl so the runtime egress proxy (env) is honoured.
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

GOOGLE = "https://dl.google.com/dl/android/maven2"
CENTRAL = "https://repo.maven.apache.org/maven2"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}

OUT = sys.argv[1]
ROOTS = sys.argv[2:]
os.makedirs(OUT, exist_ok=True)

_cache = {}
_seen = set()
_queue = [tuple(r.split(":")) for r in ROOTS]
_downloaded = []


def curl(url, dest):
    r = subprocess.run(
        ["curl", "-sS", "-L", "--max-time", "120", "-o", dest, url],
        capture_output=True, text=True)
    if r.returncode != 0 or not os.path.exists(dest) or os.path.getsize(dest) == 0:
        return False
    # reject HTML error pages (Google returns 404 HTML with leading newline)
    with open(dest, "rb") as f:
        head = f.read(512).lstrip()
    if head.startswith(b"<html") or head.startswith(b"<!DOCTYPE"):
        os.remove(dest)
        return False
    # archives must be real zips
    if dest.endswith((".aar", ".jar", ".zip")) and not head.startswith(b"PK\x03\x04"):
        os.remove(dest)
        return False
    return True


def group_path(g):
    return g.replace(".", "/")


def fetch_pom(g, a, v):
    key = (g, a, v)
    if key in _cache:
        return _cache[key]
    path = f"{group_path(g)}/{a}/{v}/{a}-{v}.pom"
    tmp = os.path.join(OUT, ".tmp.pom")
    for base in (GOOGLE, CENTRAL):
        if curl(f"{base}/{path}", tmp):
            try:
                root = ET.parse(tmp).getroot()
                _cache[key] = root
                return root
            except ET.ParseError:
                pass
    _cache[key] = None
    return None


def text_of(el, tag):
    c = el.find(f"m:{tag}", NS)
    return c.text.strip() if c is not None and c.text else ""


def resolve_props(root):
    props = {}
    parent = root.find("m:parent", NS)
    if parent is not None:
        pg, pa, pv = (text_of(parent, "groupId"), text_of(parent, "artifactId"),
                      text_of(parent, "version"))
        proot = fetch_pom(pg, pa, pv)
        if proot is not None:
            props.update(resolve_props(proot))
    for p in root.findall("m:properties/m:*", NS):
        if p.text:
            props[p.tag.split("}", 1)[1]] = p.text.strip()
    return props


def subst(value, props, root):
    def repl(m):
        k = m.group(1)
        if k == "project.version":
            return text_of(root, "version") or ""
        if k == "project.groupId":
            return text_of(root, "groupId") or ""
        if k == "project.artifactId":
            return text_of(root, "artifactId") or ""
        return props.get(k, m.group(0))
    prev = None
    while prev != value:
        prev = value
        value = re.sub(r"\$\{([^}]+)\}", repl, value)
    return value


def pom_deps(root, props):
    deps = []
    mgmt = {}
    for dm in root.findall("m:dependencyManagement/m:dependencies/m:dependency", NS):
        g, a = text_of(dm, "groupId"), text_of(dm, "artifactId")
        v = text_of(dm, "version")
        if g and a and v:
            mgmt[(g, a)] = subst(v, props, root)
    for d in root.findall("m:dependencies/m:dependency", NS):
        scope = text_of(d, "scope") or "compile"
        if scope in ("test", "provided", "system"):
            continue
        if text_of(d, "optional") == "true":
            continue
        g, a = text_of(d, "groupId"), text_of(d, "artifactId")
        v = text_of(d, "version")
        if not g or not a:
            continue
        g = subst(g, props, root)
        a = subst(a, props, root)
        v = subst(v, props, root) if v else mgmt.get((g, a), "")
        if not v or v.startswith("[") or v.startswith("("):
            continue
        deps.append((g, a, v))
    return deps


def fetch_metadata_version(g, a):
    path = f"{group_path(g)}/{a}/maven-metadata.xml"
    tmp = os.path.join(OUT, ".tmp.meta")
    for base in (GOOGLE, CENTRAL):
        if curl(f"{base}/{path}", tmp):
            try:
                root = ET.parse(tmp).getroot()
                rel = root.find("m:versioning/m:release", NS)
                if rel is not None and rel.text:
                    return rel.text.strip()
            except ET.ParseError:
                pass
    return None


def download_artifact(g, a, v):
    path = f"{group_path(g)}/{a}/{v}/{a}-{v}"
    dest_base = os.path.join(OUT, f"{a}-{v}")
    for ext in ("aar", "jar"):
        dest = dest_base + "." + ext
        if os.path.exists(dest):
            return dest
        for base in (GOOGLE, CENTRAL):
            if curl(f"{base}/{path}.{ext}", dest):
                return dest
    return None


while _queue:
    g, a, v = _queue.pop(0)
    if (g, a) in _seen:
        continue
    _seen.add((g, a))
    print(f"resolving {g}:{a}:{v}", flush=True)
    root = fetch_pom(g, a, v)
    if root is None:
        print(f"  !! no POM for {g}:{a}:{v}", flush=True)
        continue
    props = resolve_props(root)
    dest = download_artifact(g, a, v)
    if dest:
        _downloaded.append(dest)
        print(f"  -> {os.path.basename(dest)}", flush=True)
    else:
        print(f"  !! no artifact for {g}:{a}:{v}", flush=True)
    for dep in pom_deps(root, props):
        if (dep[0], dep[1]) not in _seen and dep not in _queue:
            _queue.append(dep)

print(f"\nDownloaded {len(_downloaded)} artifacts into {OUT}")
