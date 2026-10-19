/** 识别后端返回的本站文件接口；外部链接与其他协议仍使用原有浏览器行为。 */
export function generatedFileRequest(href) {
  if (typeof href !== 'string' || !href.startsWith('/') || href.startsWith('//')) return ''
  try {
    const url = new URL(href, 'https://local.invalid')
    const match = url.pathname.match(/\/files\/(download|pdf|file)\/[A-Za-z0-9_-]{8,128}$/)
    if (!match || url.hash || !url.searchParams.get('name') || [...url.searchParams.keys()].some(key => key !== 'name')) return ''
    // 使用 Axios 配置的 API 根地址，兼容前后端分离部署与自定义 context-path。
    return match[0] + url.search
  } catch { return '' }
}

/** 优先使用响应中的 UTF-8 附件名，缺失时回退到链接里的文件名。 */
export function downloadFilename(disposition, href) {
  const encoded = disposition?.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  const plain = disposition?.match(/filename="([^"]*)"/i)?.[1]
  let name = plain || new URL(href, 'https://local.invalid').searchParams.get('name') || 'download'
  if (encoded) {
    try { name = decodeURIComponent(encoded) } catch { /* 非法响应头使用回退名称。 */ }
  }
  return name.split(/[\\/]/).at(-1).replace(/[\u0000-\u001f\u007f]/g, '') || 'download'
}
