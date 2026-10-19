"""验证代码块复制、流式更新和消息字体层级；使用本地模拟 SSE。"""
import json
import os
import re
import threading
import time
from http.server import ThreadingHTTPServer
from pathlib import Path

from browser_check import MockBackend, overflow, submit, sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
BASE_URL = os.environ.get('FRONTEND_TEST_URL', 'http://127.0.0.1:5175')
JAVASCRIPT = 'const greeting = "你好 <绘灵> & 世界";\nfunction hello(name) {\n  return `${greeting}, ${name}`;\n}\n'
HTML = '<img src="x" onerror="window.__codeXss = true">\n<script>window.__codeXss = true</script>\n'
INDENTED = 'print("保留缩进和换行")\nprint("只复制这一段代码")\n'
PROGRESS = '正在检查语法，并整理工具返回的结果。\n\n```sh\nprintf "first"\nprintf "second"\nprintf "third"\n```'
FINAL = '下面是整理好的最终回答，示例代码可以分别复制。\n\n### JavaScript 示例\n\n```javascript\n' + JAVASCRIPT + '```\n\n### HTML 示例\n\n```html\n' + HTML + '```\n\n无语言标记的缩进代码：\n\n' + ''.join('    ' + line + '\n' for line in INDENTED.splitlines())


def clipboard_text(page):
    # Windows 原生剪贴板将 LF 转为 CRLF；按逻辑行比较，缩进和其他字符保持精确。
    return page.evaluate('navigator.clipboard.readText()').replace('\r\n', '\n')


class CodeBackend(MockBackend):
    def chat(self, path, query):
        message = query.get('message', [''])[0]
        if message not in ['code-sample', 'stream-code']:
            return super().chat(path, query)
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream; charset=utf-8')
        self.end_headers()
        if message == 'stream-code':
            self.event('```js\nconst first = 1;\n')
            time.sleep(1.6)
            self.event('const second = 2;\n```')
        else:
            self.event('Step 1: ' + PROGRESS)
            self.event(FINAL, 'final_answer')
            self.event('{"reason":"finished"}', 'complete')


