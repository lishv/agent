"""图片预览与下载回归：仅使用本地模拟接口，不调用模型或访问外部图片网站。"""
import io
import json
import os
import threading
import time
from collections import Counter
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse

from PIL import Image, ImageDraw
from browser_check import MockBackend, overflow, submit, sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
BASE_URL = os.environ.get('FRONTEND_TEST_URL', 'http://127.0.0.1:5176')
REMOTE = 'http://127.0.0.1:18124'
CHINESE_NAME = '小猫 + (1).png'
files = []
remote_requests = []
current_owner = '101'
slow_started = threading.Event()
slow_release = threading.Event()


def fixture_image():
    image = Image.new('RGB', (640, 420), '#eee7dc')
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((25, 25, 615, 395), radius=28, fill='#d8e4db')
    draw.ellipse((205, 100, 435, 320), fill='#c28e63')
    draw.polygon([(215, 160), (215, 65), (295, 130)], fill='#c28e63')
    draw.polygon([(345, 130), (425, 65), (425, 160)], fill='#c28e63')
    draw.ellipse((267, 183, 279, 207), fill='#353332')
    draw.ellipse((361, 183, 373, 207), fill='#353332')
    draw.polygon([(309, 232), (331, 232), (320, 245)], fill='#66493f')
    buffer = io.BytesIO()
    image.save(buffer, format='PNG')
    return buffer.getvalue()


PNG = fixture_image()


def link(name, category='download'):
    return f'/api/files/{category}/preview-run-1?name={quote(name)}'


class ImageBackend(MockBackend):
    def binary(self, body, filename=None):
        self.send_response(200)
        self.send_header('Content-Type', 'application/octet-stream' if filename else 'image/png')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        if filename:
            self.send_header('Content-Disposition', "attachment; filename*=UTF-8''" + quote(filename))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == '/api/auth/session':
            self.respond(200, {'user': {'id': current_owner, 'displayName': '图片测试用户', 'email': 'image@example.test'}, 'csrfToken': 'test-csrf-token'})
            return
        if url.path.startswith('/remote/'):
            remote_requests.append(url.path)
            if url.path.endswith('blocked.jpg'):
                self.respond(403, {'message': '图片来源拒绝访问'})
            else:
                self.binary(PNG)
            return
        if url.path.startswith('/api/files/'):
            name = parse_qs(url.query).get('name', [''])[0]
            files.append({'name': name, 'owner': self.headers.get('X-Account-ID'), 'cookie': self.headers.get('Cookie', '')})
            if self.headers.get('X-Account-ID') != current_owner:
                self.respond(409, {'code': 40901, 'message': '账号已切换'})
            elif name == 'expired.png':
                self.respond(404, {'code': 40400, 'message': '文件不存在或已过期，请重新生成后下载'})
            elif name == 'retry.png' and sum(item['name'] == name for item in files) == 1:
                self.respond(503, {'message': '图片暂时无法读取，请重试'})
            elif name == 'slow.png':
                slow_started.set()
                slow_release.wait(10)
                self.binary(PNG, name)
            elif name == 'invalid.png':
                self.binary(b'<html>not an image</html>', name)
            else:
                self.binary(b'%PDF-test-download' if name.endswith('.pdf') else PNG, name)
            return
        super().do_GET()

    def chat(self, path, query):
        scenario = query.get('message', [''])[0]
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream; charset=utf-8')
        self.end_headers()
        if scenario == 'external-search':
            data = {'status': 'ok', 'source': '测试图库', 'images': [
                {'tags': '图片直链', 'downloadUrl': REMOTE + '/remote/cat.png', 'pageUrl': REMOTE + '/source/cat'},
                {'tags': '无扩展名缩略图', 'downloadUrl': REMOTE + '/remote/image?id=2', 'isPreview': True, 'width': 320, 'height': 200},
                {'tags': '暂不可用图片', 'downloadUrl': REMOTE + '/remote/blocked.jpg'},
            ]}
            self.event('Step 1: 工具 searchImage：' + json.dumps(data, ensure_ascii=False))
            self.event(f'Step 2: 已下载候选图片：[下载]({link("process-only.png")})\n'
                       f'![过程图片]({REMOTE}/remote/process-only.png)\n'
                       f'![内嵌图片](data:image/png;base64,iVBORw0KGgo=)')
            content = ('已整理图片和来源。\n\n'
                       f'[图片直链]({REMOTE}/remote/cat.png) · [来源页面]({REMOTE}/source/cat)\n\n'
                       f'![无扩展名缩略图]({REMOTE}/remote/image?id=2)\n\n'
                       f'[暂不可用图片]({REMOTE}/remote/blocked.jpg)')
        elif scenario == 'image-stream':
            complete_link = f'[流式图片]({link("stream.png")})'
            self.event(complete_link[:-1])
            time.sleep(.3)
            self.event(complete_link[-1:])
            for _ in range(3):
                time.sleep(.15)
                self.event('\n继续说明。')
            self.event('[DONE]')
            return
        elif scenario in ['expired-image', 'retry-image', 'invalid-image', 'slow-image']:
            name = scenario.split('-')[0] + '.png'
            content = f'[下载图片]({link(name)})'
        else:
            content = (f'已找到两张图片，可以直接查看并下载。\n\n[下载 {CHINESE_NAME}]({link(CHINESE_NAME)})\n\n'
                       f'![同一张图片]({link(CHINESE_NAME)})\n\n[下载第二张]({link("cat-2.jpg")})\n\n'
                       f'[下载 PDF]({link("report.pdf", "pdf")})\n\n```markdown\n![代码中的示例](https://example.invalid/code.png)\n```')
        if 'love_app' in path:
            self.event(content)
            self.event('[DONE]')
        else:
            self.event(content, 'final_answer')
            self.event('{"reason":"finished"}', 'complete')


