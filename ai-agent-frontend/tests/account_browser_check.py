"""账号、应用历史、逻辑删除和生产构建离线恢复的浏览器验证；无真实邮件或模型调用。"""
import json, os, sys, threading, time, uuid
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from http.cookies import SimpleCookie
from urllib.parse import urlparse
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.test-tools'))
from playwright.sync_api import sync_playwright, expect
accounts={
    'alice@example.test':{'id':'101','email':'alice@example.test','displayName':'Alice','password':'password123','version':1},
    'bob@example.test':{'id':'102','email':'bob@example.test','displayName':'Bob','password':'password123','version':1},
}
sessions={}; histories={}; deleted=[]; issued_codes={}; code_requests=[]; code_logins=[]
CODE_VALUES={'register':'123456','reset_password':'234567','login':'345678'}
def consume_code(email,purpose,code):
    if issued_codes.get((email,purpose))!=code: return False
    del issued_codes[(email,purpose)]; return True
def message(role,content): return {'id':str(uuid.uuid4()),'role':role,'content':content,'status':'done','createdAt':int(time.time()*1000),'questions':[]}
def fixture(id,owner,app,title,reply):
    now=int(time.time()*1000)
    histories[id]={'id':id,'owner':owner,'appId':app,'title':title,'createdAt':now,'updatedAt':now,'messages':[message('user',title),message('assistant',reply)],'isDeleted':False}
fixture('alice-love','101','love','你好我是小李','这是你的恋爱大师历史。')
fixture('alice-manus','101','manus','你好我是小李','这是你的超级智能体历史。')
fixture('bob-love','102','love','Bob 的对话','这是 Bob 自己的历史。')

