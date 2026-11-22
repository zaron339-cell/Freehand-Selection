"""Check the optional request with the real IME; does not send a model request."""
import pathlib
import re
import time
import ui_smoke as ui

ui.BASE = [ui.ADB, "-s", "emulator-5554"]
out = pathlib.Path(__file__).resolve().parents[1] / "tmp"


def image(name):
    remote = "/sdcard/freehand-memory-smoke.png"
    ui.adb("shell", "screencap", "-p", remote)
    ui.adb("pull", remote, str(out / name))


ui.adb("shell", "am", "force-stop", "io.freehand.selection")
ui.adb("shell", "am", "start", "-n", "io.freehand.selection/.MainActivity")
time.sleep(0.5)
ui.click("先在练习页试一试", scroll=True)
ui.click("矩形框选")
root = ui.dump()
canvas = next(n for n in root.iter("node") if n.get("content-desc", "").startswith("选区画布"))
before = ui.bounds(canvas)
x1, y1, x2, y2 = before
scale = min((x2 - x1) / 1200, (y2 - y1) / 1600)
left = x1 + ((x2 - x1) - 1200 * scale) / 2
top = y1 + ((y2 - y1) - 1600 * scale) / 2
ui.adb("shell", "input", "swipe", str(int(left + 60 * scale)), str(int(top + 300 * scale)),
       str(int(left + 650 * scale)), str(int(top + 850 * scale)), "350")
assert ui.find("确认").get("enabled") == "true"
root = ui.dump()
editor = next(n for n in root.iter("node") if n.get("class") == "android.widget.EditText")
ui.tap(editor)
ui.adb("shell", "input", "text", "Translate%sbriefly")
time.sleep(0.4)
root = ui.dump()
after = ui.bounds(next(n for n in root.iter("node") if n.get("content-desc", "").startswith("选区画布")))
assert after[:2] == before[:2], ("IME moved the screenshot origin", before, after)
assert any(n.get("text") == "Translate briefly" for n in root.iter("node"))
assert ui.bounds(ui.find("确认"))[3] <= before[3], "confirmation moved outside the screen"
assert "mInputShown=true" in ui.adb("shell", "dumpsys", "input_method"), "real software keyboard was not shown"
windows = ui.adb("shell", "dumpsys", "window", "windows")
ime = re.search(r"Window #\d+ Window\{[^\n]*InputMethod\}:(.*?)(?=\n  Window #|\Z)", windows, re.S).group(1)
keyboard_top = int(re.search(r"touchable region=SkRegion\(\(\d+,(\d+),", ime).group(1))
assert ui.bounds(ui.find("确认"))[3] <= keyboard_top, "IME covers confirmation"
assert ui.bounds(next(n for n in root.iter("node") if n.get("class") == "android.widget.EditText"))[3] <= keyboard_top, "IME covers request editor"
assert not any(n.get("text") in ("AI 解题", "讲知识点", "预览选区") for n in root.iter("node"))
image("memory-selection-keyboard.png")
ui.adb("shell", "input", "keyevent", "4")
ui.adb("shell", "input", "keyevent", "4")
time.sleep(0.3)
ui.click("用户记忆", scroll=True)
assert ui.find("自动提炼新对话中的记忆") is not None
image("memory-settings.png")
print("UI smoke passed: optional request, confirmation, stable IME mapping, memory controls; no provider request sent.")
