// 轻量 markdown 渲染：标题/加粗/行内代码/链接/引用/列表/真表格，输出已转义的 HTML
// 不引第三方库，覆盖知识库文档的常见写法
// opts.inline 可注入自定义行内处理（如问答页的引用角标）

const esc = (s) => String(s)
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

function stdInline(s) {
  return esc(s)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
    .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (m, t, u) =>
      /^https?:/.test(u) ? `<a href="${u}" target="_blank" rel="noopener">${t}</a>` : `<span class="md-link-dead">${t}</span>`)
}

const splitRow = (t) => t.slice(1, -1).split('|').map((c) => c.trim())
const isSepRow = (t) => /^\|[\s:|-]+\|$/.test(t)

export function mdToHtml(md, opts = {}) {
  const inline = opts.inline || stdInline
  const lines = String(md || '').split('\n')
  let html = ''
  let list = null // 'ul' | 'ol' | null
  let inCode = false
  const closeList = () => { if (list) { html += `</${list}>`; list = null } }

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const fence = line.trim().match(/^```/)
    if (fence) {
      closeList()
      if (inCode) {
        // 代码块结束：包一层容器，带复制按钮（点击由外层统一处理）
        html += '</code></pre><button class="md-copy" type="button" title="复制代码">复制</button></div>'
      } else {
        html += '<div class="md-code"><pre class="md-pre"><code>'
      }
      inCode = !inCode
      continue
    }
    if (inCode) { html += esc(line) + '\n'; continue }

    const t = line.trim()
    const h = t.match(/^(#{1,4})\s+(.*)/)
    const ul = t.match(/^[-*]\s+(.*)/)
    const ol = t.match(/^\d+[.、]\s+(.*)/)
    const quote = t.match(/^>\s?(.*)/)

    if (h) {
      closeList()
      const lv = Math.min(h[1].length + 1, 5)
      html += `<h${lv}>` + inline(h[2]) + `</h${lv}>`
    } else if (ul) {
      if (list !== 'ul') { closeList(); html += '<ul>'; list = 'ul' }
      html += '<li>' + inline(ul[1]) + '</li>'
    } else if (ol) {
      if (list !== 'ol') { closeList(); html += '<ol>'; list = 'ol' }
      html += '<li>' + inline(ol[1]) + '</li>'
    } else if (quote) {
      closeList()
      html += '<blockquote>' + inline(quote[1]) + '</blockquote>'
    } else if (t === '') {
      closeList()
      html += '<div class="md-sp"></div>'
    } else if (/^\|.*\|/.test(t)) {
      // markdown 表格：收集连续表格行，第二行为分隔行时首行作表头
      closeList()
      const rows = []
      while (i < lines.length && /^\s*\|.*\|/.test(lines[i])) {
        rows.push(lines[i].trim())
        i++
      }
      i--
      let header = null
      let body = rows
      if (rows.length >= 2 && isSepRow(rows[1])) {
        header = splitRow(rows[0])
        body = rows.slice(2)
      }
      html += '<table class="md-table">'
      if (header) {
        html += '<thead><tr>' + header.map((c) => '<th>' + inline(c) + '</th>').join('') + '</tr></thead><tbody>'
      }
      for (const r of body) {
        if (isSepRow(r)) continue
        html += '<tr>' + splitRow(r).map((c) => '<td>' + inline(c) + '</td>').join('') + '</tr>'
      }
      html += '</tbody></table>'
    } else {
      closeList()
      html += '<p>' + inline(t) + '</p>'
    }
  }
  closeList()
  if (inCode) html += '</code></pre></div>' // 流式未闭合时也要收尾，避免嵌套错乱
  return html
}
