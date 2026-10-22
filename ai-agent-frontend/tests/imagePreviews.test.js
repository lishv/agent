import { test } from 'node:test'
import assert from 'node:assert/strict'
import MarkdownIt from 'markdown-it'
import { collectMessageImages, imagePreviewSource } from '../src/services/imagePreviews.js'
import { presentMessage } from '../src/services/messagePresentation.js'

const markdown = new MarkdownIt({ html: false, linkify: true })
const collect = text => collectMessageImages(markdown.parse(text, {}))

test('本站中文图片从 name 参数识别，复用自定义 API 根路径并排除 PDF', () => {
  const href = '/custom/api/files/download/download-run-1?name=%E5%B0%8F%E7%8C%AB%20%2B%20%281%29.PNG'
  const [image] = collect(`[下载小猫](${href})\n[报告](/api/files/pdf/download-run-1?name=report.pdf)`)
  assert.equal(image.href, href)
  assert.equal(image.label, '下载小猫')
  assert.equal(image.generated, true)
  assert.equal(image.filename, '小猫 + (1).PNG')
  assert.equal(image.key, '/files/download/download-run-1?name=%E5%B0%8F%E7%8C%AB%20%2B%20%281%29.PNG')
})

test('图片链接和显式图片去重，来源网页、代码块和行内代码不产生预览', () => {
  const text = '[小猫](https://cdn.example/cat.jpg)\n![同一张小猫](https://cdn.example/cat.jpg)\n[来源](https://example.test/cats)\n`![示例](https://cdn.example/code.png)`\n\n```markdown\n![代码](https://cdn.example/code-block.png)\n```'
  const images = collect(text)
  assert.equal(images.length, 1)
  assert.equal(images[0].label, '小猫')
  assert.equal(images[0].generated, false)
})

test('搜索结果支持无扩展名图片和缩略图，文本格式化不会反复增加预览', () => {
  const data = { status: 'ok', images: [
    { tags: '小猫', downloadUrl: 'https://cdn.example/image?id=1', pageUrl: 'https://example.test/cats' },
    { tags: '预览图', downloadUrl: 'https://cdn.example/thumb.webp', isPreview: true, width: 320, height: 200 },
  ] }
  const text = presentMessage(`工具 searchImage：${JSON.stringify(data)}`, 'manus')
  const images = collect(text)
  assert.deepEqual(images.map(image => image.href), ['https://cdn.example/image?id=1', 'https://cdn.example/thumb.webp'])
  assert.equal(collect(presentMessage(text, 'manus')).length, 2)
  assert.ok(text.includes('（预览图）'))
})

test('兼容历史图片下载链接、裸图片地址、嵌套图片链接和流式补全', () => {
  assert.equal(collect('旧记录：[图片](https://cdn.example/cat.jpeg?size=large)').length, 1)
  assert.equal(collect('https://cdn.example/cat.gif').length, 1)
  assert.equal(collect('[![小猫](https://cdn.example/image?id=1)](https://example.test/cats)').length, 1)
  assert.equal(collect('[下载图片](/api/files/download/download-run-1?name=cat.png').length, 0)
  assert.equal(collect('[下载图片](/api/files/download/download-run-1?name=cat.png)').length, 1)
})

test('不将脚本、沙箱、本地文件、协议相对地址和不合法的附件地址作为预览请求', () => {
  for (const href of ['javascript:alert(1)', 'sandbox:/tmp/cat.png', 'file:///tmp/cat.png', '//example.test/cat.png', '/secret/cat.png', '/api/files/chat-memory/download-run-1?name=cat.png', 'https://user:secret@example.test/cat.png']) {
    assert.equal(imagePreviewSource(href, true), null, href)
  }
})
