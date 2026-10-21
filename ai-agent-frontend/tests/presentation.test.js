import { test } from 'node:test'
import assert from 'node:assert/strict'
import MarkdownIt from 'markdown-it'
import { presentMessage } from '../src/services/messagePresentation.js'

test('图片搜索结果标记可预览的图片，兼容没有扩展名的真实图片地址并保留下载与来源', () => {
  const image = 'https://cdn.example/image?id=123'
  const result = { status: 'ok', images: [{ tags: '小猫', downloadUrl: image, pageUrl: 'https://example.test/cats' }] }
  for (const app of ['love', 'manus']) {
    const text = presentMessage(`工具 searchImage：${JSON.stringify(result)}`, app)
    const html = new MarkdownIt().render(text)
    assert.match(html, /<img src="https:\/\/cdn\.example\/image\?id=123"/)
    assert.ok(text.includes(`[小猫](${image})`))
    assert.ok(text.includes('[来源页面](https://example.test/cats)'))
  }
})

test('MCP 图片搜索展示下载地址和来源，隐藏原始结构', () => {
  const result = { status: 'ok', source: 'Pixabay', images: [
    { tags: '山间湖泊', downloadUrl: 'https://cdn.example/lake.jpg', pageUrl: 'https://pixabay.com/photos/lake/', width: 1280, height: 720, isPreview: true },
    { tags: '无效资源', url: 'javascript:alert(1)' },
  ] }
  const wrapped = JSON.stringify([{ text: JSON.stringify(JSON.stringify(result)) }])
  const text = presentMessage(`工具 searchImage 返回的结果：${wrapped}`, 'manus')
  assert.ok(text.includes('[山间湖泊](https://cdn.example/lake.jpg)'))
  assert.ok(text.includes('[来源页面](https://pixabay.com/photos/lake/)'))
  assert.ok(!text.includes('javascript:'))
  assert.ok(!text.includes('"images"'))
  assert.ok(text.startsWith('**已搜索图片**'))
  assert.ok(text.includes('1280 × 720（预览图）'))
  assert.ok(!text.includes('searchImage'))
  const fallback = presentMessage(`工具 searchImage：${JSON.stringify({ status: 'ok', source: 'Wallhaven', complete: false, message: '仅找到 1/3 张图片', images: result.images.slice(0, 1) })}`, 'manus')
  assert.ok(fallback.includes('Wallhaven'))
  assert.ok(!fallback.includes('Pixabay 图片'))
  assert.ok(fallback.includes('仅找到 1/3 张图片'))
})

test('图片搜索空结果和限流反馈可读，网页媒体链接保留', () => {
  const empty = presentMessage('工具 searchImage 返回的结果：[{"text":""}]', 'manus')
  assert.ok(empty.includes('没有返回可用结果'))
  const rate = JSON.stringify([{ text: JSON.stringify({ status: 'rate_limited', message: '请等待 60 秒后再搜索。', retryAfterSeconds: 60 }) }])
  const limited = presentMessage(`工具 searchImage 返回的结果：${rate}`, 'manus')
  assert.ok(limited.includes('请等待 60 秒'))
  assert.ok(limited.startsWith('**搜索图片未完成**'))
  const media = '网页标题：资源\n图片链接：\n[图片](https://cdn.example/photo.jpg)\n音频链接：\n[音乐](https://cdn.example/music.mp3)'
  const output = presentMessage(`工具 scrapeWebPage 返回的结果：${JSON.stringify(media)}`, 'manus')
  assert.ok(output.includes('[图片](https://cdn.example/photo.jpg)'))
  assert.ok(output.includes('[音乐](https://cdn.example/music.mp3)'))
})

test('没有正文的网页明确说明，图片 JSON 保留可读摘要', () => {
  assert.equal(presentMessage('工具 scrapeWebPage 返回的结果："<!doctype html><html><script>app()</script></html>"', 'manus'), '**已读取网页**：\n已读取网页内容，但页面没有可读正文。')
  const data = JSON.stringify([{ name: '鑷劧椋庢櫙', user: { email: 'private@example.test' } }, { name: '图片' }])
  assert.equal(presentMessage(`工具 executeTerminalCommand 返回的结果：${data}`, 'manus'), '**已运行命令**：已获取 2 条数据。')
  assert.equal(presentMessage('工具 search 返回的结果：{"id":"123","name":"鑷劧', 'manus'), '**已搜索资料**：已获取结构化数据。')
})