class Backend(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def state(self):
        cookie=SimpleCookie(self.headers.get('Cookie','')); id=cookie.get('TEST_SESSION')
        id=id.value if id else None
        if id not in sessions:
            id=str(uuid.uuid4()); sessions[id]={'email':None,'version':None,'csrf':str(uuid.uuid4())}
        self.sid=id
        return sessions[id]
    def user(self,state):
        user=accounts.get(state['email'])
        return user if user and user['version']==state['version'] else None
    def public_user(self,user): return {key:user[key] for key in ['id','email','displayName']} if user else None
    def respond(self,status,data):
        self.send_response(status); self.send_header('Content-Type','application/json; charset=utf-8')
        if hasattr(self,'sid'): self.send_header('Set-Cookie',f'TEST_SESSION={self.sid}; Path=/; HttpOnly; SameSite=Lax')
        self.end_headers(); self.wfile.write(json.dumps(data,ensure_ascii=False).encode())
    def do_GET(self):
        path=urlparse(self.path).path
        if path=='/api/health':
            self.send_response(200); self.end_headers(); self.wfile.write(b'ok'); return
        state=self.state(); user=self.user(state)
        if path=='/api/auth/session': self.respond(200,{'user':self.public_user(user),'csrfToken':state['csrf']}); return
        if not user: self.respond(401,{'message':'请先登录'}); return
        if self.headers.get('X-Account-ID') and self.headers.get('X-Account-ID') != user['id']:
            self.respond(409,{'message':'账号已切换','code':40901}); return
        if path=='/api/chats':
            self.respond(200,[{k:v for k,v in item.items() if k not in ['owner','messages','isDeleted']} for item in histories.values() if item['owner']==user['id'] and not item['isDeleted']]); return
        if path.startswith('/api/chats/'):
            item=histories.get(path.split('/')[-1])
            if not item or item['owner']!=user['id'] or item['isDeleted']: self.respond(404,{'message':'对话不存在'}); return
            self.respond(200,{k:v for k,v in item.items() if k not in ['owner','isDeleted']}); return
        self.respond(404,{})
    def do_POST(self):
        state=self.state(); user=self.user(state); path=urlparse(self.path).path
        body=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
        if self.headers.get('X-CSRF-TOKEN')!=state['csrf']: self.respond(403,{'message':'请刷新登录状态'}); return
        email=body.get('email','').strip().lower()
        if path=='/api/auth/email-code':
            purpose=body.get('purpose')
            if purpose not in CODE_VALUES: self.respond(400,{'message':'验证码用途不正确'}); return
            issued_codes[(email,purpose)]=CODE_VALUES[purpose]; code_requests.append((email,purpose)); self.respond(200,{'sent':True}); return
        if path=='/api/auth/register':
            if not body.get('displayName','').strip(): self.respond(400,{'message':'请输入昵称'}); return
            if not consume_code(email,'register',body.get('code')): self.respond(400,{'message':'验证码错误或已过期'}); return
            if email in accounts: self.respond(409,{'message':'该邮箱已注册'}); return
            accounts[email]={'id':str(100+len(accounts)+1),'email':email,'displayName':body.get('displayName') or '新用户','password':body['password'],'version':1}
        if path=='/api/auth/code-login':
            code_logins.append(body)
            if not consume_code(email,'login',body.get('code')): self.respond(400,{'message':'验证码错误或已过期'}); return
            if email not in accounts: self.respond(401,{'message':'该邮箱尚未注册，请先注册账号'}); return
        if path in ['/api/auth/login','/api/auth/register','/api/auth/code-login']:
            target=accounts.get(email)
            if not target or path!='/api/auth/code-login' and target['password']!=body.get('password'): self.respond(401,{'message':'邮箱或密码不正确'}); return
            state.update(email=email,version=target['version'],csrf=str(uuid.uuid4()))
            self.respond(200,{'user':self.public_user(target),'csrfToken':state['csrf']}); return
        if path=='/api/auth/logout': state.update(email=None,version=None,csrf=str(uuid.uuid4())); self.respond(200,{'loggedOut':True}); return
        if path=='/api/auth/reset-password':
            if email not in accounts or not consume_code(email,'reset_password',body.get('code')): self.respond(400,{'message':'验证码错误'}); return
            accounts[email]['password']=body['password']; accounts[email]['version']+=1; self.respond(200,{'changed':True}); return
        if not user: self.respond(401,{'message':'请先登录'}); return
        if self.headers.get('X-Account-ID') and self.headers.get('X-Account-ID')!=user['id']:
            self.respond(409,{'message':'账号已切换','code':40901}); return
        if path=='/api/auth/nickname':
            name=body.get('displayName','').strip()
            if not name or len(name)>40: self.respond(400,{'message':'昵称需为 1 至 40 个字符'}); return
            user['displayName']=name; self.respond(200,self.public_user(user)); return
        if path=='/api/auth/password':
            if body.get('currentPassword')!=user['password']: self.respond(400,{'message':'当前密码不正确'}); return
            user['password']=body['password']; user['version']+=1; self.respond(200,{'changed':True}); return
        if path.endswith('/chat/sse') or path.endswith('/manus/chat'):
            id=body['chatId']; app='manus' if '/manus/' in path else 'love'
            if id not in histories: fixture(id,user['id'],app,body['message'][:24],'服务器保存的回复。')
            else: histories[id]['messages'] += [message('user',body['message']),message('assistant','服务器保存的回复。')]
            self.send_response(200); self.send_header('Content-Type','text/event-stream; charset=utf-8'); self.end_headers()
            text='Step 1: 服务器保存的回复。' if app=='manus' else '服务器保存的回复。'
            self.wfile.write(('data: '+text+'\n\n').encode()); return
        self.respond(404,{})
    def do_PUT(self):
        state=self.state(); user=self.user(state)
        if not user: self.respond(401,{'message':'请先登录'}); return
        if self.headers.get('X-CSRF-TOKEN')!=state['csrf']: self.respond(403,{'message':'请刷新登录状态'}); return
        if self.headers.get('X-Account-ID')!=user['id']: self.respond(409,{'message':'账号已切换','code':40901}); return
        id=urlparse(self.path).path.split('/')[-2]; item=histories.get(id)
        body=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
        if not item or item['owner']!=user['id'] or item['isDeleted']: self.respond(404,{}); return
        title=body.get('title','').strip()
        if not title or len(title)>100: self.respond(400,{'message':'对话名需为 1 至 100 个字符'}); return
        item.update(title=title,updatedAt=int(time.time()*1000))
        self.respond(200,{k:v for k,v in item.items() if k not in ['owner','messages','isDeleted']})
    def do_DELETE(self):
        state=self.state(); user=self.user(state)
        if not user or self.headers.get('X-CSRF-TOKEN')!=state['csrf']: self.respond(401,{'message':'请先登录'}); return
        id=urlparse(self.path).path.split('/')[-1]; item=histories.get(id)
        if not item or item['owner']!=user['id']: self.respond(404,{}); return
        item['isDeleted']=True; item['messages']=[]; deleted.append(id); self.respond(200,{'deleted':True})

def login(page,email,password='password123'):
    page.locator('.profile-button').click(); page.get_by_label('邮箱',exact=True).fill(email)
    page.get_by_label('密码',exact=True).fill(password); page.get_by_role('button',name='登录',exact=True).click()
    expect(page.get_by_role('dialog')).to_have_count(0)
def run():
    backend=ThreadingHTTPServer(('127.0.0.1',18126),Backend); threading.Thread(target=backend.serve_forever,daemon=True).start()
    results=[]; output=ROOT/'test-results'; output.mkdir(exist_ok=True)
    try:
        with sync_playwright() as p:
            browser=p.chromium.launch(channel='msedge',headless=True)
            context=browser.new_context(viewport={'width':1440,'height':960}); page=context.new_page(); errors=[]
            page.on('pageerror',lambda e:errors.append(str(e)))
            base=os.environ.get('FRONTEND_TEST_URL','http://127.0.0.1:5176')
            page.goto(base); page.wait_for_load_state('networkidle')
            expect(page.locator('.profile-button')).to_contain_text('登录 / 注册')
            page.locator('.profile-button').click(); page.get_by_role('button',name='注册账号',exact=True).click()
            page.get_by_label('邮箱',exact=True).fill('new@example.test'); page.get_by_label('昵称',exact=True).fill('新朋友')
            page.get_by_role('button',name='获取验证码',exact=True).click(); expect(page.locator('.account-notice')).to_contain_text('验证码已发送')
            page.get_by_label('邮箱验证码',exact=True).fill('123456'); page.get_by_label('新密码',exact=True).fill('password123'); page.get_by_label('确认密码',exact=True).fill('password123')
            page.get_by_role('button',name='注册并登录',exact=True).click(); expect(page.locator('.profile-button')).to_contain_text('新朋友')
            page.locator('.profile-button').click(); page.get_by_role('button',name='退出登录',exact=True).click(); expect(page.locator('.profile-button')).to_contain_text('登录 / 注册')
            results.append('email code, registration and logout')
            page.locator('.profile-button').click(); page.get_by_role('button',name='验证码登录',exact=True).click()
            expect(page.get_by_label('密码',exact=True)).to_have_count(0)
            page.get_by_label('邮箱',exact=True).fill('missing@example.test'); page.get_by_role('button',name='获取验证码',exact=True).click()
            expect(page.locator('.account-notice')).to_contain_text('验证码已发送')
            page.get_by_label('邮箱验证码',exact=True).fill('345678'); page.get_by_role('button',name='登录',exact=True).click()
            expect(page.locator('.account-error')).to_contain_text('尚未注册')
            expect(page.get_by_role('button',name='验证码登录',exact=True)).to_have_attribute('aria-pressed','true')
            page.get_by_label('邮箱',exact=True).fill('alice@example.test'); page.get_by_role('button',name='获取验证码',exact=True).click()
            expect(page.locator('.account-notice')).to_contain_text('验证码已发送')
            page.get_by_label('邮箱验证码',exact=True).fill('000000'); page.get_by_role('button',name='登录',exact=True).click()
            expect(page.locator('.account-error')).to_contain_text('验证码错误')
            page.get_by_label('邮箱验证码',exact=True).fill('345678')
            page.screenshot(path=str(output/'account-code-login-desktop.png'),full_page=True)
            page.get_by_role('button',name='登录',exact=True).click(); expect(page.locator('.profile-button')).to_contain_text('Alice')
            token=context.request.get(base+'/api/auth/session').json()['csrfToken']
            replay=context.request.post(base+'/api/auth/code-login',data={'email':'alice@example.test','code':'345678'},headers={'X-CSRF-TOKEN':token})
            assert replay.status==400
            assert ('alice@example.test','login') in code_requests
            assert all(set(body)=={'email','code'} for body in code_logins)
            results.append('email code login, invalid/unregistered feedback, purpose and single-use validation; no password required')
            nickname_tab=context.new_page(); nickname_tab.goto(base); nickname_tab.wait_for_load_state('networkidle')
            page.locator('.profile-button').click(); page.get_by_role('button',name='修改昵称',exact=True).click()
            expect(page.get_by_label('昵称',exact=True)).to_have_value('Alice')
            page.get_by_label('昵称',exact=True).fill('   '); page.get_by_role('button',name='保存昵称',exact=True).click()
            expect(page.locator('.account-error')).to_contain_text('请输入昵称')
            page.get_by_label('昵称',exact=True).fill('  绘灵小李  '); page.get_by_role('button',name='保存昵称',exact=True).click()
            expect(page.locator('.account-notice')).to_contain_text('昵称已更新')
            expect(page.locator('.profile-button')).to_contain_text('绘灵小李')
            expect(nickname_tab.locator('.profile-button')).to_contain_text('绘灵小李')
            page.screenshot(path=str(output/'account-settings-desktop.png'),full_page=True)
            page.get_by_role('button',name='关闭账号窗口',exact=True).click(); nickname_tab.close()
            page.reload(); page.wait_for_load_state('networkidle'); expect(page.locator('.profile-button')).to_contain_text('绘灵小李')
            assert accounts['alice@example.test']['displayName']=='绘灵小李'
            assert accounts['alice@example.test']['password']=='password123' and accounts['alice@example.test']['version']==1
            results.append('nickname edit validates blank input, persists after reload, updates another tab and preserves login/history')
            expect(page.locator('.history-item')).to_have_count(2)
            assert page.locator('.history-app-label').all_text_contents()==['恋爱','智能体']
            page.get_by_role('button',name='恋爱',exact=True).click(); expect(page.locator('.history-item')).to_have_count(1)
            page.locator('.history-item').click(); expect(page.locator('.conversation-list')).to_contain_text('恋爱大师历史')
            page.get_by_role('button',name='智能体',exact=True).click(); page.locator('.history-item').click()
            expect(page.locator('.conversation-list')).to_contain_text('超级智能体历史')
            page.screenshot(path=str(output/'account-history-desktop.png'),full_page=True)
            results.append('same titles have distinct application labels; server restores both histories')
            page.locator('.history-row').hover()
            page.get_by_role('button',name='修改对话名：你好我是小李',exact=True).click()
            page.get_by_label('对话名',exact=True).fill('智能体学习计划')
            page.get_by_role('button',name='保存名称',exact=True).click()
            expect(page.get_by_role('dialog')).to_have_count(0)
            page.reload(); page.wait_for_load_state('networkidle')
            expect(page.locator('.history-item').filter(has_text='智能体学习计划')).to_have_count(1)
            assert histories['alice-manus']['title']=='智能体学习计划'
            expect(page.locator('.conversation-list')).to_contain_text('超级智能体历史')
            results.append('production build persists renamed conversation and preserves restored history')
            page.locator('#chat-input').fill('新的服务器会话'); page.get_by_role('button',name='发送消息',exact=True).click()
            expect(page.locator('.conversation-list')).to_contain_text('服务器保存的回复')
            page.wait_for_load_state('networkidle'); page.reload(); page.wait_for_load_state('networkidle')
            expect(page.locator('.conversation-list')).to_contain_text('服务器保存的回复')
            results.append('conversation resumes from server after reload')
            page.wait_for_function('!!navigator.serviceWorker.controller')
            page.get_by_role('button',name='全部',exact=True).click()
            context.set_offline(True); page.reload(); page.wait_for_load_state('domcontentloaded')
            try:
                expect(page.locator('.offline-chat-note')).to_contain_text('当前离线',timeout=12000)
            except Exception:
                page.screenshot(path=str(output/'offline-reload-debug.png'),full_page=True)
                print(json.dumps({'url':page.url,'body':page.locator('body').inner_text()[:700],'page_errors':errors,'worker':page.evaluate('navigator.serviceWorker.controller?.scriptURL'),'storage_keys':page.evaluate('Object.keys(localStorage)')},ensure_ascii=False))
                raise
            expect(page.locator('.conversation-list')).to_contain_text('服务器保存的回复')
            assert not page.locator('.history-item').filter(has_text='Bob').count()
            expect(page.locator('.profile-button')).to_contain_text('绘灵小李')
            page.get_by_role('button',name='删除对话：你好我是小李',exact=True).first.click()
            expect(page.get_by_role('button',name='取消',exact=True)).to_be_focused()
            page.keyboard.press('Shift+Tab'); expect(page.get_by_role('button',name='删除对话',exact=True)).to_be_focused()
            page.keyboard.press('Escape'); expect(page.get_by_role('dialog')).to_have_count(0)
            page.get_by_role('button',name='删除对话：你好我是小李',exact=True).first.click()
            page.get_by_role('button',name='删除对话',exact=True).click(); expect(page.get_by_role('dialog')).to_have_count(0)
            assert not histories['alice-love']['isDeleted']
            context.set_offline(False)
            expect(page.locator('.offline-chat-note')).to_have_count(0,timeout=15000)
            page.wait_for_function("!JSON.parse(localStorage.getItem('huiling.chat.v2.101')).deletedIds.length")
            assert histories['alice-love']['isDeleted'] and not histories['alice-love']['messages']
            results.append('true offline reload uses app shell and per-user cache; queued deletion syncs online and clears history')
            page.locator('.profile-button').click(); page.get_by_role('button',name='修改密码',exact=True).click()
            page.get_by_label('当前密码',exact=True).fill('password123'); page.get_by_label('新密码',exact=True).fill('newpassword123'); page.get_by_label('确认密码',exact=True).fill('newpassword123')
            page.get_by_role('button',name='保存新密码',exact=True).click(); expect(page.locator('.account-notice')).to_contain_text('密码已更新')
            page.get_by_label('邮箱',exact=True).fill('alice@example.test'); page.get_by_label('密码',exact=True).fill('newpassword123'); page.get_by_role('button',name='登录',exact=True).click()
            expect(page.locator('.profile-button')).to_contain_text('绘灵小李'); page.locator('.profile-button').click(); page.get_by_role('button',name='退出登录',exact=True).click()
            login(page,'bob@example.test'); expect(page.locator('.history-item')).to_have_count(1)
            expect(page.locator('.history-item')).to_contain_text('Bob 的对话'); assert not page.locator('.history-item').filter(has_text='你好我是小李').count()
            results.append('password change requires new login; switching accounts never mixes histories')
            page.locator('.profile-button').click(); page.get_by_role('button',name='退出登录',exact=True).click()
            page.locator('.profile-button').click(); page.get_by_role('button',name='忘记密码',exact=True).click()
            page.get_by_label('邮箱',exact=True).fill('bob@example.test'); page.get_by_role('button',name='获取验证码',exact=True).click()
            expect(page.locator('.account-notice')).to_contain_text('验证码已发送'); assert code_requests[-1]==('bob@example.test','reset_password')
            page.get_by_label('邮箱验证码',exact=True).fill('234567')
            page.get_by_label('新密码',exact=True).fill('resetpassword123'); page.get_by_label('确认密码',exact=True).fill('resetpassword123')
            page.get_by_role('button',name='重置密码',exact=True).click(); expect(page.locator('.account-notice')).to_contain_text('密码已更新')
            page.get_by_label('邮箱',exact=True).fill('bob@example.test'); page.get_by_label('密码',exact=True).fill('resetpassword123'); page.get_by_role('button',name='登录',exact=True).click()
            expect(page.locator('.profile-button')).to_contain_text('Bob')
            results.append('email code password reset and login with new password')
            page.set_viewport_size({'width':390,'height':844}); page.get_by_role('button',name='打开导航',exact=True).click(); page.locator('.profile-button').click()
            page.get_by_role('button',name='修改密码',exact=True).click(); assert not page.evaluate('document.documentElement.scrollWidth>innerWidth')
            page.screenshot(path=str(output/'account-password-mobile.png'),full_page=True)
            page.get_by_role('button',name='关闭账号窗口',exact=True).click()
            expect(page.get_by_role('button',name='打开导航',exact=True)).to_be_focused()
            other=context.new_page(); other.goto(base); other.wait_for_load_state('networkidle')
            other.locator('.profile-button').click(); other.get_by_role('button',name='退出登录',exact=True).click()
            expect(page.locator('.profile-button')).to_contain_text('登录 / 注册')
            login(other,'alice@example.test','newpassword123')
            expect(page.locator('.profile-button')).to_contain_text('绘灵小李')
            expect(page.locator('.history-item')).to_have_count(1)
            assert not page.locator('.history-item').filter(has_text='Bob').count()
            other.close(); results.append('account changes in another tab refresh identity and isolate cached histories')
            assert not errors,errors
            cache_urls=page.evaluate('Promise.all([]).then(async()=>{let urls=[];for(const key of await caches.keys()){const cache=await caches.open(key);urls.push(...(await cache.keys()).map(r=>r.url));}return urls;})')
            assert all('/api/' not in url for url in cache_urls)
            results.append('mobile form and navigation; app-shell cache excludes APIs; no runtime errors')
            browser.close()
        print(json.dumps({'passed':results,'logical_deletions':deleted},ensure_ascii=False))
    finally: backend.shutdown(); backend.server_close()
if __name__=='__main__': run()
