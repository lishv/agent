import test from 'node:test'
import assert from 'node:assert/strict'
import { createSseParser } from '../src/services/sse.js'

function parse(chunks) {
  const events = []
  const parser = createSseParser((event) => events.push(event))
  for (const chunk of chunks) parser.feed(chunk)
  parser.finish()
  return events
}

test('解析普通事件和连续事件', () => {
  assert.deepEqual(parse(['data: 你好\n\ndata: 世界\n\n']), [
    { event: 'message', data: '你好' }, { event: 'message', data: '世界' },
  ])
})

test('保留空格、空 data 和多行文本，不 trim 模型片段', () => {
  assert.deepEqual(parse(['data:   hello \ndata:\ndata: world\n\n']), [
    { event: 'message', data: '  hello \n\nworld' },
  ])
})

test('任意字符处分片仍正确解析 CRLF、多行和 event 字段', () => {
  const source = 'event: progress\r\ndata: Step 1: 搜索\r\ndata: 完成\r\n\r\ndata: 最终结果\r\n\r\n'
  const expected = [{ event: 'progress', data: 'Step 1: 搜索\n完成' }, { event: 'message', data: '最终结果' }]
  for (let i = 1; i < source.length; i += 1) assert.deepEqual(parse([source.slice(0, i), source.slice(i)]), expected)
  assert.deepEqual(parse([...source]), expected)
})

test('支持 CR 行尾、BOM、注释心跳，忽略不相关字段', () => {
  assert.deepEqual(parse(['', '\uFEFF: heartbeat\rid: abc\rretry: 3000\rdata: hi\r\r']), [{ event: 'message', data: 'hi' }])
})

test('只有事件完整到达才分发，不把断线半帧显示为完整回复', () => {
  assert.deepEqual(parse(['data: 完整\n\ndata: 不完整\n']), [{ event: 'message', data: '完整' }])
})

test('工具返回 JSON 当作内容保存，不误解析或丢失引号', () => {
  assert.deepEqual(parse(['data: Step 1: 工具返回：{"title":"中文"}\n\n']), [{ event: 'message', data: 'Step 1: 工具返回：{"title":"中文"}' }])
})
