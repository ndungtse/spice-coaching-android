"""Driving the host app through Appium. Every selector is in the table below, in both UI
languages, so a changed label is fixed in one place."""
from __future__ import annotations

import re
import time
from collections import Counter
from pathlib import Path

from appium import webdriver
from appium.options.android import UiAutomator2Options
from appium.webdriver.common.appiumby import AppiumBy
from selenium.common.exceptions import TimeoutException
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait

SEL = {
    "more_options": ("More options", "আরও বিকল্প"),
    "send": ("Send message", "বার্তা পাঠান"),
    "ask_ai": ("Ask AI Assistant", "এআই সহকারীকে জিজ্ঞাসা করুন"),
    "drawer_open": ("Open navigation drawer", "নেভিগেশন মেনু খুলুন"),
    "assistant_menu": ("CHW Assistant",),
    "mode_sheet_open": ("Change how answers are found", "উত্তর কীভাবে পাওয়া যায় তা বদলান"),
    "mode_sheet_title": ("How answers are found", "উত্তর কীভাবে পাওয়া যায়"),
    "mode_online": ("Online", "অনলাইন"),
    "mode_on_device": ("On this phone", "এই ফোনে"),
    "sheet_close": ("Close sheet", "শিট বন্ধ করুন"),
    "style_direct": ("Exact words from your guide", "নির্দেশিকার হুবহু লেখা"),
    "style_assisted": ("Explained in simple words", "সহজ ভাষায় বুঝিয়ে দেওয়া"),
    "exact_confirm": ("Use exact wording", "হুবহু লেখা ব্যবহার করুন"),
    "download": ("Download both", "Download AI Model", "Download"),
    "dialog_ok": ("Allow", "ALLOW", "Yes", "OK", "Continue", "ঠিক আছে", "হ্যাঁ"),
}
# Texts that are part of the chat screen itself, never an answer.
CHROME = {"AI Coach", "এআই কোচ", "Online", "Offline", "অনলাইন", "অফলাইন", "More options", "আরও বিকল্প",
          "Send message", "বার্তা পাঠান", "Type a message…", "বার্তা লিখুন…", "On this phone", "এই ফোনে",
          "Change", "বদলান", "How answers are found", "উত্তর কীভাবে পাওয়া যায়"}
_CITATION = re.compile(r"·\s*p\.\s*\d+|extracted[-_]pages|training[-_]module|[-_]pages[-_](en|bn)", re.I)
_BENGALI = re.compile(r"[ঀ-৿]")


def connect(settings: dict, serial: str) -> webdriver.Remote:
    cap = settings["capture"]
    o = UiAutomator2Options()
    o.platform_name, o.automation_name, o.device_name, o.udid = "Android", "UiAutomator2", "Android", serial
    o.app_package, o.app_activity = cap["package"], cap["launcher_activity"]
    o.auto_grant_permissions, o.no_reset, o.new_command_timeout = True, True, 600
    o.skip_device_initialization, o.ignore_hidden_api_policy_error = True, True
    return webdriver.Remote(cap["appium_server"], options=o)


def _click_text(driver, labels: tuple[str, ...], timeout: float = 3) -> bool:
    for label in labels:
        try:
            WebDriverWait(driver, timeout).until(EC.element_to_be_clickable(
                (AppiumBy.ANDROID_UIAUTOMATOR, f'new UiSelector().text("{label}")'))).click()
            return True
        except TimeoutException:
            continue
    return False


def _click_desc(driver, labels: tuple[str, ...], timeout: float = 3) -> bool:
    for label in labels:
        try:
            WebDriverWait(driver, timeout).until(EC.element_to_be_clickable((AppiumBy.ACCESSIBILITY_ID, label))).click()
            return True
        except TimeoutException:
            continue
    return False


def _present(driver, by: str, value: str) -> bool:
    try:
        driver.find_element(by, value)
        return True
    except Exception:
        return False


def is_chat_ready(driver) -> bool:
    return any(_present(driver, AppiumBy.ACCESSIBILITY_ID, s) for s in SEL["more_options"]) and \
        _present(driver, AppiumBy.CLASS_NAME, "android.widget.EditText")


def _is_home(driver) -> bool:
    return is_chat_ready(driver) or any(_present(driver, AppiumBy.ACCESSIBILITY_ID, s) for s in SEL["drawer_open"])


def _type(driver, element, text: str) -> None:
    element.click()
    try:
        element.clear()
    except Exception:
        pass
    try:
        element.send_keys(text)
    except Exception:
        driver.set_clipboard_text(text)
        driver.press_keycode(279)  # KEYCODE_PASTE


def login(driver, package: str, username: str, password: str) -> None:
    deadline = time.time() + 90
    while time.time() < deadline:
        _click_text(driver, SEL["dialog_ok"], timeout=1)
        if _is_home(driver):
            return
        if _present(driver, AppiumBy.ID, f"{package}:id/userName"):
            break
        time.sleep(1)
    else:
        raise TimeoutException("neither the login screen nor the home screen appeared")
    wait = WebDriverWait(driver, 60)
    _type(driver, wait.until(EC.element_to_be_clickable((AppiumBy.ID, f"{package}:id/userName"))), username)
    if not _present(driver, AppiumBy.ID, f"{package}:id/password"):
        driver.press_keycode(66)  # the two-step login asks for the password after Enter
        time.sleep(0.8)
    _type(driver, wait.until(EC.element_to_be_clickable((AppiumBy.ID, f"{package}:id/password"))), password)
    try:
        driver.hide_keyboard()
    except Exception:
        pass
    wait.until(EC.element_to_be_clickable((AppiumBy.ID, f"{package}:id/btnLogin"))).click()
    end = time.time() + 300
    while time.time() < end and not _is_home(driver):
        _click_text(driver, SEL["dialog_ok"], timeout=1)
        time.sleep(2)
    if not _is_home(driver):
        raise TimeoutException("home screen did not appear after login")


