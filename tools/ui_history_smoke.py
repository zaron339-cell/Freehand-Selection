"""Dedicated emulator + localhost fixture only: geometry, durable resume, original image context."""
import json
import urllib.request
from ui_smoke import adb, bounds, click, dump, find, tap, wait_text


def description(value):
    return next(n for n in dump().iter('node') if n.get('content-desc') == value)


def swipe(node, dx, dy, edge=False):
    x1, y1, x2, y2 = bounds(node)
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    if edge:
        x = x2 - 10
    adb('shell', 'input', 'swipe', str(x), str(y), str(x + dx), str(y + dy), '800')


def requests():
    return json.load(urllib.request.urlopen('http://127.0.0.1:8769/requests'))


def frame():
    right = bounds(description('调整窗口宽度'))
    bottom = bounds(description('调整窗口高度'))
    return right[2] - bottom[0], bottom[3] - right[1]


adb('shell', 'am', 'force-stop', 'io.freehand.selection')
adb('shell', 'appops', 'set', 'io.freehand.selection', 'SYSTEM_ALERT_WINDOW', 'allow')
adb('shell', 'pm', 'grant', 'io.freehand.selection', 'android.permission.POST_NOTIFICATIONS')
adb('shell', 'am', 'start', '-W', '-n', 'io.freehand.selection/.MainActivity')
click('开启悬浮球')
click('Start now')
dump()
adb('shell', 'input', 'tap', '1164', '318')
click('矩形框选')
adb('shell', 'screencap', '-p', '/sdcard/history-selection.png')
adb('pull', '/sdcard/history-selection.png', '.android-user/history-selection.png')
adb('shell', 'input', 'swipe', '350', '240', '650', '450', '700')
click('预览选区')
before = len(requests())
click('AI 解题')
wait_text(lambda t: '本地模拟接口测试' in t and '回答完成' in t)
assert len(requests()) == before + 1
original = requests()[-1]
w, h = frame()
swipe(description('调整窗口宽度'), -140, 0)
w2, h2 = frame()
assert w2 < w - 100 and h2 == h, (w, h, w2, h2)
swipe(description('调整窗口高度'), 0, -90)
w3, h3 = frame()
assert w3 == w2 and h3 < h2 - 60, (w2, h2, w3, h3)
swipe(description('调整窗口宽高'), 100, 60, edge=True)
w4, h4 = frame()
assert w4 > w3 + 60 and h4 > h3 + 30, (w3, h3, w4, h4)
swipe(find('⠿ AI 讲解'), -700, 0)
# Real WindowManager coordinates, rather than clipped accessibility bounds.
windows = adb('shell', 'dumpsys', 'window', 'windows')
assert 'x=-' in windows or '(-' in windows, 'Window did not move beyond screen edge'
adb('shell', 'input', 'tap', '1164', '318')
assert bounds(find('⠿ AI 讲解'))[0] >= 0
assert frame() == (w4, h4), (frame(), w4, h4)
tap(find('继续追问这张题图…'))
adb('shell', 'input', 'text', 'UnsentDraft')
adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
click('关闭')
adb('shell', 'am', 'force-stop', 'io.freehand.selection')
adb('shell', 'am', 'start', '-W', '-n', 'io.freehand.selection/.MainActivity')
click('历史对话', scroll=True)
resume = find('继续对话')
tap(resume)
text = wait_text(lambda t: '已恢复对话' in t)
assert 'UnsentDraft' in text and '本地模拟接口测试' in text, text
assert frame() == (w4, h4)
assert len(requests()) == before + 1, 'Restoring conversation made an unsolicited AI call'
click('发送')
wait_text(lambda t: '回答完成' in t and '你：UnsentDraft' in t)
followup = requests()[-1]
assert len(requests()) == before + 2
assert original['messages'][1] == followup['messages'][1], 'Original question/image context changed after process death'
assert followup['messages'][-1]['content'] == 'UnsentDraft', followup['messages'][-1]
adb('shell', 'screencap', '-p', '/sdcard/history-resumed.png')
adb('pull', '/sdcard/history-resumed.png', '.android-user/history-resumed.png')
click('关闭')
print('PASS: independent width/height/corner resize, offscreen movement and ball recovery, close + process death + history resume, draft and frame restore, no auto request, follow-up retains original image/context')
