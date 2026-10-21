#!/usr/bin/env python3
"""
Guard against code that crashes on Android because of invokedynamic.

dx (our dexer) copies invokedynamic as it is, but Android has no
java.lang.invoke.LambdaMetafactory: a lambda compiled that way throws
NoSuchMethodError the first time it runs. Our own code is compiled with
-Xlambdas=class, but a few functions of the Kotlin standard library
(compareBy with several selectors, nullsFirst/nullsLast, then, ...) use
invokedynamic inside.

This script builds the call graph of the app classes plus the libraries,
marks every method that contains invokedynamic, follows callers up, and
fails if any app method can reach one.

  check-indy.py <app classes dir> <library.jar> [<library.jar> ...]
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile


def class_files(root):
    for d, _, files in os.walk(root):
        for f in files:
            if f.endswith(".class"):
                yield os.path.join(d, f)


def javap(paths):
    """javap -c -p on many class files at once (one JVM per batch)."""
    env = dict(os.environ)
    env.pop("JAVA_TOOL_OPTIONS", None)
    out = []
    for i in range(0, len(paths), 300):
        p = subprocess.run(["javap", "-c", "-p"] + paths[i:i + 300], capture_output=True, text=True, env=env)
        out.append(p.stdout)
    return "\n".join(out)


CLASS_RE = re.compile(r"^(?:[\w$]+ )*(?:class|interface|enum) ([\w.$]+)(?:<.*?>)?(?: extends ([\w.$]+))?")
METHOD_RE = re.compile(r"^  (?:[\w$<>\[\],.? ]+ )?([\w$<>]+)\(([^)]*)\)")
CALL_RE = re.compile(r"invoke(?:static|virtual|special|interface)\s+#\d+\s+// (?:Interface)?Method ([\w/$]+)\.([\w$<>\"]+):")


SUPER = {}


def graph(text):
    """method key -> set of called method keys; and the set of methods with invokedynamic.
    Also fills SUPER (class -> superclass), to resolve inherited static methods
    (Kotlin facade classes such as ComparisonsKt inherit them from their parts)."""
    calls, indy = {}, set()
    cls = None
    cur = None
    for line in text.splitlines():
        m = CLASS_RE.match(line)
        if m:
            cls = m.group(1).replace(".", "/")
            if m.group(2):
                SUPER[cls] = m.group(2).replace(".", "/")
            cur = None
            continue
        m = METHOD_RE.match(line)
        if m and cls and not line.startswith("    "):
            name = m.group(1)
            if name == cls.split("/")[-1].split("$")[-1] or name == cls.replace("/", "."):
                name = "<init>"
            cur = cls + "." + name.strip('"')
            calls.setdefault(cur, set())
            continue
        if cur is None:
            continue
        if "invokedynamic" in line:
            indy.add(cur)
        m = CALL_RE.search(line)
        if m:
            calls[cur].add(m.group(1) + "." + m.group(2).strip('"'))
    return calls, indy


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    app_dir, jars = sys.argv[1], sys.argv[2:]
    app = [p for p in class_files(app_dir) if "/app/pensy/" in p.replace(os.sep, "/")]
    app_calls, app_indy = graph(javap(app))
    calls = dict(app_calls)
    indy = set(app_indy)
    with tempfile.TemporaryDirectory() as tmp:
        for j in jars:
            d = os.path.join(tmp, os.path.basename(j))
            with zipfile.ZipFile(j) as z:
                for n in z.namelist():
                    if n.endswith(".class") and not n.startswith("META-INF/"):
                        z.extract(n, d)
            c, i = graph(javap(list(class_files(d))))
            calls.update(c)
            indy |= i
    # A call to C.m where C does not declare m goes to the nearest superclass that does.
    def resolve(key):
        cls, name = key.rsplit(".", 1)
        seen = 0
        while key not in calls and cls in SUPER and seen < 20:
            cls = SUPER[cls]
            key = cls + "." + name
            seen += 1
        return key
    calls = {m: {resolve(c) for c in callees} for m, callees in calls.items()}
    app_calls = {m: calls[m] for m in app_calls}
    # Everything that can reach an invokedynamic.
    bad = set(indy)
    changed = True
    while changed:
        changed = False
        for m, callees in calls.items():
            if m not in bad and callees & bad:
                bad.add(m)
                changed = True
    hits = sorted(m for m in app_calls if m in bad)
    if hits:
        print("check-indy: these app methods reach invokedynamic (they would crash on Android):")
        for m in hits:
            via = sorted(c for c in calls[m] if c in bad)
            print("  " + m + "  ->  " + ", ".join(via))
        return 1
    print("check-indy: OK (%d app methods, %d library methods with invokedynamic, none reachable)" % (len(app_calls), len(indy)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
