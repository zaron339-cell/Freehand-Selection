"""Exercise global prompt editing on the dedicated emulator, restoring defaults afterwards."""
from ui_smoke import adb, bounds, click, dump, find, tap


def field_for(label):
    find(label, scroll=True)
    nodes = list(dump().iter("node"))
    title = next(node for node in nodes if node.get("text") == label)
    bottom = bounds(title)[3]
    return next(node for node in nodes if node.get("class") == "android.widget.EditText" and bounds(node)[1] >= bottom)


def replace(label, value):
    tap(field_for(label))
    adb("shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
    adb("shell", "input", "keyevent", "KEYCODE_DEL")
    dump()  # Wait for selection/deletion and IME processing before injecting text.
    adb("shell", "input", "text", value.replace(" ", "%s"))
    adb("shell", "input", "keyevent", "KEYCODE_BACK")


if __name__ == "__main__":
    adb("shell", "am", "force-stop", "io.freehand.selection")
    adb("shell", "am", "start", "-W", "-n", "io.freehand.selection/.MainActivity")
    click("提示词设置", scroll=True)
    values = {
        "通用提示词（所有模型）": "Help with any subject and choose the format freely.",
        "AI 解题按钮提示词": "Translate the selection.",
        "讲知识点按钮提示词": "Explain the selection naturally.",
    }
    for label, value in values.items():
        replace(label, value)
    click("保存", scroll=True)
    click("提示词设置", scroll=True)
    for label, value in values.items():
        assert field_for(label).get("text") == value, label
    click("恢复默认", scroll=True)
    click("保存", scroll=True)
    click("提示词设置", scroll=True)
    assert field_for("通用提示词（所有模型）").get("text") == "你是一位学习助手，请根据用户提供的内容和问题提供帮助。"
    assert field_for("AI 解题按钮提示词").get("text") == "请解答选区中的问题。"
    assert field_for("讲知识点按钮提示词").get("text") == "请讲解选区中的内容。"
    adb("shell", "screencap", "-p", "/sdcard/freehand-prompts.png")
    adb("pull", "/sdcard/freehand-prompts.png", ".android-user/prompt-settings-smoke.png")
    click("返回", scroll=True)
    print("PASS: prompt editor, save/reopen, restore defaults (dedicated emulator only)")