def new_chat(page, scenario):
    page.get_by_role('button', name='新对话', exact=True).click()
    submit(page, scenario)
    expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()


def all_loaded(page):
    for card in page.locator('.message-image').all():
        card.scroll_into_view_if_needed()
        expect(card.locator('img.is-loaded')).to_be_visible()
        assert card.locator('img').evaluate('image => image.naturalWidth') == 640


def run():
    global current_owner
    output = ROOT / 'test-results' / 'image-previews'
    output.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(('127.0.0.1', 18124), ImageBackend)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    results, errors = [], []
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(channel=os.environ.get('PLAYWRIGHT_CHANNEL', 'msedge'), headless=True)
            try:
                context = browser.new_context(viewport={'width': 1440, 'height': 1050}, accept_downloads=True)
                context.add_cookies([{'name': 'preview-session', 'value': 'test-session', 'url': BASE_URL}])
                context.add_init_script('''window.previewUrls = {created: [], revoked: []};
                    const create = URL.createObjectURL.bind(URL), revoke = URL.revokeObjectURL.bind(URL);
                    URL.createObjectURL = blob => { const url = create(blob); previewUrls.created.push(url); return url; };
                    URL.revokeObjectURL = url => { previewUrls.revoked.push(url); return revoke(url); };''')
                page = context.new_page()
                page.on('pageerror', lambda error: errors.append(str(error)))
                page.goto(BASE_URL + '/#/chat/manus')
                page.wait_for_load_state('networkidle')
                submit(page, 'local-images')
                expect(page.locator('.message-image')).to_have_count(2)
                all_loaded(page)
                assert all(item['owner'] == '101' and 'preview-session=test-session' in item['cookie'] for item in files)
                assert not any(item['name'] == 'report.pdf' for item in files)
                assert page.locator('.markdown-body img').count() == 0
                counts = Counter(item['name'] for item in files)
                assert counts[CHINESE_NAME] == counts['cat-2.jpg'] == 1
                results.append('authenticated attachment responses preview once; PDF and code examples are not loaded as images')
                first = page.locator('.message-image').first
                with page.expect_download() as download:
                    first.get_by_role('button', name='下载图片', exact=True).click()
                assert download.value.suggested_filename == CHINESE_NAME
                assert Path(download.value.path()).read_bytes() == PNG
                with page.expect_download() as download:
                    page.get_by_role('link', name='下载 PDF').click()
                assert Path(download.value.path()).read_bytes() == b'%PDF-test-download'
                assert not overflow(page)
                page.screenshot(path=str(output / 'images-desktop.png'), animations='disabled')
                results.append('image card download and existing PDF download remain functional')

                for width in [390, 320]:
                    page.set_viewport_size({'width': width, 'height': 960})
                    all_loaded(page)
                    assert not overflow(page)
                    page.screenshot(path=str(output / f'images-mobile-{width}.png'), animations='disabled')
                results.append('320px and 390px image layouts have no horizontal overflow')
                page.set_viewport_size({'width': 1440, 'height': 1050})
                page.reload()
                page.wait_for_load_state('networkidle')
                expect(page.locator('.message-image')).to_have_count(2)
                all_loaded(page)
                results.append('reloaded history restores image previews')

                new_chat(page, 'external-search')
                expect(page.locator('.message-image')).to_have_count(3)
                expect(page.locator('[data-kind="final"] .message-image')).to_have_count(3)
                for step in page.locator('.step-message').all():
                    step.get_by_role('button', name='展开', exact=True).click()
                    expect(step.locator('img, .message-image')).to_have_count(0)
                assert not any(item['name'] == 'process-only.png' for item in files)
                assert '/remote/process-only.png' not in remote_requests
                for card in page.locator('.message-image').all():
                    card.scroll_into_view_if_needed()
                expect(page.locator('.message-image img.is-loaded')).to_have_count(2)
                expect(page.locator('.message-image').last).to_contain_text('图片暂时无法预览')
                expect(page.locator('.message-image').last.get_by_role('link', name='打开原图')).to_have_attribute('href', REMOTE + '/remote/blocked.jpg')
                assert Counter(remote_requests)['/remote/image'] == 1
                results.append('only final replies preview images; expanded tool/progress messages do not display or fetch any images')

                for scenario, expected in [('expired-image', '文件不存在或已过期'), ('invalid-image', '图片暂时无法预览')]:
                    new_chat(page, scenario)
                    expect(page.locator('.message-image')).to_contain_text(expected)
                    expect(page.locator('.message-image').get_by_role('button', name='下载图片')).to_be_visible()
                new_chat(page, 'retry-image')
                expect(page.locator('.message-image')).to_contain_text('图片暂时无法读取')
                page.get_by_role('button', name='重新加载', exact=True).click()
                all_loaded(page)
                results.append('expired and invalid images show readable failures; retry succeeds without resubmitting the AI task')

                new_chat(page, 'slow-image')
                assert slow_started.wait(5)
                page.get_by_role('link', name='探索应用', exact=True).click()
                slow_release.set()
                page.wait_for_function('previewUrls.created.every(url => previewUrls.revoked.includes(url))')
                expect(page.locator('.message-image')).to_have_count(0)
                results.append('navigation cancels an unfinished image fetch and releases all object URLs')

                page.get_by_role('link', name='AI 恋爱大师', exact=True).click()
                submit(page, 'image-stream')
                expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
                expect(page.locator('.message-image')).to_have_count(1)
                all_loaded(page)
                assert Counter(item['name'] for item in files)['stream.png'] == 1
                results.append('love assistant streaming completes links and preserves previews without repeated requests')

                # 旧版恋爱消息可能没有 kind，仍应区分普通回复、工具回执和步骤。
                page.evaluate('''({finalLink, processLink}) => {
                    const key = 'huiling.chat.v2.101', cache = JSON.parse(localStorage.getItem(key));
                    const session = cache.sessions.find(item => item.appId === 'love');
                    const reply = (id, content, extra = {}) => ({id, role:'assistant', content,
                        createdAt:Date.now(), status:'done', ...extra});
                    session.messages = [
                        reply('love-progress', `[候选图片](${processLink})`, {kind:'progress'}),
                        reply('love-step', `![过程图片](${processLink})`, {stepNumber:1}),
                        reply('love-tool', `**已下载文件**：[下载](${processLink})`),
                        reply('love-final', `这是最终回复：[下载图片](${finalLink})`)
                    ];
                    localStorage.setItem(key, JSON.stringify(cache));
                }''', {'finalLink': link('love-history.png'), 'processLink': link('love-process-only.png')})
                page.reload()
                expect(page.locator('.message-image')).to_have_count(1)
                all_loaded(page)
                expect(page.locator('[data-kind="progress"] img')).to_have_count(0)
                assert not any(item['name'] == 'love-process-only.png' for item in files)
                results.append('love history previews only the final response, including legacy records without kind')

                current_owner = '102'
                page.evaluate("window.dispatchEvent(new StorageEvent('storage', {key: 'huiling.account.v1', newValue: JSON.stringify({user:{id:'102'}})}))")
                expect(page.locator('.message-image')).to_have_count(0)
                page.wait_for_function('previewUrls.created.every(url => previewUrls.revoked.includes(url))')
                assert not errors, errors
                results.append('account switch removes the previous account image and releases its Blob; no browser runtime errors')
                context.close()
            except Exception:
                page.screenshot(path=str(output / 'failure.png'), animations='disabled')
                (output / 'failure.html').write_text(page.content(), encoding='utf-8')
                raise
            finally:
                browser.close()
    finally:
        slow_release.set()
        server.shutdown()
        server.server_close()
    (output / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    run()
