#!/usr/bin/env python3
"""Drive the app on an emulator through uiautomator, for screenshots and smoke checks.

Compose exposes text and content descriptions to the accessibility tree, so elements can be
found the way TalkBack would find them. Usage, one action per argument:

    tools/ui.py wait:Servers tap:"Calegix test server" shot:servers-light back

Actions:
    tap:TEXT        tap the first node whose text or content-desc equals TEXT
    tapc:TEXT       same, but substring match
    maybe:TEXT      tap TEXT if it shows up within a few seconds, otherwise carry on
    wait:TEXT       wait until a node with TEXT (substring) appears
    shot:NAME       save docs/screenshots/NAME.png
    back            press back
    sleep:SECONDS   wait
    dump            print visible texts and descriptions
    adb:ARGS        run `adb shell ARGS`

ANDROID_SERIAL selects the device.
"""

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(ROOT, "docs", "screenshots")


def adb(*args, binary=False):
    out = subprocess.run(["adb", *args], check=True, capture_output=True)
    return out.stdout if binary else out.stdout.decode()


def nodes():
    for _ in range(5):
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
            xml = adb("exec-out", "cat", "/sdcard/ui.xml")
            return list(ET.fromstring(xml).iter("node"))
        except (subprocess.CalledProcessError, ET.ParseError):
            time.sleep(1)
    raise RuntimeError("uiautomator dump kept failing")


def labels(node):
    return [node.get("text") or "", node.get("content-desc") or ""]


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(text, exact=True, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for node in nodes():
            if any((label == text) if exact else (text in label) for label in labels(node)):
                return node
        time.sleep(0.7)
    raise RuntimeError(f"not found: {text!r}")


def main(actions):
    os.makedirs(SHOTS, exist_ok=True)
    for action in actions:
        verb, _, arg = action.partition(":")
        if verb in ("tap", "tapc"):
            x, y = center(find(arg, exact=verb == "tap"))
            adb("shell", "input", "tap", str(x), str(y))
            time.sleep(0.8)
        elif verb == "maybe":
            try:
                x, y = center(find(arg, timeout=6))
                adb("shell", "input", "tap", str(x), str(y))
                time.sleep(0.8)
            except RuntimeError:
                pass
        elif verb == "wait":
            find(arg, exact=False, timeout=30)
        elif verb == "shot":
            time.sleep(0.6)
            with open(os.path.join(SHOTS, f"{arg}.png"), "wb") as f:
                f.write(adb("exec-out", "screencap", "-p", binary=True))
            print(f"saved {arg}.png")
        elif verb == "back":
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(0.8)
        elif verb == "sleep":
            time.sleep(float(arg))
        elif verb == "dump":
            for node in nodes():
                text = [label for label in labels(node) if label]
                if text:
                    print(" | ".join(text), node.get("bounds"))
        elif verb == "adb":
            print(adb("shell", *arg.split()), end="")
        else:
            raise SystemExit(f"unknown action {action}")


if __name__ == "__main__":
    main(sys.argv[1:])
