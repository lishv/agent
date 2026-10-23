/** 识别本站附件链接，兼容模型误拼的一层用户目录；文件归属仍由后端登录态校验。 */
export function generatedFileRequest(href, origin = globalThis.location?.origin || 'https://local.invalid') {
  if (typeof href !== 'string' || href.startsWith('//') || !(href.startsWith('/') || /^https?:\/\//i.test(href))) return ''
  try {
    const url = new URL(href, origin)
    if (url.origin !== new URL(origin).origin || url.username || url.password) return ''
    const match = url.pathname.match(/\/files\/(download|pdf|file)\/(?:[0-9]{1,19}\/)?([A-Za-z0-9_-]{8,128})$/)
    if (!match || url.hash || !url.searchParams.get('name') || [...url.searchParams.keys()].some(key => key !== 'name')) return ''
    // 使用 Axios 配置的 API 根地址，兼容前后端分离部署与自定义 context-path。
    return `/files/${match[1]}/${match[2]}${url.search}`
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
