"""模拟独立后台执行，验证跨会话、页面重载、追问恢复与指定会话取消。"""
import copy
import json
import os
import sys
import threading
import time
import uuid
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.test-tools'))
from playwright.sync_api import sync_playwright, expect

tasks = {}
starts = []
lock = threading.RLock()
BASE_URL = os.environ.get('FRONTEND_TEST_URL', 'http://127.0.0.1:5178')


def message(role, content, status='done', kind='final'):
    return dict(id=str(uuid.uuid4()), role=role, content=content, status=status, kind=kind,
                createdAt=int(time.time()*1000), questions=[])


def view(task, summary=False):
    fields = ['id', 'appId', 'title', 'createdAt', 'updatedAt', 'running']
    if not summary:
        fields.append('messages')
    return copy.deepcopy({key: task[key] for key in fields})


def execute(task):
    with lock:
        reply = message('assistant', '后台已开始执行', 'streaming' if task['appId'] == 'love' else 'done',
                        'final' if task['appId'] == 'love' else 'progress')
        task['messages'].append(reply)
        task['events'].append((None, '后台已开始执行' if task['appId'] == 'love' else 'Step 1: 后台已开始执行'))
    if task['title'] == '后台等待补充':
        with lock:
            question = dict(runId=task['runId'], questionId=str(uuid.uuid4()), question='请补充目标', answer='', expired=False)
            pending = message('assistant', '', 'waiting', 'progress')
            pending['questions'].append(question)
            task['messages'].append(pending)
            task['events'].append(('human_question', json.dumps(question, ensure_ascii=False)))
        task['answer'].wait(15)
        with lock:
            if not task['cancel'].is_set():
                pending['status'] = 'done'
                task['messages'].append(message('user', question['answer']))
    else:
        task['cancel'].wait(7)
    with lock:
        if task['cancel'].is_set():
            for item in task['messages']:
                if item['status'] in ['streaming', 'waiting']:
                    item['status'] = 'stopped'
                for question in item['questions']:
                    if not question['answer']:
                        question['expired'] = True
        elif task['appId'] == 'love':
            reply['content'] += '，完整恋爱回复已完成'
            reply['status'] = 'done'
            task['events'].append((None, '，完整恋爱回复已完成'))
        else:
            answer = '完整智能体答案已完成' if task['title'] != '后台等待补充' else '已根据补充目标完成原任务'
            task['messages'].append(message('assistant', answer))
            task['events'].append(('final_answer', answer))
        task['running'] = False
        task['events'].append(('complete', '{}'))


class Backend(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def respond(self, data, status=200):
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode())

    def do_GET(self):
        path = urlparse(self.path).path
        if path == '/api/auth/session':
            self.respond(dict(user=dict(id='101', email='test@example.test', displayName='测试用户'), csrfToken='test'))
        elif path == '/api/health':
            self.send_response(200); self.end_headers(); self.wfile.write(b'ok')
        elif path == '/api/chats':
            with lock:
                self.respond([view(task, True) for task in reversed(list(tasks.values()))])
        elif path.startswith('/api/chats/'):
            with lock:
                task = tasks.get(path.split('/')[-1])
                self.respond(view(task) if task else {}, 200 if task else 404)
        else:
            self.respond({}, 404)

    def do_POST(self):
        path = urlparse(self.path).path
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
        if path in ['/api/ai/love_app/chat/sse', '/api/ai/manus/chat']:
            with lock:
                task = dict(id=body['chatId'], appId='love' if '/love_app/' in path else 'manus', title=body['message'],
                            createdAt=int(time.time()*1000), updatedAt=int(time.time()*1000), running=True,
                            messages=[message('user', body['message'])], events=[], runId=body.get('runId'),
                            cancel=threading.Event(), answer=threading.Event())
                tasks[task['id']] = task; starts.append(task['id'])
            threading.Thread(target=execute, args=(task,), daemon=True).start()
            self.send_response(200); self.send_header('Content-Type', 'text/event-stream; charset=utf-8'); self.end_headers()
            index = 0
            try:
                while True:
                    with lock:
                        events = list(task['events'][index:]); running = task['running']
                    for event, text in events:
                        frame = (f'event: {event}\n' if event else '') + ''.join(f'data: {line}\n' for line in text.split('\n')) + '\n'
                        self.wfile.write(frame.encode()); self.wfile.flush(); index += 1
                    if not running:
                        break
                    time.sleep(.03)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass  # 模拟真实服务端：关闭浏览器连接后任务继续执行。
        elif path.startswith('/api/chats/') and path.endswith('/cancel'):
            with lock:
                task = tasks[path.split('/')[-2]]
                task['cancel'].set(); task['answer'].set()
            self.respond(dict(cancelled=True))
        elif path == '/api/ai/manus/answer':
            with lock:
                task = next(task for task in tasks.values() if task['runId'] == body['runId'])
                question = task['messages'][-1]['questions'][0]
                assert question['questionId'] == body['questionId']
                question['answer'] = body['answer']; task['answer'].set()
            self.respond(dict(accepted=True))
        else:
            self.respond({}, 404)


