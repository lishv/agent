"""上下文占用、手动/自动压缩、后台排队、刷新恢复和移动端验证，不调用真实模型。"""
import json
import os
import threading
import time
from http.server import ThreadingHTTPServer
from urllib.parse import urlparse
from background_browser_check import Backend as TaskBackend, tasks, starts, lock, ROOT
from playwright.sync_api import sync_playwright, expect

BASE_URL = os.environ.get('FRONTEND_TEST_URL', 'http://127.0.0.1:5179')
compactions = []


def usage(task):
    if 'context' not in task:
        total = 23000 if task['appId'] == 'manus' else 15000
        task['context'] = dict(enabled=True, estimated=True, estimatedTokens=total, inputBudgetTokens=28672,
                               contextWindowTokens=32768, responseReserveTokens=4096, usagePercent=round(total/286.72,1),
                               autoEnabled=True, thresholdPercent=70, retainTurns=4, status='idle',
                               lastCompactedAt=0, lastBeforeTokens=0, lastAfterTokens=0, compactionCount=0, message='')
    return task['context']


def compact(task, manual):
    context = usage(task)
    if task.get('fail_compaction'):
        context.update(status='error', message='上下文压缩失败，已保留原记忆，可稍后重试')
        return
    context.update(status='queued' if task['running'] else 'compressing', message='已安排在安全位置压缩')
    compactions.append(dict(id=task['id'], manual=manual))

    def worker():
        while task['running']:
            time.sleep(.02)
        with lock:
            context.update(status='compressing', message='正在生成历史摘要')
        time.sleep(.8)
        with lock:
            before = context['estimatedTokens']; after = max(1000, before//4)
            context.update(status='completed', estimatedTokens=after, usagePercent=round(after/286.72,1),
                           lastBeforeTokens=before, lastAfterTokens=after, lastCompactedAt=int(time.time()*1000),
                           compactionCount=context['compactionCount']+1, message='手动压缩完成' if manual else '自动压缩完成')
    threading.Thread(target=worker, daemon=True).start()


class Backend(TaskBackend):
    def do_GET(self):
        path = urlparse(self.path).path
        if path.endswith('/context'):
            with lock:
                task = tasks.get(path.split('/')[-2])
                if not task:
                    self.respond({}, 404); return
                context = usage(task)
                if not task['running'] and context['usagePercent'] >= 70 and context['status'] == 'idle':
                    compact(task, False)
                self.respond(dict(context))
            return
        super().do_GET()

    def do_POST(self):
        path = urlparse(self.path).path
        if path.endswith('/context/compact'):
            self.rfile.read(int(self.headers.get('Content-Length', 0)))
            with lock:
                task = tasks[path.split('/')[-3]]
                compact(task, True); self.respond(dict(usage(task)))
            return
        super().do_POST()


def run():
    backend = ThreadingHTTPServer(('127.0.0.1', 18129), Backend)
    threading.Thread(target=backend.serve_forever, daemon=True).start()
    results = []; errors = []
    output = ROOT/'test-results'; output.mkdir(exist_ok=True)
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(channel='msedge', headless=True)
            page = browser.new_page(viewport=dict(width=1440, height=960))
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(BASE_URL+'/#/chat/love'); page.wait_for_load_state('networkidle')
            page.get_by_role('button', name='查看上下文占用').click()
            expect(page.get_by_role('button', name='压缩上下文', exact=True)).to_be_disabled()
            page.keyboard.press('Escape'); expect(page.locator('.context-panel')).to_have_count(0)
            page.locator('#chat-input').fill('恋爱手动压缩'); page.get_by_role('button', name='发送消息', exact=True).click()
            expect(page.locator('.context-toggle')).to_contain_text('约 52%', timeout=6000)
            love_url = page.url
            page.get_by_role('button', name='查看上下文占用').click()
            expect(page.locator('.context-panel')).to_contain_text('达到 70% 自动压缩')
            expect(page.locator('.context-panel')).to_contain_text('保留 4 轮原文')
            expect(page.locator('.context-panel')).to_contain_text('预留 4.1k')
            page.get_by_role('button', name='压缩上下文', exact=True).click()
            expect(page.locator('.context-panel')).to_contain_text('已安排，等待安全位置')
            expect(page.get_by_role('button', name='停止接收', exact=True)).to_be_visible()
            page.get_by_role('link', name='AI 超级智能体', exact=True).click()
            expect(page.locator('.context-toggle')).to_have_text('0%')
            expect(page.locator('.context-panel')).to_have_count(0)
            page.locator('#chat-input').fill('智能体自动压缩'); page.get_by_role('button', name='发送消息', exact=True).click()
            expect(page.locator('.context-toggle')).to_contain_text('约 80%', timeout=6000)
            expect(page.locator('[data-kind="final"]')).to_contain_text('完整智能体答案已完成', timeout=12000)
            expect(page.locator('.context-toggle')).to_contain_text('约 20%', timeout=7000)
            page.get_by_role('button', name='查看上下文占用').click()
            expect(page.locator('.context-panel')).to_contain_text('自动压缩完成')
            page.screenshot(path=str(output/'context-manus-desktop.png'), full_page=True)
            assert any(not item['manual'] for item in compactions)
            results.append('70% automatic compaction and new session has independent meter')

            page.locator('.history-item').filter(has_text='恋爱手动压缩').click()
            assert page.url == love_url
            expect(page.locator('.context-toggle')).to_contain_text('约 13%', timeout=7000)
            expect(page.locator('.conversation-list')).to_contain_text('完整恋爱回复已完成')
            page.get_by_role('button', name='查看上下文占用').click()
            expect(page.locator('.context-panel')).to_contain_text('15.0k → 3.8k')
            expect(page.locator('.context-panel')).to_contain_text('手动压缩完成')
            page.screenshot(path=str(output/'context-love-desktop.png'), full_page=True)
            assert len(starts) == 2, '压缩不能重新发起聊天任务'
            results.append('manual compaction queues safely during streaming and preserves full visible reply')

            page.reload(); page.wait_for_load_state('networkidle')
            expect(page.locator('.context-toggle')).to_contain_text('约 13%', timeout=6000)
            page.get_by_role('button', name='查看上下文占用').click()
            expect(page.locator('.context-panel')).to_contain_text('15.0k → 3.8k')
            with lock:
                task = next(task for task in tasks.values() if task['title']=='恋爱手动压缩')
                task['fail_compaction'] = True
            page.get_by_role('button', name='压缩上下文', exact=True).click()
            expect(page.locator('.context-message')).to_contain_text('已保留原记忆')
            expect(page.locator('.context-toggle')).to_contain_text('约 13%')
            expect(page.locator('.conversation-list')).to_contain_text('完整恋爱回复已完成')
            results.append('reload restores context metrics; summary failure keeps history and occupancy')

            page.set_viewport_size(dict(width=390, height=844))
            assert not page.evaluate('document.documentElement.scrollWidth > window.innerWidth')
            bounds = page.locator('.context-panel').bounding_box()
            assert bounds['x']>=0 and bounds['x']+bounds['width']<=390
            page.screenshot(path=str(output/'context-mobile.png'), full_page=True)
            page.keyboard.press('Escape'); expect(page.locator('.context-panel')).to_have_count(0)
            assert not errors, errors
            results.append('390px mobile popover fits viewport; Escape dismissal and no runtime errors')
            browser.close()
        print(json.dumps(dict(passed=results, compactions=compactions), ensure_ascii=True))
    finally:
        for task in tasks.values():
            task['cancel'].set(); task['answer'].set()
        backend.shutdown(); backend.server_close()


if __name__ == '__main__':
    run()