test('历史乱码隐藏，正常中文、多个工具及用户输入保持可读', () => {
  assert.equal(presentMessage('工具 readFile 返回的结果：涓枃', 'manus'), '**已读取文件**：已收到结果，部分文字编码异常。')
  assert.equal(presentMessage('工具 readFile 返回的结果：中文内容\n工具 generatePDF 返回的结果：已生成报告', 'manus'), '**已读取文件**：中文内容\n**已生成 PDF**：已生成报告')
  const input = '<html>用户自己的示例</html>'
  assert.equal(presentMessage(input, 'manus', 'user'), input)
  assert.equal(presentMessage('请使用 JSON 格式：{"id":1}', 'manus'), '请使用 JSON 格式：{"id":1}')
  const download = 'Resource downloaded successfully.\n[下载企鹅照片](/api/files/download/run-1?name=penguin.jpg)'
  for (const app of ['manus', 'love']) {
    assert.equal(presentMessage(`工具 downloadResource：${download}`, app), `**已下载文件**：${download}`)
    assert.equal(presentMessage('工具 downloadResource：用户自己的文字', app, 'user'), '工具 downloadResource：用户自己的文字')
  }
})

test('搜索工具展示完整标题、可访问链接与摘要，重复结果不重复展示', () => {
  const results = JSON.stringify([{ title: 'Vue 学习路线', link: 'https://vuejs.org/guide/', snippet: '先掌握响应式与组件。' }, { title: 'Java 文档', link: 'https://docs.oracle.com/', snippet: '从基础语法开始。' }])
  const raw = `工具 searchWebScraping 返回的结果：${results}`
  const text = presentMessage(`${raw}\n${raw}`, 'manus')
  assert.ok(text.includes('Vue 学习路线') && text.includes('https://vuejs.org/guide/') && text.includes('先掌握响应式与组件。'))
  assert.ok(text.includes('Java 文档') && text.includes('从基础语法开始。'))
  assert.equal(text.match(/Vue 学习路线/g).length, 1)
  const prefixed = presentMessage(`工具 remote.searchWebScraping 返回的结果：${results}`, 'manus')
  assert.ok(prefixed.startsWith('**已搜索资料**'))
  assert.ok(prefixed.includes('https://vuejs.org/guide/'))
  assert.ok(!prefixed.includes('searchWebScraping'))
})

test('网页工具显示正文并过滤脚本源码，编码异常不暴露给用户', () => {
  const html = '<!doctype html><html><head><title>学习指南</title></head><body><h1>Java 学习</h1><p>先学习基础语法。</p><p>再完成一个项目。</p><script>alert(1)</script></body></html>'
  const text = presentMessage(`工具 scrapeWebPage 返回的结果：${JSON.stringify(html)}`, 'manus')
  assert.ok(text.includes('Java 学习') && text.includes('先学习基础语法。') && text.includes('再完成一个项目。'))
  assert.ok(!text.includes('<html>') && !text.includes('alert(1)'))
  const results = JSON.stringify([{ title: '鑷劧椋庢櫙', link: 'https://example.test/', snippet: '正常摘要' }])
  assert.ok(!presentMessage(`工具 searchWebScraping 返回的结果：${results}`, 'manus').includes('鑷'))
  assert.equal(presentMessage('工具 amap_maps_around_search 返回的结果：找到了三个地点', 'manus'), '**已搜索周边**：找到了三个地点')
  assert.equal(presentMessage('工具 futureTool 返回的结果：保留执行信息', 'manus'), '**操作结果**：保留执行信息')
  assert.equal(presentMessage('工具 executeTerminalCommand 返回的结果：Command execution failed with exit code: 1', 'manus'), '**运行命令未完成**：Command execution failed with exit code: 1')
})
