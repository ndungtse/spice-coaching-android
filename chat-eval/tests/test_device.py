import os

from chat_eval import device


def test_parse_meminfo():
    assert device.parse_meminfo("MemTotal:        4025264 kB\nMemFree: 1 kB") == 3930


def test_parse_version():
    text = "    versionCode=57 minSdk=24 targetSdk=35\n    versionName=2.0.57-dev\n"
    assert device.parse_version(text) == {"version_name": "2.0.57-dev", "version_code": "57"}


def test_tool_prefers_android_home(tmp_path, monkeypatch):
    exe = ".exe" if os.name == "nt" else ""
    (tmp_path / "platform-tools").mkdir()
    (tmp_path / "platform-tools" / f"adb{exe}").write_text("")
    monkeypatch.setenv("ANDROID_HOME", str(tmp_path))
    assert device.tool("adb") == str(tmp_path / "platform-tools" / f"adb{exe}")
