"""侧边栏与布局验证；使用本地模拟接口，不调用真实 AI。"""
import json
import os
import threading
from http.server import ThreadingHTTPServer
from pathlib import Path

from browser_check import MockBackend, overflow, submit, sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
BASE_URL = os.environ.get('FRONTEND_TEST_URL', 'http://127.0.0.1:5175')


def settled(page):
    page.wait_for_load_state('networkidle')
    expect(page.locator('.connection-status')).to_contain_text('服务已连接')


def run():
    output = ROOT / 'test-results' / 'layout'
    output.mkdir(parents=True, exist_ok=True)
    backend = ThreadingHTTPServer(('127.0.0.1', 18124), MockBackend)
    threading.Thread(target=backend.serve_forever, daemon=True).start()
    results, errors = [], []
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(channel=os.environ.get('PLAYWRIGHT_CHANNEL', 'msedge'), headless=True)
            context = browser.new_context(viewport={'width': 1440, 'height': 960})
            page = context.new_page()
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(BASE_URL)
            settled(page)
            expect(page.get_by_role('button', name='收起侧边栏')).to_be_visible()
            expect(page.locator('.sidebar')).to_have_css('width', '248px')
            assert not overflow(page)
            page.screenshot(path=str(output / 'home-expanded.png'), full_page=True, animations='disabled')
            original_x = page.locator('.main-shell').bounding_box()['x']
            page.get_by_role('button', name='收起侧边栏').click()
            expect(page.locator('.sidebar')).to_have_css('width', '76px')
            assert page.locator('.main-shell').bounding_box()['x'] < original_x - 100
            for name in ['探索应用', 'AI 恋爱大师', 'AI 超级智能体']:
                expect(page.get_by_role('link', name=name, exact=True)).to_be_visible()
            expect(page.get_by_role('button', name='开启新对话', exact=True)).to_be_visible()
            expect(page.get_by_role('button', name='账号设置：测试用户', exact=True)).to_be_visible()
            assert not overflow(page)
            page.screenshot(path=str(output / 'home-collapsed.png'), full_page=True, animations='disabled')
            page.reload()
            settled(page)
            expect(page.locator('.sidebar')).to_have_css('width', '76px')
            results.append('desktop collapse, icon navigation and persistent preference')

            page.get_by_role('link', name='AI 恋爱大师', exact=True).click()
            expect(page.locator('#chat-input')).to_be_visible()
            page.locator('#chat-input').fill('收起侧栏时，草稿仍然保留。')
            previous_url = page.url
            page.get_by_role('button', name='展开侧边栏').click()
            expect(page.locator('.sidebar')).to_have_css('width', '248px')
            expect(page.locator('#chat-input')).to_have_value('收起侧栏时，草稿仍然保留。')
            assert page.url == previous_url
            submit(page, 'stream-love')
            expect(page.locator('[data-role="assistant"]').last).to_contain_text('你好，')
            page.get_by_role('button', name='收起侧边栏').click()
            expect(page.locator('[data-role="assistant"]').last).to_contain_text('我在这里听你说。')
            expect(page.get_by_role('button', name='发送消息', exact=True)).to_be_visible()
            expect(page.locator('.sidebar')).to_have_css('width', '76px')
            page.screenshot(path=str(output / 'love-chat-collapsed.png'), full_page=True, animations='disabled')
            results.append('collapse preserves route, draft and active SSE reply')

            page.get_by_role('button', name='展开最近对话').click()
            expect(page.get_by_role('button', name='全部', exact=True)).to_be_focused()
            expect(page.locator('.sidebar')).to_have_css('width', '248px')
            page.get_by_role('button', name='收起侧边栏').focus()
            page.keyboard.press('Enter')
            expect(page.get_by_role('button', name='展开侧边栏')).to_be_focused()
            page.get_by_role('button', name='账号设置：测试用户', exact=True).click()
            expect(page.locator('.account-dialog')).to_be_visible()
            page.get_by_role('button', name='关闭账号窗口').click()
            expect(page.get_by_role('button', name='账号设置：测试用户', exact=True)).to_be_focused()
            page.get_by_role('button', name='开启新对话', exact=True).click()
            expect(page.get_by_role('heading', name='今天，有什么心事？')).to_be_visible()
            assert page.url != previous_url
            results.append('collapsed history, keyboard controls, account and new conversation')

            page.set_viewport_size({'width': 390, 'height': 844})
            expect(page.get_by_role('button', name='打开导航')).to_be_visible()
            page.get_by_role('button', name='打开导航').click()
            expect(page.get_by_role('dialog', name='绘灵导航')).to_be_visible()
            expect(page.get_by_role('dialog', name='绘灵导航').get_by_role('button', name='关闭导航', exact=True)).to_be_focused()
            expect(page.locator('.brand-copy')).to_be_visible()
            assert page.locator('.main-shell').evaluate('element => element.inert')
            for _ in range(22):
                page.keyboard.press('Tab')
                assert page.evaluate('document.querySelector(".sidebar").contains(document.activeElement)')
            page.screenshot(path=str(output / 'mobile-drawer.png'), full_page=True, animations='disabled')
            page.keyboard.press('Escape')
            expect(page.get_by_role('button', name='打开导航')).to_be_focused()
            assert not page.locator('.main-shell').evaluate('element => element.inert')
            assert page.evaluate('document.body.style.overflow') != 'hidden'
            page.get_by_role('button', name='打开导航').click()
            page.locator('.sidebar-scrim').click(position={'x': 380, 'y': 300})
            expect(page.get_by_role('button', name='打开导航')).to_be_focused()
            page.get_by_role('button', name='打开导航').click()
            page.get_by_role('link', name='AI 超级智能体', exact=True).click()
            expect(page.get_by_role('heading', name='一起，把想法变成现实。')).to_be_visible()
            expect(page.locator('.sidebar')).not_to_be_visible()
            page.get_by_role('button', name='打开导航').click()
            page.get_by_role('button', name='账号设置：测试用户', exact=True).click()
            expect(page.locator('.account-dialog')).to_be_visible()
            page.get_by_role('button', name='关闭账号窗口').click()
            expect(page.get_by_role('button', name='打开导航')).to_be_focused()
            results.append('mobile drawer, focus containment, Escape, scrim and account focus')

            for width, height in [(1920, 1080), (1440, 960), (1024, 768), (820, 1000), (760, 844), (390, 844), (320, 568)]:
                page.set_viewport_size({'width': width, 'height': height})
                page.goto(BASE_URL)
                settled(page)
                if width > 760:
                    expect(page.get_by_role('button', name='展开侧边栏')).to_be_visible()
                    page.get_by_role('button', name='展开侧边栏').click()
                    expect(page.locator('.sidebar')).to_have_css('width', '248px')
                assert not overflow(page), f'home overflow at {width}px'
                page.locator('.application-card.manus').click()
                expect(page.get_by_role('heading', name='一起，把想法变成现实。')).to_be_visible()
                assert not overflow(page), f'chat overflow at {width}px'
                assert page.locator('.chat-scroll-area').evaluate('element => element.scrollTop') == 0, 'empty chat should start at its greeting'
                composer = page.locator('.composer').bounding_box()
                assert composer['y'] + composer['height'] <= height, f'composer below viewport at {width}px'
                if width > 760:
                    page.get_by_role('button', name='收起侧边栏').click()
                    expect(page.locator('.sidebar')).to_have_css('width', '76px')
                    assert not overflow(page), f'collapsed chat overflow at {width}px'
                if width in [1440, 390, 320]:
                    page.screenshot(path=str(output / f'manus-welcome-{width}.png'), full_page=True, animations='disabled')
            results.append('320–1920px responsive layout, expanded and collapsed widths, visible composer')

            blocked = browser.new_context(viewport={'width': 1280, 'height': 900})
            blocked.add_init_script("""
                const read = Storage.prototype.getItem, write = Storage.prototype.setItem;
                Storage.prototype.getItem = function(key) { if (key === 'huiling:sidebar-collapsed') throw new Error('storage disabled'); return read.call(this, key); };
                Storage.prototype.setItem = function(key, value) { if (key === 'huiling:sidebar-collapsed') throw new Error('storage disabled'); return write.call(this, key, value); };
            """)
            blocked_page = blocked.new_page()
            blocked_page.on('pageerror', lambda error: errors.append(str(error)))
            blocked_page.goto(BASE_URL)
            settled(blocked_page)
            blocked_page.get_by_role('button', name='收起侧边栏').click()
            expect(blocked_page.locator('.sidebar')).to_have_css('width', '76px')
            results.append('sidebar remains usable when preference storage is unavailable')
            assert not errors, errors
            results.append('no browser runtime errors')
            blocked.close()
            context.close()
            browser.close()
        (output / 'report.json').write_text(json.dumps({'passed': results}, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'passed': results}, ensure_ascii=True))
    finally:
        backend.shutdown()
        backend.server_close()


if __name__ == '__main__':
    run()