def open_assistant(driver, package: str, timeout_s: float = 600) -> None:
    if is_chat_ready(driver):
        return
    if not (_click_desc(driver, SEL["ask_ai"], timeout=10) or
            (_click_desc(driver, SEL["drawer_open"]) and _click_text(driver, SEL["assistant_menu"], timeout=5))):
        raise TimeoutException("could not open the assistant from the home screen")
    _click_text(driver, ("Yes",), timeout=3)
    end = time.time() + timeout_s
    while time.time() < end:
        if is_chat_ready(driver):
            return
        _click_text(driver, SEL["download"], timeout=1)
        time.sleep(2)
    raise TimeoutException("the chat screen never became ready")


def _keep_download_and_confirm(driver) -> None:
    """Switching to exact words asks whether to also remove the downloaded model, with the
    box ticked. It is unticked first, so a test run never deletes the model."""
    for box in driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, "new UiSelector().checkable(true)"):
        if box.get_attribute("checked") == "true":
            box.click()
            time.sleep(0.5)
    _click_text(driver, SEL["exact_confirm"], timeout=5)


def wait_ready(driver, timeout_s: float = 600) -> None:
    """Waits on the chat screen until it accepts a question, starting a pending model
    download if its button is showing."""
    end = time.time() + timeout_s
    while time.time() < end:
        if is_chat_ready(driver):
            return
        _click_text(driver, SEL["download"], timeout=1)
        time.sleep(2)
    raise TimeoutException("the chat screen never became ready")


def set_answer_mode(driver, mode: str, style: str | None = None) -> None:
    """Selects online or on this phone, and for on this phone the answer style: `direct`
    (exact words from the guide) or `assisted` (explained in simple words)."""
    target = SEL["mode_online"] if mode == "online" else SEL["mode_on_device"]
    if not any(_present(driver, AppiumBy.ANDROID_UIAUTOMATOR, f'new UiSelector().textContains("{t}")')
               for t in SEL["mode_sheet_title"]):
        if not _click_desc(driver, SEL["mode_sheet_open"], timeout=4) and not _click_text(driver, ("Change", "বদলান")):
            raise TimeoutException("could not open the answer-mode sheet")
        time.sleep(0.8)
    if not _click_text(driver, target, timeout=8):
        raise TimeoutException(f"answer mode option not found: {target}")
    time.sleep(0.5)
    if style:
        labels = SEL["style_direct"] if style == "direct" else SEL["style_assisted"]
        if not _click_text(driver, labels, timeout=8):
            raise TimeoutException(f"answer style option not found: {labels}")
        time.sleep(1)
        if style == "direct":
            _keep_download_and_confirm(driver)
    if not _click_desc(driver, SEL["sheet_close"], timeout=2) and not _click_text(driver, SEL["sheet_close"], timeout=1):
        driver.press_keycode(4)


def restart(driver, serial: str, package: str, activity: str) -> None:
    from chat_eval import device
    device.adb(serial, "shell", "am", "force-stop", package)
    device.adb(serial, "shell", "am", "start", "-W", "-n", f"{package}/{activity}")
    time.sleep(3)


def visible_texts(driver) -> list[str]:
    out = []
    for el in driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.TextView"):
        try:
            value = (el.text or "").strip()
        except Exception:
            continue
        if value:
            out.append(value)
    return out


def send(driver, text: str) -> None:
    field = WebDriverWait(driver, 30).until(EC.element_to_be_clickable((AppiumBy.CLASS_NAME, "android.widget.EditText")))
    _type(driver, field, text)
    time.sleep(0.5)
    # Compose replaces the field node as it recomposes, so the check reads a fresh lookup.
    typed = driver.find_element(AppiumBy.CLASS_NAME, "android.widget.EditText").text or ""
    if typed.strip()[:18] != text.strip()[:18]:
        raise RuntimeError("the question did not reach the input field")
    if not _click_desc(driver, SEL["send"], timeout=10):
        raise TimeoutException("send button not found")


def _is_answer_text(t: str, question: str) -> bool:
    return t not in CHROME and t != question and len(t) >= 8 and not _CITATION.search(t) \
        and bool(_BENGALI.search(t) or len(t) >= 40)


def newest_answer(driver, before: list[str], question: str) -> str:
    """The answer bubble for `question`: the first answer-like text after the question's own
    bubble. An identical earlier answer elsewhere on screen does not hide it. When a long
    answer has pushed the question off screen, the text whose on-screen count grew since the
    send is taken instead."""
    try:
        size = driver.get_window_size()
        driver.swipe(size["width"] // 2, int(size["height"] * 0.75), size["width"] // 2, int(size["height"] * 0.35), 250)
    except Exception:
        pass
    texts = visible_texts(driver)
    if question in texts:
        last = len(texts) - 1 - texts[::-1].index(question)
        after = [t for t in texts[last + 1:] if _is_answer_text(t, question)]
        return after[0] if after else ""
    grew = Counter(texts) - Counter(before)
    candidates = [t for t in texts if grew[t] > 0 and _is_answer_text(t, question)]
    return candidates[-1] if candidates else ""


def dump_ui(driver, path: Path) -> None:
    path.write_text(driver.page_source, encoding="utf-8")
