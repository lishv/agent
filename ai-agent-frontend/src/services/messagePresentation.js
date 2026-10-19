import { decodeHTML } from 'entities'

const badEncoding = /[\uFFFD\uE000-\uF8FF]|锟斤拷|楂樻竻|澹佺焊|鑾峰彇|缁撴灉|(?:鑷|楂|鍙|鏂|鐨|銆){2}/
const escapeMarkdown = text => text.replace(/[\\`*_[\]<>]/g, '\\$&')
const readable = value => typeof value === 'string' && !badEncoding.test(value) ? value.trim() : ''

// 工具标识只用于匹配操作类型，界面和复制内容统一使用可读说明。
const toolActions = {
  searchImage: '搜索图片',
  searchWebScraping: '搜索资料',
  searchWeb: '搜索资料',
  search: '搜索资料',
  scrapeWebPage: '读取网页',
  downloadResource: '下载文件',
  executeTerminalCommand: '运行命令',
  generatePDF: '生成 PDF',
  readFile: '读取文件',
  writeFile: '保存文件',
  askHuman: '请求补充信息',
  doTerminate: '完成任务',
  maps_text_search: '搜索地点',
  maps_around_search: '搜索周边',
  maps_search_detail: '查询地点详情',
  maps_geo: '查询位置',
  maps_regeocode: '查询地址',
  maps_ip_location: '查询位置',
  maps_weather: '查询天气',
  maps_distance: '计算距离',
  maps_direction_walking: '规划步行路线',
  maps_direction_driving: '规划驾车路线',
  maps_direction_bicycling: '规划骑行路线',
  maps_direction_transit_integrated: '规划公交路线',
  maps_schema_personal_map: '生成地图',
  maps_schema_navi: '生成导航',
  maps_schema_take_taxi: '获取打车信息',
}
const toolNames = Object.keys(toolActions).sort((first, second) => second.length - first.length)

/** 兼容 MCP 服务添加的命名空间，优先匹配完整工具名，避免把地点搜索误识别为普通搜索。 */
function resolveToolName(tool) {
  return toolNames.find(name => tool === name || ['.', ':', '_', '-'].some(separator => tool.endsWith(separator + name)))
}

function safeLink(value) {
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) ? url.href.replace(/[()<>]/g, char => '%' + char.charCodeAt(0).toString(16).toUpperCase()) : ''
  } catch { return '' }
}
/** 旧会话仍可能含 HTML；提取正文，移除脚本、样式和页面源代码。 */
function webPageText(html) {
  const body = html.match(/<body\b[^>]*>([\s\S]*?)<\/body\s*>/i)?.[1] || html
  return decodeHTML(body
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<(script|style|noscript|template|svg|head)\b[^>]*>[\s\S]*?<\/\1\s*>/gi, '')
    .replace(/<br\s*\/?>|<\/(?:p|div|li|h[1-6]|section|article|tr)>/gi, '\n')
    .replace(/<[^>]*>/g, ' '))
    .split('\n').map(line => readable(line.replace(/\s+/g, ' '))).filter(Boolean).join('\n')
}
function searchResults(items) {
  if (!items.length) return '没有找到相关搜索结果。'
  return items.map((item, index) => {
    const title = readable(item?.title) || `搜索结果 ${index + 1}`
    const link = safeLink(item?.link || item?.url)
    const snippet = readable(item?.snippet || item?.description)
    return `${index + 1}. ${link ? `[${escapeMarkdown(title)}](${link})` : escapeMarkdown(title)}${snippet ? `\n   ${escapeMarkdown(snippet)}` : ''}`
  }).join('\n\n')
}

// MCP 的文本块可能再包含一层 JSON 字符串，展开后再按具体工具展示。
function unwrapToolResult(raw) {
  let result = raw.trim()
  for (let depth = 0; depth < 4; depth++) {
    try {
      const data = JSON.parse(result)
      if (typeof data === 'string') { result = data; continue }
      const blocks = Array.isArray(data) ? data : data?.content
      if (Array.isArray(blocks) && blocks.length && blocks.every(item => typeof item?.text === 'string')) {
        result = blocks.map(item => item.text).join('\n'); continue
      }
    } catch { /* 普通工具文本无需展开。 */ }
    break
  }
  return result
}

function imageSearchResults(data) {
  if (data?.status !== 'ok') return readable(data?.message) || '图片搜索暂时没有可用结果。'
  const images = Array.isArray(data.images) ? data.images : []
  const links = images.map((image, index) => {
    const url = safeLink(image?.downloadUrl || image?.url)
    if (!url) return ''
    const title = readable(image?.tags) || `图片 ${index + 1}`
    const page = safeLink(image?.pageUrl)
    const dimensions = Number.isInteger(image?.width) && image.width > 0 && Number.isInteger(image?.height) && image.height > 0
      ? ` · ${image.width} × ${image.height}${image.isPreview ? '（预览图）' : ''}` : ''
    return `${index + 1}. [${escapeMarkdown(title)}](${url})${dimensions}${page ? ` · [来源页面](${page})` : ''}`
  }).filter(Boolean)
  const source = escapeMarkdown(readable(data?.source) || '图库')
  const incomplete = data?.complete === false ? `\n${escapeMarkdown(readable(data?.message) || '当前结果不足，请查看实际张数。')}\n` : ''
  return links.length ? `\n找到 ${links.length} 张 ${source} 图片：${incomplete}\n\n${links.join('\n\n')}` : '没有找到可用的图片地址。'
}

/** 搜索与网页展示可读内容；其他结构数据仍隐藏原始 JSON 和私人字段。 */
export function presentMessage(content, appId, role = 'assistant') {
  if (!['manus', 'love'].includes(appId) || role !== 'assistant') return content || ''
  const text = content || ''
  const toolPattern = /工具[ \t]+([\w.:-]+)(?:[ \t]+返回的结果)?[ \t]*[：:][ \t]*([\s\S]*?)(?=\n[ \t]*工具[ \t]+[\w.:-]+(?:[ \t]+返回的结果)?[ \t]*[：:]|$)/g
  const seen = new Set()
  const output = text.replace(toolPattern, (_, tool, raw) => {
    const result = unwrapToolResult(raw)
    const key = `${tool}:${result}`
    if (seen.has(key)) return ''
    seen.add(key)
    const name = resolveToolName(tool)
    let incomplete = /^Error\b|(?:^|\n)Command execution (?:failed|interrupted)\b/i.test(result.trim())
    let detail = result
    if (name === 'searchImage' && !result.trim()) {
      detail = '图片搜索没有返回可用结果。'
    } else if (/<!doctype\s+html|<html\b|<head\b|<body\b|<script\b/i.test(result)) {
      detail = '\n' + (webPageText(result) || '已读取网页内容，但页面没有可读正文。')
    } else if (/^[\[{]/.test(result)) {
      try {
        const data = JSON.parse(result)
        const items = Array.isArray(data) ? data : data?.data?.records || data?.data || data?.records
        if (name === 'searchImage' && data?.status) incomplete = !['ok', 'no_results'].includes(data.status)
        detail = name === 'searchImage' && data?.status ? imageSearchResults(data)
          : name === 'searchWebScraping' && Array.isArray(items) ? '\n' + searchResults(items)
          : Array.isArray(items) ? `已获取 ${items.length} 条数据。` : '已获取结构化数据。'
      } catch { detail = '已获取结构化数据。' }
    } else if (badEncoding.test(result)) {
      detail = '已收到结果，部分文字编码异常。'
    }
    const action = toolActions[name]
    let title = incomplete ? '操作未完成' : '操作结果'
    if (action) title = incomplete ? `${action}未完成` : `已${action}`
    return `**${title}**：${detail}`
  })
  return seen.size ? output.replace(/\n{3,}/g, '\n\n').trim() : output
}
