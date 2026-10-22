import { generatedFileRequest } from './fileDownloads.js'

const imageExtension = /\.(?:avif|bmp|gif|jpe?g|png|webp)$/i

/** 普通链接按图片文件名识别；显式 Markdown 图片也接受没有扩展名的地址。 */
export function imagePreviewSource(href, explicit = false) {
  if (typeof href !== 'string' || !href) return null
  const request = generatedFileRequest(href)
  try {
    const url = new URL(href, 'https://local.invalid')
    if (request) {
      const filename = url.searchParams.get('name')
      return explicit || imageExtension.test(filename)
        ? { href, key: request, generated: true, filename } : null
    }
    if (!/^https?:\/\//i.test(href) || url.username || url.password) return null
    const filename = url.searchParams.get('filename') || url.searchParams.get('name') || decodeURIComponent(url.pathname).split('/').at(-1)
    return explicit || imageExtension.test(filename)
      ? { href: url.href, key: url.href, generated: false, filename } : null
  } catch { return null }
}

/** 只读取 Markdown 图片和链接，忽略代码示例；同一消息内的相同资源只预览一次。 */
export function collectMessageImages(tokens) {
  const images = new Map()
  function add(href, label, explicit) {
    const source = imagePreviewSource(href, explicit)
    if (!source || images.has(source.key)) return
    images.set(source.key, { ...source, label: label?.trim() || source.filename || '图片' })
  }
  function visit(children) {
    children.forEach((token, index) => {
      if (token.type === 'image') add(token.attrGet('src'), token.content, true)
      if (token.type === 'link_open') {
        let label = ''
        for (let next = index + 1; next < children.length && children[next].type !== 'link_close'; next++) {
          if (['text', 'code_inline', 'image'].includes(children[next].type)) label += children[next].content
        }
        add(token.attrGet('href'), label, false)
      }
      if (token.children) visit(token.children)
    })
  }
  visit(tokens)
  return [...images.values()]
}
