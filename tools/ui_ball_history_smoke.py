"""Uses local fixture history prepared by ui_smoke.py in the dedicated emulator."""
import re
from ui_smoke import adb, click, dump, wait_text


def top_activity():
    state = adb('shell', 'dumpsys', 'activity', 'activities')
    return re.findall(r'topResumedActivity=.*', state)[-1]


def long_press_ball():
    adb('shell', 'input', 'swipe', '1164', '318', '1164', '318', '900')
    wait_text(lambda t: '快捷操作' in t)


adb('shell', 'am', 'force-stop', 'io.freehand.selection')
adb('shell', 'appops', 'set', 'io.freehand.selection', 'SYSTEM_ALERT_WINDOW', 'allow')
adb('shell', 'pm', 'grant', 'io.freehand.selection', 'android.permission.POST_NOTIFICATIONS')
adb('shell', 'am', 'start', '-W', '-n', 'io.freehand.selection/.MainActivity')
click('开启悬浮球')
click('Start now')
dump()
underlying = top_activity()
assert 'io.freehand.selection/.MainActivity' not in underlying, underlying
long_press_ball()
click('历史对话')
wait_text(lambda t: '选择一条记录，继续追问' in t and '继续对话' in t)
assert top_activity() == underlying, 'History overlay switched the foreground Activity'
adb('shell', 'screencap', '-p', '/sdcard/ball-history.png')
adb('pull', '/sdcard/ball-history.png', '.android-user/ball-history.png')
click('关闭')
long_press_ball()
click('开始圈选')
wait_text(lambda t: '自由圈选' in t and '矩形框选' in t)
click('关闭')
long_press_ball()
click('历史对话')
click('继续对话')
wait_text(lambda t: '已恢复对话' in t and '本地模拟接口测试' in t)
assert top_activity() == underlying, 'Resuming from ball opened another Activity'
long_press_ball()
wait_text(lambda t: '找回当前回答' in t)
click('找回当前回答')
wait_text(lambda t: '已恢复对话' in t)
click('关闭')
long_press_ball()
click('收起')
assert not any(n.get('text') == '快捷操作' for n in dump().iter('node'))
print('PASS: long-press menu, floating history open/close, capture action, saved conversation resume, recover action and dismiss; original foreground Activity unchanged')