def run():
    backend = ThreadingHTTPServer(('127.0.0.1', 18128), Backend)
    threading.Thread(target=backend.serve_forever, daemon=True).start()
    errors = []
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(channel='msedge', headless=True)
            page = browser.new_page(viewport=dict(width=1440, height=960))
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(BASE_URL + '/#/chat/love'); page.wait_for_load_state('networkidle')
            page.locator('#chat-input').fill('后台恋爱任务'); page.get_by_role('button', name='发送消息', exact=True).click()
            expect(page.locator('.conversation-list')).to_contain_text('后台已开始执行')
            love_url = page.url
            page.get_by_role('button', name='新对话', exact=True).click()
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            page.get_by_role('link', name='AI 超级智能体', exact=True).click()
            page.locator('#chat-input').fill('后台智能体任务'); page.get_by_role('button', name='发送消息', exact=True).click()
            expect(page.locator('.agent-activity')).to_contain_text('正在思考')
            manus_url = page.url
            expect(page.locator('.history-running')).to_have_count(2)
            page.locator('.history-item').filter(has_text='后台恋爱任务').click()
            expect(page.get_by_role('button', name='停止接收', exact=True)).to_be_visible()
            assert page.url == love_url
            assert '后台智能体任务' not in page.locator('.conversation-list').inner_text()
            page.get_by_role('link', name='探索应用', exact=True).click()
            expect(page.locator('.history-running')).to_have_count(2)
            page.reload(); page.wait_for_load_state('networkidle')
            page.locator('.history-item').filter(has_text='后台智能体任务').click()
            assert page.url == manus_url
            expect(page.locator('.agent-activity')).to_contain_text('正在思考')
            expect(page.locator('[data-kind="final"]')).to_contain_text('完整智能体答案已完成', timeout=12000)
            expect(page.locator('.history-running')).to_have_count(0, timeout=6000)
            page.locator('.history-item').filter(has_text='后台恋爱任务').click()
            expect(page.locator('.conversation-list')).to_contain_text('完整恋爱回复已完成')
            assert len(starts) == 2, '页面重载不能重新发起模型任务'

            page.get_by_role('link', name='AI 超级智能体', exact=True).click()
            page.locator('#chat-input').fill('后台等待补充'); page.get_by_role('button', name='发送消息', exact=True).click()
            expect(page.locator('.human-question')).to_contain_text('请补充目标')
            page.get_by_role('link', name='AI 恋爱大师', exact=True).click()
            expect(page.locator('.answer-banner')).to_have_count(0)
            page.reload(); page.wait_for_load_state('networkidle')
            page.locator('.history-item').filter(has_text='后台等待补充').click()
            expect(page.locator('.human-question')).to_contain_text('请补充目标')
            expect(page.locator('.human-question')).not_to_contain_text('本次提问已结束')
            page.locator('#chat-input').fill('整理原任务'); page.get_by_role('button', name='提交补充', exact=True).click()
            expect(page.locator('[data-kind="final"]')).to_contain_text('已根据补充目标完成原任务', timeout=7000)
            assert len(starts) == 3

            page.get_by_role('button', name='新对话', exact=True).click()
            page.locator('#chat-input').fill('后台停止智能体'); page.get_by_role('button', name='发送消息', exact=True).click()
            expect(page.locator('.agent-activity')).to_be_visible()
            page.get_by_role('link', name='AI 恋爱大师', exact=True).click()
            page.locator('#chat-input').fill('后台停止恋爱'); page.get_by_role('button', name='发送消息', exact=True).click()
            expect(page.get_by_role('button', name='停止接收', exact=True)).to_be_visible()
            expect(page.locator('.history-running')).to_have_count(2)
            page.get_by_role('button', name='停止接收', exact=True).click()
            expect(page.locator('.history-running')).to_have_count(1)
            page.locator('.history-item').filter(has_text='后台停止智能体').click()
            expect(page.get_by_role('button', name='停止接收', exact=True)).to_be_visible()
            page.get_by_role('button', name='停止接收', exact=True).click()
            expect(page.locator('.history-running')).to_have_count(0)
            assert not errors, errors
            browser.close()
        print('PASS: two apps continue after switching/home/reload; no message mixing or duplicate runs; pending question survives reload; cancellation is per conversation')
    finally:
        for task in tasks.values():
            task['cancel'].set(); task['answer'].set()
        backend.shutdown(); backend.server_close()


if __name__ == '__main__':
    run()
