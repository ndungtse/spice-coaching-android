"""adb and emulator access. Every call is an argument list; nothing goes through a shell."""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import time
from pathlib import Path

_EXE = ".exe" if os.name == "nt" else ""
_SUBDIR = {"adb": "platform-tools", "emulator": "emulator"}


def tool(name: str) -> str:
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(env)
        if root:
            candidate = Path(root) / _SUBDIR[name] / f"{name}{_EXE}"
            if candidate.exists():
                return str(candidate)
    found = shutil.which(name)
    if not found:
        raise FileNotFoundError(f"{name} not found in ANDROID_HOME, ANDROID_SDK_ROOT or PATH")
    return found


def adb(serial: str | None, *args: str, timeout: float = 90) -> subprocess.CompletedProcess:
    cmd = [tool("adb")] + (["-s", serial] if serial else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)


def parse_meminfo(text: str) -> int | None:
    m = re.search(r"MemTotal:\s+(\d+)\s+kB", text)
    return int(m.group(1)) // 1024 if m else None


def parse_version(dumpsys: str) -> dict:
    name = re.search(r"versionName=(\S+)", dumpsys)
    code = re.search(r"versionCode=(\d+)", dumpsys)
    return {"version_name": name.group(1) if name else None, "version_code": code.group(1) if code else None}


def list_serials() -> list[str]:
    lines = adb(None, "devices").stdout.splitlines()[1:]
    return [ln.split()[0] for ln in lines if ln.strip().endswith("device")]


def probe(serial: str) -> dict:
    prop = lambda k: adb(serial, "shell", "getprop", k).stdout.strip()
    return {"serial": serial, "model": prop("ro.product.model"), "abi": prop("ro.product.cpu.abi"),
            "ram_mb": parse_meminfo(adb(serial, "shell", "cat", "/proc/meminfo").stdout),
            "emulator": serial.startswith("emulator-")}


def set_airplane(serial: str, on: bool) -> None:
    adb(serial, "shell", "cmd", "connectivity", "airplane-mode", "enable" if on else "disable")
    time.sleep(2)
    actual = adb(serial, "shell", "settings", "get", "global", "airplane_mode_on").stdout.strip() in {"1", "true"}
    if actual != on:
        raise RuntimeError(f"airplane mode should be {'on' if on else 'off'} but the device reports otherwise")


def pid(serial: str, package: str) -> str | None:
    return adb(serial, "shell", "pidof", "-s", package).stdout.strip() or None


def clear_logcat(serial: str) -> None:
    adb(serial, "logcat", "-c")


def logcat(serial: str, package: str, tags: list[str]) -> list[str]:
    """The app's buffered log lines for the given tags, plus any crash lines, with no line cap."""
    args = ["logcat", "-d", "-v", "time"]
    app_pid = pid(serial, package)
    if app_pid:
        args += ["--pid", app_pid]
    lines = adb(serial, *args, timeout=60).stdout.splitlines()
    keep = tuple(f"/{t}(" for t in tags) + tuple(f"/{t} " for t in tags)
    return [ln for ln in lines if any(k in ln for k in keep) or "Fatal signal" in ln or "has died" in ln]


def apk_version(serial: str, package: str) -> dict:
    return parse_version(adb(serial, "shell", "dumpsys", "package", package).stdout)


def list_avds() -> list[str]:
    out = subprocess.run([tool("emulator"), "-list-avds"], capture_output=True, text=True, encoding="utf-8")
    return [ln.strip() for ln in out.stdout.splitlines() if ln.strip() and not ln.startswith("INFO")]


def boot(avd: str, timeout_s: float = 300) -> str:
    before = set(list_serials())
    subprocess.Popen([tool("emulator"), "-avd", avd], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        for serial in set(list_serials()) - before:
            if adb(serial, "shell", "getprop", "sys.boot_completed").stdout.strip() == "1":
                return serial
        time.sleep(3)
    raise TimeoutError(f"{avd} did not finish booting in {timeout_s:.0f}s")


def doctor(settings: dict) -> int:
    import sys
    problems = []
    print(f"python {sys.version.split()[0]}")
    for name in ("adb", "emulator"):
        try:
            print(f"{name}: {tool(name)}")
        except FileNotFoundError as e:
            problems.append(str(e))
    appium = shutil.which("appium")
    print(f"appium: {appium or 'not found'}")
    if not appium:
        problems.append("appium not found; install with `npm install -g appium` and `appium driver install uiautomator2`")
    else:
        drivers = subprocess.run([appium, "driver", "list", "--installed"], capture_output=True, text=True,
                                 encoding="utf-8", errors="replace")
        if "uiautomator2" not in (drivers.stdout + drivers.stderr):
            problems.append("the UiAutomator2 driver is not installed: `appium driver install uiautomator2`")
    for serial in list_serials():
        info = probe(serial)
        tier = "2gb" if (info["ram_mb"] or 0) < 3072 else "4gb"
        print(f"device {serial}: {info['model']} abi={info['abi']} ram={info['ram_mb']}MB tier={tier}")
        apk = settings.get("local", {}).get("apk", "")
        if info["emulator"] and info["abi"].startswith("x86") and apk and "x86" not in Path(apk).name:
            problems.append(f"{serial} is an x86 emulator and the APK looks arm64-only; the app crashes after login")
    for p in problems:
        print(f"PROBLEM: {p}")
    return 1 if problems else 0
