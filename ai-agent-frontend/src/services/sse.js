/** SSE 行解析器：保留空格，支持跨网络分片的 CR/LF、UTF-8 BOM 与多行 data。 */
export function createSseParser(onEvent) {
  let buffer = ''
  let data = []
  let event = ''
  let firstChunk = true

  function line(text) {
    if (text === '') {
      if (data.length) onEvent({ event: event || 'message', data: data.join('\n') })
      data = []
      event = ''
      return
    }
    if (text.startsWith(':')) return
    const colon = text.indexOf(':')
    const field = colon === -1 ? text : text.slice(0, colon)
    let value = colon === -1 ? '' : text.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'data') data.push(value)
    if (field === 'event') event = value
  }

  function drain(final = false) {
    let start = 0
    for (let i = 0; i < buffer.length; i += 1) {
      const char = buffer[i]
      if (char !== '\r' && char !== '\n') continue
      if (char === '\r' && i === buffer.length - 1 && !final) break
      line(buffer.slice(start, i))
      if (char === '\r' && buffer[i + 1] === '\n') i += 1
      start = i + 1
    }
    buffer = buffer.slice(start)
  }

  return {
    feed(chunk) {
      if (!chunk) return
      if (firstChunk) {
        chunk = chunk.replace(/^\uFEFF/, '')
        firstChunk = false
      }
      buffer += chunk
      drain()
    },
    // SSE 规范要求空行结束事件；不将断线留下的半条事件当作完整回复。
    finish() {
      drain(true)
      buffer = ''
      data = []
      event = ''
    },
  }
}