def run():
    output = ROOT / 'test-results' / 'code-blocks'
    output.mkdir(parents=True, exist_ok=True)
    backend = ThreadingHTTPServer(('127.0.0.1', 18124), CodeBackend)
    threading.Thread(target=backend.serve_forever, daemon=True).start()
    results, errors = [], []
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(channel=os.environ.get('PLAYWRIGHT_CHANNEL', 'msedge'), headless=True)
            context = browser.new_context(viewport={'width': 1440, 'height': 960}, permissions=['clipboard-read', 'clipboard-write'])
            page = context.new_page()
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(BASE_URL + '/#/chat/manus')
            page.wait_for_load_state('networkidle')
            expect(page.locator('#chat-input')).to_be_visible()
            submit(page, 'code-sample')
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            final = page.locator('[data-kind="final"]')
            progress = page.locator('[data-kind="progress"]')
            expect(final.locator('.code-block')).to_have_count(3)
            expect(final.locator('.message-expand')).to_have_count(0)
            expect(progress.locator('.message-text')).to_have_class(re.compile('is-collapsed'))
            expect(final.locator('.message-bubble')).to_have_css('font-size', '15px')
            expect(page.locator('[data-role="user"] .message-bubble')).to_have_css('font-size', '15px')
            expect(progress.locator('.message-bubble')).to_have_css('font-size', '13px')
            assert not page.evaluate('Boolean(window.__codeXss)')
            assert final.locator('.code-block img, .code-block script').count() == 0
            assert not overflow(page)

            blocks = final.locator('.code-block')
            for index, expected_code in enumerate([JAVASCRIPT, HTML, INDENTED]):
                button = blocks.nth(index).locator('.code-copy-button')
                button.click()
                expect(button).to_have_text('已复制')
                actual_code = clipboard_text(page)
                assert actual_code == expected_code, f'block {index}: clipboard differs from visible code'
                expect(final.locator('.message-actions button')).to_have_text('复制回复')
            results.append('fenced and indented blocks copy only code, preserving whitespace, Chinese and HTML literals')
            results.append('larger main messages, smaller progress, final answers remain expanded and HTML remains safe')

            progress.get_by_role('button', name='展开', exact=True).click()
            tool_copy = progress.locator('.code-copy-button')
            expect(tool_copy).to_be_visible()
            tool_copy.focus()
            page.keyboard.press('Enter')
            expect(tool_copy).to_have_text('已复制')
            assert clipboard_text(page) == 'printf "first"\nprintf "second"\nprintf "third"\n'
            progress.get_by_role('button', name='收起', exact=True).click()
            results.append('collapsed progress keeps two-line behavior; expanded code supports keyboard copy')

            failure_button = blocks.first.locator('.code-copy-button')
            page.evaluate("Object.defineProperty(navigator.clipboard, 'writeText', { configurable: true, value: async () => { throw new Error('clipboard denied'); } })")
            failure_button.click()
            expect(failure_button).to_have_text('复制失败')
            expect(failure_button).to_have_text('复制代码', timeout=4000)
            page.evaluate('delete navigator.clipboard.writeText')
            failure_button.click()
            expect(failure_button).to_have_text('已复制')
            assert clipboard_text(page) == JAVASCRIPT
            expect(blocks.nth(1).locator('.code-copy-button')).to_have_text('复制代码')
            results.append('independent copy feedback, permission failure and retry')
            page.screenshot(path=str(output / 'code-desktop.png'), animations='disabled')

            for width in [390, 320]:
                page.set_viewport_size({'width': width, 'height': 844})
                expect(final.locator('.message-bubble')).to_have_css('font-size', '15px')
                expect(progress.locator('.message-bubble')).to_have_css('font-size', '13px')
                assert not overflow(page), str(page.evaluate("({ width: innerWidth, documentWidth: document.documentElement.scrollWidth, mainMargin: getComputedStyle(document.querySelector('.main-shell')).marginLeft, codeWidths: [...document.querySelectorAll('.code-block')].map(element => element.getBoundingClientRect().width) })"))
                pre = blocks.first.locator('pre')
                pre.evaluate('element => { element.scrollLeft = element.scrollWidth }')
                button = blocks.first.locator('.code-copy-button')
                button.click()
                expect(button).to_have_text('已复制')
                assert clipboard_text(page) == JAVASCRIPT
                page.screenshot(path=str(output / f'code-mobile-{width}.png'), animations='disabled')
            results.append('320px and 390px code scrolling and copy, with mobile typography')

            page.reload()
            page.wait_for_load_state('networkidle')
            final = page.locator('[data-kind="final"]')
            expect(final.locator('.code-block')).to_have_count(3)
            expect(final.locator('.is-collapsed')).to_have_count(0)
            final.locator('.code-copy-button').first.click()
            assert clipboard_text(page) == JAVASCRIPT
            results.append('restored conversation rebuilds functional code copy buttons')

            page.set_viewport_size({'width': 1440, 'height': 960})
            page.get_by_role('link', name='AI 恋爱大师', exact=True).click()
            submit(page, 'stream-code')
            code = page.locator('[data-kind="final"] .code-block code')
            expect(code).to_have_text('const first = 1;\n')
            page.locator('.code-copy-button').click()
            assert clipboard_text(page) == 'const first = 1;\n'
            expect(code).to_have_text('const first = 1;\nconst second = 2;\n')
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            page.locator('.code-copy-button').click()
            assert clipboard_text(page) == 'const first = 1;\nconst second = 2;\n'
            results.append('code copy remains functional before and after streaming redraw')
            assert not errors, errors
            results.append('no browser runtime errors')
            context.close()
            browser.close()
        (output / 'report.json').write_text(json.dumps({'passed': results}, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'passed': results}, ensure_ascii=True))
    finally:
        backend.shutdown()
        backend.server_close()


if __name__ == '__main__':
    run()
