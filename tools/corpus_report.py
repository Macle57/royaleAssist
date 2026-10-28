"""Pull the corpus evaluation results from a device and print per-script accuracy.

Run the batch first:
  adb shell am start -S -n com.macle.royaleassist/.debug.DebugActivity --ez corpus true
then (after logcat shows "CORPUS SUMMARY"):
  python tools/corpus_report.py [--serial emulator-5554] [--file local.json] [--save out.json] [--failures]
"""
import argparse
import collections
import json
import os
import subprocess
import sys
import tempfile

REMOTE = "/sdcard/Android/data/com.macle.royaleassist/files/corpus-results.json"


def pull(serial):
    fd, local = tempfile.mkstemp(suffix=".json")
    os.close(fd)
    cmd = ["adb"] + (["-s", serial] if serial else []) + ["pull", REMOTE, local]
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    subprocess.run(cmd, check=True, capture_output=True, env=env)
    return local


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--file", help="read a local results file instead of pulling")
    ap.add_argument("--save", help="copy the pulled results here")
    ap.add_argument("--failures", action="store_true", help="list items whose name or clan did not match")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    path = a.file or pull(a.serial)
    rows = json.load(open(path, encoding="utf-8"))
    if a.save:
        json.dump(rows, open(a.save, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    by = collections.defaultdict(list)
    for r in rows:
        by[r["script"]].append(r)
    hdr = f"{'script':18} {'n':>3} {'name':>9} {'exact':>9} {'clan':>9} {'ms(avg)':>8}"
    print(hdr)
    print("-" * len(hdr))

    def line(label, rs):
        n = len(rs)
        nm = sum(r["nameMatch"] for r in rs)
        ex = sum(r["nameExact"] for r in rs)
        cl = sum(r["clanMatch"] for r in rs)
        ms = sum(r["ms"] for r in rs) / max(n, 1)
        print(f"{label:18} {n:>3} {nm:>3} {100*nm/n:>4.0f}% {ex:>3} {100*ex/n:>4.0f}% {cl:>3} {100*cl/n:>4.0f}% {ms:>8.0f}")

    for k in sorted(by, key=lambda k: -len(by[k])):
        line(k, by[k])
    print("-" * len(hdr))
    line("ALL", rows)
    sure = [r for r in rows if not r["uncertain"]]
    line("ALL (sure labels)", sure)

    if a.failures:
        print()
        for r in rows:
            if r["nameMatch"] and r["clanMatch"]:
                continue
            print(f"{r['file'][6:]:24} [{r['script']}] name {'ok' if r['nameMatch'] else 'XX'} "
                  f"'{r['readName']}' vs '{r['expectedName']}' ({r['nameSim']:.2f}); clan {'ok' if r['clanMatch'] else 'XX'} "
                  f"'{r['readClan']}' vs '{r['expectedClan']}'; grown={r['grown']}")


if __name__ == "__main__":
    main()
