"""浏览器验证。启动前端时设置 BACKEND_PROXY_TARGET=http://127.0.0.1:18124。

模拟接口只在本测试进程中存在，不调用真实模型。
安装：python -m pip install --target .test-tools -r tests/requirements.txt
"""
import json
import os
from pathlib import Path
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.test-tools'))
from playwright.sync_api import sync_playwright, expect

requests = []
failures = {}
pending = {}
submitted_answers = []
BASE_URL = os.environ.get('FRONTEND_TEST_URL', 'http://127.0.0.1:5173')


class MockBackend(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
        path = urlparse(self.path).path
        if path in ['/api/ai/manus/chat', '/api/ai/love_app/chat/sse']:
            self.chat(path, {key: [value] for key, value in body.items()})
            return
        if path == '/api/ai/manus/cancel' or path.startswith('/api/chats/') and path.endswith('/cancel'):
            chat_id = path.split('/')[-2] if path.startswith('/api/chats/') else None
            for value in list(pending.values()):
                if value['runId'] == body.get('runId') or chat_id and value['chatId'] == chat_id:
                    value['cancelled'] = True
                    value['event'].set()
            self.respond(200, {'cancelled': True})
            return
        if path == '/api/ai/manus/answer':
            request = pending.get(body.get('questionId'))
            if not request or request['runId'] != body.get('runId'):
                self.respond(409, {'error': 'wrong question'})
                return
            if body.get('answer') == '模拟提交失败' and not failures.get('answer'):
                failures['answer'] = True
                self.respond(503, {'error': 'try again'})
                return
            submitted_answers.append(body)
            request['answer'] = body['answer']
            request['event'].set()
            # SSE 可比 POST 确认更早到达，用于检查消息顺序。
            time.sleep(.3)
            self.respond(200, {'accepted': True})
            return
        self.respond(404, {})

    def respond(self, status, data):
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        try:
            self.wfile.write(json.dumps(data).encode('utf-8'))
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        url = urlparse(self.path)
        query = parse_qs(url.query)
        if url.path == '/api/auth/session':
            self.respond(200, {'user': {'id': '101', 'email': 'test@example.test', 'displayName': '测试用户'}, 'csrfToken': 'test-csrf-token'})
            return
        if url.path == '/api/chats':
            self.respond(200, [])
            return
        if url.path.startswith('/api/chats/'):
            self.respond(404, {'message': 'not found'})
            return
        if url.path == '/api/health':
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain')
            self.end_headers()
            self.wfile.write(b'ok')
            return
        self.chat(url.path, query)

    def chat(self, path, query):
        message = query.get('message', [''])[0]
        requests.append({'path': path, 'query': query, 'method': self.command, 'url_query': urlparse(self.path).query})
        if message == 'fail-once' and not failures.get(message):
            failures[message] = True
            self.send_response(503)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(b'{"error":"unavailable"}')
            return
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream; charset=utf-8')
        self.send_header('Cache-Control', 'no-cache')
        self.end_headers()
        try:
            if message == 'slow-stop':
                self.event('这是停止前的内容。')
                time.sleep(2)
                self.event('这段内容不应在停止后出现。')
            elif message == 'xss':
                self.event('<script>window.__xss = true</script><img src=x onerror="window.__xss=true">')
            elif message == 'long':
                self.event('\n\n'.join(f'第 {i} 段：这是一段用来验证阅读时不被强制滚动的内容。' for i in range(40)))
                time.sleep(1)
                self.event('\n\n新的段落到达。')
            elif message == 'thinking-final':
                self.event('Step 1: '+'\n'.join(f'执行说明 {i}：正在整理用户需要的资料。' for i in range(8)))
                time.sleep(1.5)
                self.event('Step 2: 检索已完成，正在整理最终答案。')
                time.sleep(1)
                self.event('\n\n'.join(f'最终答案第 {i} 段：请先学习基础概念，再使用一个完整项目验证理解。' for i in range(12)), 'final_answer')
                self.event('{"reason":"finished"}', 'complete')
            elif message in ['ask-human', 'ask-human-cancel', 'ask-human-long']:
                run_id = query['runId'][0]
                self.event(json.dumps({'runId': run_id}), 'run_started')
                self.event('Step 1: 正在了解任务需求。')
                for number, text in enumerate(['你希望完成什么任务？', '有哪些具体要求？']):
                    if message == 'ask-human-long' and number == 0:
                        text += '\n' + '\n'.join(f'补充第 {i} 项信息，让任务更明确。' for i in range(6))
                    question_id = str(uuid.uuid4())
                    wait = {'runId': run_id, 'chatId': query['chatId'][0], 'event': threading.Event(), 'answer': '', 'cancelled': False}
                    pending[question_id] = wait
                    self.event(json.dumps({'runId': run_id, 'questionId': question_id, 'question': text}, ensure_ascii=False), 'human_question')
                    wait['event'].wait(20)
                    pending.pop(question_id, None)
                    if wait['cancelled'] or not wait['answer']:
                        return
                    self.event(f'Step {number + 2}: 已收到真实回答：{wait["answer"]}')
                self.event('已完成你的任务。', 'final_answer')
                self.event('{"reason":"finished"}', 'complete')
            elif message == 'media-tools':
                images={'status':'ok','source':'Pixabay','images':[{'tags':'山间湖泊','downloadUrl':'https://cdn.example/lake.jpg','pageUrl':'https://pixabay.com/photos/lake/'}]}
                self.event('Step 1: 工具 searchImage 返回的结果：'+json.dumps([{'text':json.dumps(images,ensure_ascii=False)}],ensure_ascii=False))
                media='网页标题：校园资源\n网页地址：https://example.test/\n\n图片链接：\n[校园图片](https://cdn.example/campus.jpg)\n\n视频链接：\n[校园视频](https://cdn.example/campus.mp4)\n\n音频链接：\n[背景音乐](https://cdn.example/campus.mp3)'
                self.event('Step 2: 工具 scrapeWebPage 返回的结果：'+json.dumps(media,ensure_ascii=False))
                rate={'status':'rate_limited','message':'图片服务请求过于频繁，请等待 60 秒后再搜索。','retryAfterSeconds':60}
                self.event('Step 3: 工具 searchImage 返回的结果：'+json.dumps([{'text':json.dumps(rate,ensure_ascii=False)}],ensure_ascii=False))
                self.event('已整理可用的图片、视频和音频链接。','final_answer')
                self.event('{"reason":"finished"}','complete')
            elif message == 'raw-tools':
                html='<html><head><title>学习指南</title></head><body><h1>Java 学习指南</h1><p>先掌握基础语法与集合。</p><p>接着学习数据库和 Spring Boot。</p><p>最后完成一个项目。</p><script>app()</script></body></html>'
                self.event('Step 1: 工具 scrapeWebPage 返回的结果：'+json.dumps(html,ensure_ascii=False))
                self.event('Step 2: 工具 executeTerminalCommand 返回的结果：' + json.dumps([{'name': '鑷劧椋庢櫙', 'user': {'email': 'private@example.test'}}], ensure_ascii=False))
                search=json.dumps([{'title':f'学习资料 {i}','link':f'https://example.test/guide/{i}','snippet':f'第 {i} 条详细介绍，包含基础语法、集合和项目实践。'} for i in range(1,8)],ensure_ascii=False)
                tool='工具 searchWebScraping 返回的结果：'+search
                self.event('Step 3: '+tool+'\n'+tool)
                self.event('Step 4: ' + '\n'.join(f'第 {i} 项：这是面向用户的正常中文执行说明。' for i in range(8)))
                self.event('推荐先学习 Java 基础，再完成一个 Spring Boot 项目。', 'final_answer')
                self.event('{"reason":"finished"}', 'complete')
            elif message == 'sse-error':
                self.event('Step 1: 已完成第一步。')
                self.event('I/O error: https://api.example.test/private RST_STREAM', 'error')
            elif path.endswith('/manus/chat'):
                self.event('Step 1: 工具 searchWebScraping 返回的结果：\n\n找到了 **3 条**相关信息。')
                time.sleep(.7)
                self.event('Step 2: 工具 generatePDF 返回的结果：\n\n文档已生成：`tmp/pdf/学习路线.pdf`')
                time.sleep(.3)
                self.event('学习路线已整理：先掌握 Java 基础，再通过项目学习 Spring Boot，生成的文档包含每周安排。', 'final_answer')
                self.event('{"reason":"finished"}', 'complete')
            elif message == 'stream-love':
                self.event('你好，')
                time.sleep(.9)
                self.event('我在这里听你说。\n\n**慢慢来**，我们一起理清思路。')
            else:
                self.event('收到你的消息。\n\n这是一个完整的回复。')
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def event(self, text, event=None):
        frame = (f'event: {event}\r\n' if event else '') + ''.join(f'data: {line}\r\n' for line in text.split('\n')) + '\r\n'
        self.wfile.write(frame.encode('utf-8'))
        self.wfile.flush()


def overflow(page):
    return page.evaluate('document.documentElement.scrollWidth > window.innerWidth')


def submit(page, text):
    page.locator('#chat-input').fill(text)
    page.get_by_role('button', name='发送消息', exact=True).click()


def run():
    output = ROOT / 'test-results'
    output.mkdir(exist_ok=True)
    backend = ThreadingHTTPServer(('127.0.0.1', 18124), MockBackend)
    threading.Thread(target=backend.serve_forever, daemon=True).start()
    results = []
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(channel=os.environ.get('PLAYWRIGHT_CHANNEL', 'msedge'), headless=True)
            context = browser.new_context(viewport={'width': 1440, 'height': 960}, device_scale_factor=1, permissions=['clipboard-read', 'clipboard-write'])
            page = context.new_page()
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(BASE_URL)
            page.wait_for_load_state('networkidle')
            expect(page.get_by_role('button', name='绘灵首页')).to_be_visible()
            assert page.title() == '绘灵 · AI 工作空间'
            expect(page.get_by_role('button', name='服务已连接')).to_be_visible()
            assert not overflow(page)
            page.screenshot(path=str(output / 'home-desktop.png'), full_page=True)
            results.append('desktop home and backend proxy')

            page.locator('.application-card.love').click()
            expect(page.get_by_role('heading', name='今天，有什么心事？')).to_be_visible()
            first_id = page.url.split('session=')[-1]
            assert first_id and len(first_id) > 20
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_disabled()
            page.get_by_role('button', name='读懂心动信号', exact=False).click()
            assert page.locator('#chat-input').input_value().startswith('我喜欢一个人')
            assert not requests, 'suggestion must not send automatically'
            page.screenshot(path=str(output / 'love-welcome-desktop.png'), full_page=True)
            results.append('automatic session id and suggested prompts')

            page.locator('#chat-input').fill('   ')
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_disabled()
            page.locator('#chat-input').fill('输入法测试')
            page.locator('#chat-input').evaluate("el => el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, bubbles: true }))")
            assert not requests
            page.locator('#chat-input').press('Shift+Enter')
            assert '\n' in page.locator('#chat-input').input_value()
            results.append('blank input, Chinese IME, multiline input')

            submit(page, 'stream-love')
            ai = page.locator('[data-role="assistant"]').last
            expect(ai).to_contain_text('你好，')
            expect(page.get_by_role('button', name='停止接收', exact=True)).to_be_visible()
            assert '听你说' not in ai.inner_text(), 'response should be visible before completion'
            expect(ai).to_contain_text('我在这里听你说。')
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            expect(ai.locator('strong').filter(has_text='慢慢来')).to_be_visible()
            user_box = page.locator('.user-bubble').last.bounding_box()
            ai_box = page.locator('.ai-bubble').last.bounding_box()
            assert user_box['x'] > ai_box['x']
            assert requests[-1]['query']['chatId'][0] == first_id
            page.screenshot(path=str(output / 'love-conversation-desktop.png'), full_page=True)
            page.get_by_role('button', name='复制回复', exact=True).last.click()
            expect(page.get_by_role('button', name='已复制', exact=True)).to_be_visible()
            assert '慢慢来' in page.evaluate('navigator.clipboard.readText()')
            results.append('incremental SSE, role alignment, Markdown, clipboard')

            submit(page, 'second question')
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            assert requests[-1]['query']['chatId'][0] == first_id
            prior_requests = len(requests)
            page.wait_for_timeout(700)
            assert len(requests) == prior_requests, 'normal EOF must not trigger automatic retries'
            page.reload()
            page.wait_for_load_state('networkidle')
            expect(page.locator('[data-role="user"]')).to_have_count(2)
            assert first_id in page.url
            results.append('stable chatId, no automatic resend, reload history')

            page.get_by_role('button', name='新对话', exact=True).click()
            expect(page.get_by_role('heading', name='今天，有什么心事？')).to_be_visible()
            assert first_id not in page.url
            submit(page, 'slow-stop')
            expect(page.locator('[data-role="assistant"]').last).to_contain_text('停止前的内容')
            page.get_by_role('button', name='停止接收', exact=True).click()
            expect(page.locator('[data-role="assistant"]').last).to_contain_text('已停止接收')
            page.wait_for_timeout(2100)
            assert '不应在停止后出现' not in page.locator('[data-role="assistant"]').last.inner_text()
            results.append('new conversation and cancellation')

            submit(page, 'fail-once')
            expect(page.locator('.reply-error-toast')).to_have_text('回答出错~')
            toast = page.locator('.reply-error-toast').bounding_box()
            composer = page.locator('.composer').bounding_box()
            assert toast['y'] + toast['height'] <= composer['y']
            expect(page.locator('.reply-error-toast')).to_have_count(0, timeout=4500)
            assert '503' not in page.locator('.conversation-list').inner_text()
            page.get_by_role('button', name='再次尝试', exact=True).click()
            expect(page.locator('[data-role="assistant"]').last).to_contain_text('完整的回复')
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            submit(page, 'xss')
            expect(page.locator('[data-role="assistant"]').last).to_contain_text('<script>')
            assert not page.evaluate('Boolean(window.__xss)')
            assert page.locator('.markdown-body script').count() == 0
            results.append('error retry and HTML sanitization')

            submit(page, 'long')
            expect(page.locator('[data-role="assistant"]').last).to_contain_text('第 39 段')
            long_reply = page.locator('[data-role="assistant"]').last
            expect(long_reply.locator('.message-expand')).to_have_count(0)
            expect(long_reply.locator('.is-collapsed')).to_have_count(0)
            assert long_reply.locator('.message-text').evaluate('el => el.clientHeight > 200')
            page.locator('.chat-scroll-area').evaluate('el => { el.scrollTop = 0; el.dispatchEvent(new Event("scroll")) }')
            expect(page.get_by_role('button', name='回到最新消息', exact=True)).to_be_visible()
            expect(page.locator('[data-role="assistant"]').last).to_contain_text('新的段落到达')
            assert page.locator('.chat-scroll-area').evaluate('el => el.scrollTop') == 0
            page.get_by_role('button', name='回到最新消息', exact=True).click()
            results.append('reading scroll position is preserved')

            page.get_by_role('link', name='AI 超级智能体', exact=True).click()
            expect(page.get_by_role('heading', name='一起，把想法变成现实。')).to_be_visible()
            page.get_by_label('描述你想完成的任务…', exact=True).fill('整理学习路线')
            page.get_by_role('button', name='发送消息', exact=True).click()
            expect(page.locator('.step-message')).to_have_count(1)
            expect(page.locator('.agent-activity')).to_contain_text('正在思考')
            expect(page.get_by_role('button', name='停止接收', exact=True)).to_be_visible()
            expect(page.locator('.step-message')).to_have_count(2)
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            expect(page.locator('.agent-activity')).to_have_count(0)
            assert requests[-1]['query']['chatId'][0] in page.url
            expect(page.locator('.step-heading')).to_have_count(0)
            assert '步骤 1' not in page.locator('.conversation-list').inner_text()
            expect(page.locator('[data-kind="final"]').last).to_contain_text('学习路线已整理')
            assert 'doTerminate' not in page.locator('.conversation-list').inner_text()
            expect(page.locator('[data-role="assistant"]')).to_have_count(3)
            for bubble in page.locator('.step-message').all():
                expect(bubble.locator('.message-bubble')).to_have_count(1)
                # 工具回执使用浅灰容器，普通执行说明继续开放排版。
                tool_result = 'tool-message' in bubble.get_attribute('class').split()
                expect(bubble.locator('.ai-bubble')).to_have_css('border-top-width', '1px' if tool_result else '0px')
                expect(bubble.locator('.ai-bubble')).to_have_css('background-color', 'rgb(250, 250, 250)' if tool_result else 'rgba(0, 0, 0, 0)')
            page.screenshot(path=str(output / 'manus-conversation-desktop.png'), full_page=True)
            stable_chat = requests[-1]['query']['chatId'][0]
            page.get_by_label('描述你想完成的任务…', exact=True).fill('继续')
            page.get_by_role('button', name='发送消息', exact=True).click()
            expect(page.locator('.step-message')).to_have_count(4)
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            assert requests[-1]['query']['chatId'][0] == stable_chat
            results.append('one SSE step per bubble, chronological order and stable Manus chatId')

            page.get_by_role('button', name='新对话', exact=True).click()
            page.locator('#chat-input').fill('raw-tools')
            page.get_by_role('button', name='发送消息', exact=True).click()
            expect(page.locator('.step-message')).to_have_count(4)
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            visible_text = page.locator('.conversation-list').inner_text()
            assert 'Java 学习指南' in visible_text and '先掌握基础语法与集合' in visible_text and '已获取 1 条数据' in visible_text
            assert '学习资料 7' in visible_text and visible_text.count('学习资料 1')==1
            assert '推荐先学习 Java 基础' in visible_text
            assert all(value not in visible_text for value in ['<!doctype', '鑷', 'private@example.test'])
            search_result=page.locator('.step-message').nth(2)
            expect(search_result.get_by_role('button',name='展开',exact=True)).to_be_visible()
            assert search_result.locator('.message-text').evaluate('el => el.clientHeight <= parseFloat(getComputedStyle(el).lineHeight) * 2 + 1')
            assert search_result.locator('.message-text').evaluate('el => el.querySelector("a").getBoundingClientRect().bottom <= el.getBoundingClientRect().bottom + 1'), 'second search line must be fully visible'
            search_result.get_by_role('button',name='展开',exact=True).click()
            expect(search_result.locator('a')).to_have_count(7)
            expect(search_result.get_by_role('link',name='学习资料 7',exact=True)).to_be_visible()
            search_result.get_by_role('button',name='收起',exact=True).click()
            step = page.locator('.step-message').nth(3)
            expect(step.get_by_role('button', name='展开', exact=True)).to_be_visible()
            assert step.locator('.message-text').evaluate('el => el.clientHeight <= parseFloat(getComputedStyle(el).lineHeight) * 2 + 1')
            expect(step.locator('.markdown-body p')).to_have_css('white-space', 'normal')
            step.get_by_role('button', name='展开', exact=True).click()
            assert step.locator('.message-text').evaluate('el => el.clientHeight > 120')
            step.get_by_role('button', name='收起', exact=True).click()
            assert step.locator('.message-text').evaluate('el => el.clientHeight <= parseFloat(getComputedStyle(el).lineHeight) * 2 + 1')
            page.locator('.step-message').first.get_by_role('button', name='复制回复', exact=True).click()
            assert '<html>' not in page.evaluate('navigator.clipboard.readText()')
            assert '先掌握基础语法与集合' in page.evaluate('navigator.clipboard.readText()')
            page.screenshot(path=str(output / 'huiling-collapsed-desktop.png'), full_page=True)
            results.append('tool search links and webpage body are readable, no step headings, final answer precedes completion; two-line collapse and safe copy')

            page.get_by_role('button', name='新对话', exact=True).click()
            submit(page, 'media-tools')
            expect(page.locator('.step-message')).to_have_count(3)
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            for bubble in page.locator('.step-message').all():
                expand=bubble.get_by_role('button',name='展开',exact=True)
                if expand.count(): expand.click()
            expect(page.get_by_role('link',name='山间湖泊',exact=True)).to_have_attribute('href','https://cdn.example/lake.jpg')
            expect(page.get_by_role('link',name='来源页面',exact=True)).to_have_attribute('href','https://pixabay.com/photos/lake/')
            for label, extension in [('校园图片','jpg'),('校园视频','mp4'),('背景音乐','mp3')]:
                expect(page.get_by_role('link',name=label,exact=True)).to_have_attribute('href',f'https://cdn.example/campus.{extension}')
            expect(page.locator('.step-message').last).to_contain_text('等待 60 秒')
            assert '"images"' not in page.locator('.conversation-list').inner_text()
            page.screenshot(path=str(output/'media-links-desktop.png'),full_page=True)
            results.append('MCP image download/source links, webpage video/audio links and rate-limit feedback')

            page.get_by_role('button', name='新对话', exact=True).click()
            submit(page, 'thinking-final')
            progress=page.locator('[data-kind="progress"]').first
            expect(progress.get_by_role('button',name='展开',exact=True)).to_be_visible()
            expect(page.locator('.agent-activity')).to_contain_text('正在思考')
            page.screenshot(path=str(output/'manus-thinking-desktop.png'),full_page=True)
            expect(page.locator('[data-kind="final"]')).to_contain_text('最终答案第 11 段')
            expect(page.get_by_role('button',name='发送消息',exact=True)).to_be_visible()
            final=page.locator('[data-kind="final"]')
            expect(final.locator('.message-expand')).to_have_count(0); expect(final.locator('.is-collapsed')).to_have_count(0)
            assert final.locator('.message-text').evaluate('el => el.clientHeight > 300')
            expect(page.locator('.agent-activity')).to_have_count(0)
            page.reload(); page.wait_for_load_state('networkidle')
            expect(page.locator('[data-kind="final"]')).to_contain_text('最终答案第 11 段')
            expect(page.locator('[data-kind="final"] .message-expand')).to_have_count(0)
            expect(page.locator('[data-kind="final"] .is-collapsed')).to_have_count(0)
            page.screenshot(path=str(output/'manus-final-desktop.png'),full_page=True)
            results.append('thinking indicator persists between progress events; final answer is fully visible and remains so after reload')

            page.get_by_role('button', name='新对话', exact=True).click()
            long_input = '中' * 20000
            expect(page.locator('#chat-input')).to_have_attribute('maxlength', '20000')
            page.locator('#chat-input').fill(long_input)
            expect(page.locator('.character-count')).to_have_text('20000/20000')
            page.locator('#chat-input').press('End')
            page.locator('#chat-input').press('a')
            expect(page.locator('#chat-input')).to_have_value(long_input)
            page.get_by_role('button', name='发送消息', exact=True).click()
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            assert requests[-1]['query']['message'][0] == long_input
            assert requests[-1]['method'] == 'POST' and requests[-1]['url_query'] == ''
            user_message = page.locator('[data-role="user"]').last
            expect(user_message.get_by_role('button', name='展开', exact=True)).to_be_visible()
            assert user_message.locator('.message-text').evaluate('el => el.clientHeight <= parseFloat(getComputedStyle(el).lineHeight) * 2 + 1')
            results.append('long Chinese POST body and user message two-line collapse')

            page.get_by_role('button', name='新对话', exact=True).click()
            page.locator('#chat-input').fill('sse-error')
            page.get_by_role('button', name='发送消息', exact=True).click()
            expect(page.locator('.reply-error-toast')).to_have_text('回答出错~')
            expect(page.locator('.reply-error-toast')).to_have_css('opacity', '1')
            expect(page.locator('.agent-activity')).to_have_count(0)
            expect(page.locator('.step-message')).to_have_count(1)
            expect(page.get_by_role('button', name='再次尝试', exact=True)).to_be_visible()
            assert 'RST_STREAM' not in page.locator('.conversation-list').inner_text()
            assert 'api.example.test' not in page.locator('.conversation-list').inner_text()
            page.screenshot(path=str(output / 'huiling-error-toast-desktop.png'), full_page=True)
            expect(page.locator('.reply-error-toast')).to_have_count(0, timeout=4500)
            results.append('SSE server error preserves completed steps and shows a temporary composer toast')

            page.get_by_role('button', name='新对话', exact=True).click()
            page.locator('#chat-input').fill('ask-human-long')
            page.get_by_role('button', name='发送消息', exact=True).click()
            question = page.locator('.human-question')
            expect(question.get_by_role('button', name='展开', exact=True)).to_be_visible()
            assert question.locator('p').evaluate('el => el.clientHeight <= parseFloat(getComputedStyle(el).lineHeight) * 2 + 1')
            question.get_by_role('button', name='展开', exact=True).click()
            assert question.locator('p').evaluate('el => el.clientHeight > 120')
            question.get_by_role('button', name='收起', exact=True).click()
            page.get_by_role('button', name='结束任务', exact=True).click()
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            results.append('long human questions also collapse until explicitly expanded')

            page.get_by_role('button', name='新对话', exact=True).click()
            chat_url = page.url
            before = len(requests)
            page.get_by_label('描述你想完成的任务…', exact=True).fill('ask-human')
            page.get_by_role('button', name='发送消息', exact=True).click()
            expect(page.locator('.human-question')).to_contain_text('你希望完成什么任务？')
            expect(page.locator('.agent-activity')).to_contain_text('等待你的补充')
            expect(page.get_by_role('button', name='提交补充', exact=True)).to_be_disabled()
            page.screenshot(path=str(output / 'manus-human-question-desktop.png'), full_page=True)
            page.get_by_label('输入你的回答，继续当前任务…', exact=True).fill('模拟提交失败')
            page.get_by_role('button', name='提交补充', exact=True).click()
            expect(page.locator('.reply-error-toast')).to_have_text('回答出错~')
            expect(page.locator('.answer-error')).to_contain_text('请再试一次')
            assert page.locator('#chat-input').input_value() == '模拟提交失败'
            page.get_by_label('输入你的回答，继续当前任务…', exact=True).fill('整理 Java 学习路线')
            page.get_by_role('button', name='提交补充', exact=True).click()
            expect(page.locator('.human-question').last).to_contain_text('有哪些具体要求？')
            assert page.url == chat_url
            assert len(requests) == before + 1, 'answer must resume the current stream, not start another run'
            page.get_by_label('输入你的回答，继续当前任务…', exact=True).fill('适合初学者，分阶段安排')
            page.get_by_role('button', name='提交补充', exact=True).click()
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            expect(page.locator('[data-role="user"]')).to_have_count(3)
            expect(page.locator('[data-role="assistant"]').last).to_contain_text('已完成你的任务')
            assert submitted_answers[-2]['runId'] == submitted_answers[-1]['runId']
            assert submitted_answers[-2]['questionId'] != submitted_answers[-1]['questionId']
            roles = page.locator('.message-row').evaluate_all('els => els.map(el => el.dataset.role)')
            assert roles == ['user', 'assistant', 'assistant', 'user', 'assistant', 'assistant', 'user', 'assistant', 'assistant'], roles
            assert '300 元' not in page.locator('.conversation-list').inner_text()
            page.screenshot(path=str(output / 'manus-human-answer-desktop.png'), full_page=True)
            results.append('two real answers resume the same run; failed answer retry and message order')

            page.get_by_role('button', name='新对话', exact=True).click()
            page.get_by_label('描述你想完成的任务…', exact=True).fill('ask-human-cancel')
            page.get_by_role('button', name='发送消息', exact=True).click()
            expect(page.locator('.human-question')).to_contain_text('你希望完成什么任务？')
            page.get_by_role('button', name='结束任务', exact=True).click()
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            expect(page.locator('.agent-activity')).to_have_count(0)
            expect(page.locator('.human-question')).to_contain_text('本次提问已结束')
            results.append('cancel pending human question')

            # 离开聊天页仍在后台接收，返回后能读到完整回复。
            page.get_by_role('link', name='AI 恋爱大师', exact=True).click()
            expect(page.get_by_role('heading', name='今天，有什么心事？')).to_be_visible()
            submit(page, 'slow-stop')
            background_url = page.url
            expect(page.locator('[data-role="assistant"]').last).to_contain_text('停止前的内容')
            page.get_by_role('link', name='探索应用', exact=True).click()
            expect(page.locator('.history-running')).to_be_visible()
            page.wait_for_timeout(2200)
            saved = page.evaluate("JSON.parse(localStorage.getItem('huiling.chat.v2.101')).sessions")
            assert saved[0]['messages'][-1]['status'] == 'done'
            assert '不应在停止后出现' in saved[0]['messages'][-1]['content']
            expect(page.locator('.history-running')).to_have_count(0)
            page.locator('.history-item').filter(has_text='slow-stop').first.click()
            assert page.url == background_url
            expect(page.locator('[data-role="assistant"]').last).to_contain_text('不应在停止后出现')
            results.append('navigation preserves background stream; returning shows full result and sidebar activity clears')

            mobile = context.new_page()
            mobile.set_viewport_size({'width': 390, 'height': 844})
            mobile.goto(BASE_URL)
            mobile.wait_for_load_state('networkidle')
            assert not overflow(mobile)
            mobile.screenshot(path=str(output / 'home-mobile.png'), full_page=True)
            mobile.get_by_role('button', name='打开导航', exact=True).click()
            expect(mobile.locator('.sidebar')).to_have_class('sidebar is-open')
            mobile.get_by_role('link', name='AI 恋爱大师', exact=True).click()
            expect(mobile.get_by_role('heading', name='今天，有什么心事？')).to_be_visible()
            expect(mobile.locator('.sidebar')).to_have_css('visibility', 'hidden')
            assert not overflow(mobile)
            mobile.screenshot(path=str(output / 'love-welcome-mobile.png'), full_page=True)
            submit(mobile, 'stream-love')
            expect(mobile.locator('[data-role="assistant"]').last).to_contain_text('听你说')
            expect(mobile.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            assert not overflow(mobile)
            mobile.screenshot(path=str(output / 'love-conversation-mobile.png'), full_page=True)
            results.append('390px mobile home, drawer, chat, no horizontal overflow')

            mobile.goto(BASE_URL)
            mobile.locator('.application-card.manus').click()
            mobile.get_by_label('描述你想完成的任务…', exact=True).fill('ask-human-cancel')
            mobile.get_by_role('button', name='发送消息', exact=True).click()
            expect(mobile.get_by_label('输入你的回答，继续当前任务…', exact=True)).to_be_visible()
            expect(mobile.locator('.human-question')).to_contain_text('你希望完成什么任务？')
            assert not overflow(mobile)
            mobile.screenshot(path=str(output / 'manus-human-question-mobile.png'), full_page=True)
            mobile.get_by_role('button', name='结束任务', exact=True).click()
            expect(mobile.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            results.append('390px mobile human question, answer composer and cancellation')

            mobile.get_by_role('button', name='新对话', exact=True).click()
            mobile.locator('#chat-input').fill('raw-tools')
            mobile.get_by_role('button', name='发送消息', exact=True).click()
            expect(mobile.locator('.step-message')).to_have_count(4)
            expect(mobile.locator('.step-heading')).to_have_count(0)
            step = mobile.locator('.step-message').nth(2)
            expect(step.get_by_role('button', name='展开', exact=True)).to_be_visible()
            assert step.locator('.message-text').evaluate('el => el.clientHeight <= parseFloat(getComputedStyle(el).lineHeight) * 2 + 1')
            assert step.locator('.message-text').evaluate('el => el.querySelector("a").getBoundingClientRect().bottom <= el.getBoundingClientRect().bottom + 1'), 'mobile second search line must be fully visible'
            step.get_by_role('button', name='展开', exact=True).click()
            expect(step.locator('a')).to_have_count(7)
            assert not overflow(mobile)
            step.get_by_role('button', name='收起', exact=True).click()
            mobile.screenshot(path=str(output / 'huiling-collapsed-mobile.png'), full_page=True)
            mobile.get_by_role('button',name='新对话',exact=True).click()
            submit(mobile, 'thinking-final')
            expect(mobile.locator('.agent-activity')).to_contain_text('正在思考')
            expect(mobile.locator('[data-kind="final"]')).to_contain_text('最终答案第 11 段')
            expect(mobile.get_by_role('button',name='发送消息',exact=True)).to_be_visible()
            expect(mobile.locator('[data-kind="final"] .message-expand')).to_have_count(0)
            expect(mobile.locator('[data-kind="final"] .is-collapsed')).to_have_count(0)
            assert not overflow(mobile)
            mobile.screenshot(path=str(output/'manus-final-mobile.png'),full_page=True)
            mobile.get_by_role('button', name='新对话', exact=True).click()
            mobile.locator('#chat-input').fill('sse-error')
            mobile.get_by_role('button', name='发送消息', exact=True).click()
            expect(mobile.locator('.reply-error-toast')).to_have_text('回答出错~')
            expect(mobile.locator('.reply-error-toast')).to_have_css('opacity', '1')
            assert not overflow(mobile)
            mobile.screenshot(path=str(output / 'huiling-error-toast-mobile.png'), full_page=True)
            expect(mobile.locator('.reply-error-toast')).to_have_count(0, timeout=4500)
            results.append('mobile two-line collapse, expansion and temporary error toast')

            for width in [320, 760, 1024]:
                mobile.set_viewport_size({'width': width, 'height': 844})
                mobile.goto(BASE_URL)
                mobile.wait_for_load_state('networkidle')
                assert not overflow(mobile), f'home overflow at {width}px'
                mobile.locator('.application-card.manus').click()
                expect(mobile.get_by_role('heading', name='一起，把想法变成现实。')).to_be_visible()
                assert not overflow(mobile), f'chat overflow at {width}px'
            results.append('320px, 760px, 1024px responsive breakpoints')

            assert not errors, errors
            results.append('no browser runtime errors')
            context.close()
            browser.close()
        (output / 'browser-report.json').write_text(json.dumps({'passed': results, 'requests': len(requests)}, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'passed': results, 'request_count': len(requests)}, ensure_ascii=True))
    finally:
        backend.shutdown()
        backend.server_close()


if __name__ == '__main__':
    run()
