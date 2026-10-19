"""会话改名、运行中改名、错误提示和移动端键盘导航；只使用本地模拟接口。"""
import json
import os
import threading
import time
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from browser_check import MockBackend, overflow, submit, sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
BASE_URL = os.environ.get('FRONTEND_TEST_URL', 'http://127.0.0.1:5175')
histories = {}
renames = []
deleted = []
release = threading.Event()


def message(role, content, kind='final'):
    return dict(id=f'{role}-{time.time_ns()}', role=role, content=content, kind=kind,
                status='done', createdAt=int(time.time() * 1000), questions=[])


def fixture(id, app, title):
    histories[id] = dict(id=id, appId=app, title=title, createdAt=1, updatedAt=2, running=False,
                         messages=[message('user', title), message('assistant', '历史内容仍完整保留。')])


fixture('love-room', 'love', '恋爱记录')
fixture('manus-room', 'manus', '学习任务')


def summary(item):
    return {key: value for key, value in item.items() if key != 'messages'}


class Backend(MockBackend):
    def do_GET(self):
        path = urlparse(self.path).path
        if path == '/api/chats':
            self.respond(200, [summary(item) for item in histories.values()]); return
        if path.startswith('/api/chats/') and not path.endswith('/context'):
            item = histories.get(path.split('/')[-1])
            self.respond(200 if item else 404, item or {}); return
        super().do_GET()

    def do_PUT(self):
        assert self.headers.get('X-CSRF-TOKEN') == 'test-csrf-token'
        assert self.headers.get('X-Account-ID') == '101'
        item = histories.get(urlparse(self.path).path.split('/')[-2])
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
        if not item:
            self.respond(404, {'message': '对话不存在'}); return
        if body['title'] == '模拟保存失败':
            self.respond(503, {'message': '名称未保存，请重试'}); return
        item.update(title=body['title'], updatedAt=int(time.time() * 1000))
        renames.append((item['id'], body['title']))
        self.respond(200, summary(item))

    def do_DELETE(self):
        id = urlparse(self.path).path.split('/')[-1]
        histories.pop(id, None); deleted.append(id)
        self.respond(200, {'deleted': True})

    def do_POST(self):
        if urlparse(self.path).path == '/api/test/release':
            release.set(); self.respond(200, {}); return
        super().do_POST()

    def chat(self, path, query):
        if query.get('message', [''])[0] != 'rename-active':
            super().chat(path, query); return
        id = query['chatId'][0]
        fixture(id, 'manus', 'rename-active')
        item = histories[id]
        item.update(running=True, messages=[message('user', 'rename-active')])
        item['messages'].append(message('assistant', '正在整理资料，回答会继续执行。', 'progress'))
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream; charset=utf-8')
        self.end_headers()
        try:
            self.event('Step 1: 正在整理资料，回答会继续执行。')
            if not release.wait(20): raise AssertionError('任务未在测试期限内释放')
            item['messages'].append(message('assistant', '原任务完整回答已完成。'))
            item['running'] = False
            self.event('原任务完整回答已完成。', 'final_answer')
            self.event('{}', 'complete')
        except (BrokenPipeError, ConnectionResetError):
            pass


def open_rename(page, title):
    button = page.get_by_role('button', name=f'修改对话名：{title}', exact=True)
    button.locator('..').hover()
    button.click()
    expect(page.get_by_label('对话名', exact=True)).to_be_focused()


