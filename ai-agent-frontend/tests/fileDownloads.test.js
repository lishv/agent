import { test } from 'node:test'
import assert from 'node:assert/strict'
import MarkdownIt from 'markdown-it'
import { generatedFileRequest, downloadFilename } from '../src/services/fileDownloads.js'

test('识别真实下载链接并通过已配置的 API 根地址请求，保留编码的中文和特殊字符', () => {
  const href = '/custom/api/files/pdf/download-run-1?name=%E6%8A%A5%E5%91%8A%20%2B%20%281%29.pdf'
  assert.equal(generatedFileRequest(href), '/files/pdf/download-run-1?name=%E6%8A%A5%E5%91%8A%20%2B%20%281%29.pdf')
  assert.match(new MarkdownIt().render(`[下载报告](${href})`), /<a href=/)
})

test('不接管外站、沙箱或不合法的文件链接', () => {
  for (const href of ['https://example.com/files/pdf/download-run-1?name=a.pdf', '//example.com/files/pdf/download-run-1?name=a.pdf', 'sandbox:/tmp/file.pdf', 'javascript:alert(1)', '/api/files/chat-memory/download-run-1?name=a', '/api/files/pdf/short?name=a', '/api/files/pdf/download-run-1', '/api/files/pdf/download-run-1?name=a&owner=2']) {
    assert.equal(generatedFileRequest(href), '', href)
  }
})

test('优先使用中文附件名并移除路径和控制字符', () => {
  assert.equal(downloadFilename("attachment; filename=\"fallback.pdf\"; filename*=UTF-8''%E4%B8%AD%E6%96%87%20%2B%20%281%29.pdf", '/api/files/pdf/download-run-1?name=x'), '中文 + (1).pdf')
  assert.equal(downloadFilename(undefined, '/api/files/file/download-run-1?name=nested%2F%E7%AC%94%E8%AE%B0.txt'), '笔记.txt')
  assert.equal(downloadFilename('attachment; filename="../bad\n.txt"', '/api/files/file/download-run-1?name=x'), 'bad.txt')
})
