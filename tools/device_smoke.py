#!/usr/bin/env python3
"""Installs the release APK on a running emulator/device and walks through it with adb.

Fails if the app crashes or an expected screen does not appear. This is the only test that runs
the real, R8-optimised APK on real Android, so it is what catches "works in tests, crashes on phone".
Usage: device_smoke.py path/to/app.apk [screenshot-dir]
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "app.mygachalist"


def adb(*args, check=True, binary=False):
    r = subprocess.run(["adb", *args], capture_output=True, timeout=120)
    if check and r.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} failed: {r.stderr.decode(errors='replace')}")
    return r.stdout if binary else r.stdout.decode(errors="replace")


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
    xml = adb("shell", "cat", "/sdcard/ui.xml", check=False)
    try:
        root = ET.fromstring(xml[xml.index("<?xml"):])
    except Exception:
        return []
    out = []
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            out.append((n.get("text") or n.get("content-desc") or "", (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def find(text):
    for label, x, y in nodes():
        if text in label:
            return x, y
    return None


def crashed():
    log = adb("logcat", "-d", "-s", "AndroidRuntime:E", check=False)
    return log if "FATAL EXCEPTION" in log and PKG in log else ""


def wait_for(text, timeout=40):
    end = time.time() + timeout
    while time.time() < end:
        hit = find(text)
        if hit:
            print(f"ok: saw '{text}'", flush=True)
            return hit
        if crashed():
            break
        time.sleep(1.5)
    print("---- crash log ----\n" + (crashed() or "(no fatal exception)"))
    print("---- on screen ----\n" + "\n".join(sorted({n[0] for n in nodes() if n[0]})))
    raise SystemExit(f"FAILED: '{text}' did not appear")


def tap(text):
    x, y = wait_for(text)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1)


def shot(directory, name):
    if directory:
        Path(directory).mkdir(parents=True, exist_ok=True)
        (Path(directory) / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))


def main():
    apk = sys.argv[1]
    shots = sys.argv[2] if len(sys.argv) > 2 else ""
    adb("install", "-r", apk)
    adb("logcat", "-c", check=False)
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity")

    wait_for("Releasing soon", timeout=90)
    time.sleep(8)  # let covers load for the screenshot
    shot(shots, "device-01-home")
    adb("shell", "dumpsys", "gfxinfo", PKG, "reset", check=False)
    for _ in range(6):
        adb("shell", "input", "swipe", "500", "1500", "500", "500", "250")
    for _ in range(6):
        adb("shell", "input", "swipe", "500", "500", "500", "1500", "250")
    gfx = adb("shell", "dumpsys", "gfxinfo", PKG, check=False)
    print("---- frame stats while scrolling Home (emulator, indicative only) ----")
    print("\n".join(line for line in gfx.splitlines() if re.search(r"Total frames|Janky frames|percentile", line)))

    tap("Seasonal")
    wait_for("Season")
    tap("Search")
    wait_for("Sort")
    shot(shots, "device-02-search")
    tap("Genshin Impact")
    wait_for("Information")
    time.sleep(5)
    shot(shots, "device-03-detail")
    tap("Add to list")
    wait_for("Status")
    adb("shell", "input", "keyevent", "BACK")
    time.sleep(1)
    adb("shell", "input", "keyevent", "BACK")
    tap("My List")
    wait_for("Playing")
    tap("Profile")
    time.sleep(2)
    shot(shots, "device-04-profile")

    if crashed() or not adb("shell", "pidof", PKG, check=False).strip():
        print(crashed())
        raise SystemExit("FAILED: the app is no longer running")
    print("Device walkthrough passed.")


if __name__ == "__main__":
    main()