def run():
    backend = ThreadingHTTPServer(('127.0.0.1', 18124), Backend)
    threading.Thread(target=backend.serve_forever, daemon=True).start()
    results, errors = [], []
    output = ROOT / 'test-results' / 'conversations'
    output.mkdir(parents=True, exist_ok=True)
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(channel=os.environ.get('PLAYWRIGHT_CHANNEL', 'msedge'), headless=True)
            context = browser.new_context(viewport={'width': 1440, 'height': 960})
            page = context.new_page()
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(BASE_URL + '/#/chat/manus?session=manus-room')
            page.wait_for_load_state('networkidle')
            expect(page.locator('.conversation-list')).to_contain_text('历史内容仍完整保留')
            old_url = page.url
            open_rename(page, '学习任务')
            page.keyboard.press('Shift+Tab')
            expect(page.get_by_role('button', name='保存名称', exact=True)).to_be_focused()
            page.keyboard.press('Escape')
            expect(page.get_by_role('dialog')).to_have_count(0)
            expect(page.get_by_role('button', name='修改对话名：学习任务', exact=True)).to_be_focused()
            results.append('rename dialog selects title, traps focus, closes on Escape and restores focus')

            open_rename(page, '学习任务')
            page.get_by_label('对话名', exact=True).fill('   ')
            page.get_by_role('button', name='保存名称', exact=True).click()
            expect(page.get_by_role('alert')).to_contain_text('1 至 100')
            page.get_by_label('对话名', exact=True).fill('  Vue 学习计划  ')
            page.keyboard.press('Enter')
            expect(page.get_by_role('dialog')).to_have_count(0)
            expect(page.locator('.history-item').filter(has_text='Vue 学习计划')).to_have_count(1)
            assert page.url == old_url and histories['manus-room']['title'] == 'Vue 学习计划'
            expect(page.locator('.conversation-list')).to_contain_text('历史内容仍完整保留')
            page.reload(); page.wait_for_load_state('networkidle')
            expect(page.locator('.history-item').filter(has_text='Vue 学习计划')).to_have_count(1)
            results.append('blank validation, Enter save, trim, persisted rename and unchanged active route/history')

            open_rename(page, 'Vue 学习计划')
            page.get_by_label('对话名', exact=True).fill('模拟保存失败')
            page.get_by_role('button', name='保存名称', exact=True).click()
            expect(page.get_by_role('alert')).to_contain_text('名称未保存')
            assert histories['manus-room']['title'] == 'Vue 学习计划'
            page.get_by_role('button', name='取消', exact=True).click()
            context.set_offline(True)
            open_rename(page, 'Vue 学习计划')
            count = len(renames)
            page.get_by_label('对话名', exact=True).fill('离线时的新名称')
            page.get_by_role('button', name='保存名称', exact=True).click()
            expect(page.get_by_role('alert')).to_contain_text('当前离线')
            assert len(renames) == count
            page.get_by_role('button', name='取消', exact=True).click()
            context.set_offline(False)
            expect(page.locator('.offline-chat-note')).to_have_count(0)
            results.append('server failure and offline feedback preserve the saved title')

            page.get_by_role('button', name='开启新对话', exact=True).click()
            submit(page, 'rename-active')
            expect(page.locator('.agent-activity')).to_contain_text('正在思考')
            open_rename(page, 'rename-active')
            page.get_by_label('对话名', exact=True).fill('执行中的学习任务')
            page.get_by_role('button', name='保存名称', exact=True).click()
            expect(page.get_by_role('dialog')).to_have_count(0)
            expect(page.get_by_role('button', name='停止接收', exact=True)).to_be_visible()
            context.request.post(BASE_URL + '/api/test/release', data={})
            expect(page.locator('.conversation-list')).to_contain_text('原任务完整回答已完成')
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            expect(page.locator('.history-item').filter(has_text='执行中的学习任务')).to_have_count(1)
            results.append('renaming an executing task preserves the SSE stream and final answer')

            page.set_viewport_size({'width': 390, 'height': 844})
            page.get_by_role('button', name='打开导航', exact=True).click()
            open_rename(page, '恋爱记录')
            page.get_by_label('对话名', exact=True).fill('恋爱记录 · 新名称')
            page.get_by_role('button', name='保存名称', exact=True).click()
            expect(page.get_by_role('dialog', name='修改对话名')).to_have_count(0)
            expect(page.get_by_role('button', name='修改对话名：恋爱记录 · 新名称', exact=True)).to_be_focused()
            assert not overflow(page)
            page.screenshot(path=str(output / 'rename-mobile.png'), full_page=True, animations='disabled')
            page.get_by_role('button', name='删除对话：恋爱记录 · 新名称', exact=True).click()
            expect(page.get_by_role('dialog', name='删除这段对话？')).to_contain_text('记忆文件和压缩备份也会清理')
            page.get_by_role('button', name='删除对话', exact=True).click()
            expect(page.get_by_role('dialog', name='删除这段对话？')).to_have_count(0)
            assert 'love-room' in deleted and 'love-room' not in histories
            results.append('mobile rename actions, focus restore, no overflow and deletion explains file cleanup')
            assert not errors, errors
            browser.close()
        print(json.dumps({'passed': results}, ensure_ascii=False))
    finally:
        release.set(); backend.shutdown(); backend.server_close()


if __name__ == '__main__':
    run()
