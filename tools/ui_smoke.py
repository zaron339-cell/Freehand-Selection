"""Drive only the dedicated emulator against ai_smoke_server.py. No real provider calls."""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ADB = r"E:\Android_Studio_SDK\platform-tools\adb.exe"
BASE = [ADB, "-P", "5041", "-s", "127.0.0.1:5581"]


def adb(*args):
    return subprocess.check_output(BASE + list(args), timeout=40).decode("utf-8", "replace")


def bounds(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds", ""))))


def dump():
    for attempt in range(3):
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/freehand-ai-ui.xml")
            break
        except subprocess.CalledProcessError:
            if attempt == 2:
                raise
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/freehand-ai-ui.xml"))


def find(text, *, prefix=False, scroll=False):
    for _ in range(9 if scroll else 1):
        root = dump()
        for node in root.iter("node"):
            value = node.get("text", "")
            if (value.startswith(text) if prefix else value == text):
                b = bounds(node)
                if len(b) == 4 and b[2] > b[0] and b[3] > b[1]:
                    return node
        if scroll:
            adb("shell", "input", "swipe", "1000", "660", "1000", "240", "350")
    raise AssertionError(f"Cannot find UI element: {text}")


def tap(node):
    x1, y1, x2, y2 = bounds(node)
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def click(text, **options):
    tap(find(text, **options))


def wait_text(predicate):
    for _ in range(10):
        output = "\n".join(n.get("text", "") for n in dump().iter("node"))
        if predicate(output):
            return output
    raise AssertionError(output)


def fill(label, value):
    find(label, scroll=True)
    nodes = list(dump().iter("node"))
    title = next(node for node in nodes if node.get("text") == label)
    bottom = bounds(title)[3]
    field = next(node for node in nodes if node.get("class") == "android.widget.EditText" and bounds(node)[1] >= bottom)
    tap(field)
    adb("shell", "input", "text", value.replace(" ", "%s"))
    adb("shell", "input", "keyevent", "KEYCODE_BACK")


if __name__ == "__main__":
    adb("shell", "am", "force-stop", "io.freehand.selection")
    adb("shell", "am", "start", "-W", "-n", "io.freehand.selection/.MainActivity")
    if "--floating" in sys.argv:
        adb("shell", "appops", "set", "io.freehand.selection", "SYSTEM_ALERT_WINDOW", "allow")
        adb("shell", "pm", "grant", "io.freehand.selection", "android.permission.POST_NOTIFICATIONS")
        click("开启悬浮球")
        click("Start now")
        dump()
        adb("shell", "input", "tap", "1164", "318")
        click("矩形框选")
        adb("shell", "input", "swipe", "350", "240", "650", "450", "700")
        click("预览选区")
        click("AI 解题")
        wait_text(lambda text: "本地模拟接口测试" in text and "回答完成" in text)
        title = find("⠿ AI 讲解")
        before = bounds(title)
        x = (before[0] + before[2]) // 2
        y = (before[1] + before[3]) // 2
        adb("shell", "input", "swipe", str(x), str(y), str(x - 400), str(y + 40), "800")
        after = bounds(find("⠿ AI 讲解"))
        assert after[0] < before[0] - 250, (before, after)
        adb("shell", "screencap", "-p", "/sdcard/freehand-ai-floating.png")
        adb("pull", "/sdcard/freehand-ai-floating.png", ".android-user/ai-floating-smoke.png")
        click("关闭")
        print("PASS: capture permission, floating selection AI, streamed result, window drag, close (local fixture only)")
        sys.exit(0)
    click("AI 模型设置", prefix=True)
    click("新建配置")
    fill("API 地址 / Base URL", "http://10.0.2.2:8769/v1")
    fill("模型 ID（必须支持图片输入）", "vision-smoke")
    fill("API Key（使用设备密钥加密后保存在本机）", "smoke-key-not-real")
    click("允许 HTTP", prefix=True, scroll=True)
    click("测试图片连接", scroll=True)
    output = wait_text(lambda text: "接口已返回" in text and "本地模拟" in text)
    click("保存并使用", scroll=True)
    assert "自定义模型" in find("AI 模型设置", prefix=True).get("text")
    click("先在练习页试一试")
    click("矩形框选")
    adb("shell", "input", "swipe", "420", "225", "640", "445", "700")
    click("预览选区")
    click("AI 解题")
    output = wait_text(lambda text: "本地模拟接口测试" in text and "回答完成" in text)
    click("继续追问这张题图…")
    adb("shell", "input", "text", "Explain%sagain")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    click("发送")
    output = wait_text(lambda text: "Explain again" in text and text.count("自定义模型：") == 2 and "回答完成" in text)
    assert "你：请" not in output and "### 本地模拟" not in output and "**重要观点**" not in output
    click("复制")
    assert "已复制回答" in "\n".join(n.get("text", "") for n in dump().iter("node"))
    adb("shell", "screencap", "-p", "/sdcard/freehand-ai-smoke.png")
    adb("pull", "/sdcard/freehand-ai-smoke.png", ".android-user/ai-conversation-smoke.png")
    print("PASS: settings, encrypted profile save, image test, selection AI, follow-up, copy (local fixture only)")
