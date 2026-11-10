import { useEffect, useState } from 'react'
import './App.css'

type Format = {
  formatId: string
  ext: string
  vcodec: string | null
  acodec: string | null
  width: number | null
  height: number | null
  quality: string
  bitrate: number
  size: string
  url: string
  container: string
}

type ParseResult = {
  title: string
  id: string
  uploader: string
  duration: number
  thumbnail: string
  description: string
  webpageUrl: string
  videos: Format[]
  audios: Format[]
  images: Format[]
}

type Task = {
  taskId: string
  url: string
  title: string
  savePath: string
  status: 'running' | 'done' | 'error'
  progress: number
  speed: string
  eta: string
  filename: string
  error: string
}

const API = ''
const VERSION = 'v1.1.0'

function fmtDuration(s: number): string {
  if (!s) return ''
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = Math.floor(s % 60)
  return h > 0
    ? `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
    : `${m}:${String(sec).padStart(2, '0')}`
}

function proxyUrl(url: string, referer: string): string {
  return `/api/proxy?url=${encodeURIComponent(url)}&referer=${encodeURIComponent(referer)}`
}

const Icon = {
  home: (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 10.5 12 3l9 7.5" /><path d="M5 9.5V21h14V9.5" />
    </svg>
  ),
  user: (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="8" r="4" /><path d="M4 21c0-4 3.5-6.5 8-6.5s8 2.5 8 6.5" />
    </svg>
  ),
  folder: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 6a2 2 0 0 1 2-2h4l2 3h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6Z" />
    </svg>
  ),
  moon: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />
    </svg>
  ),
  sun: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </svg>
  ),
  close: (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  ),
  trash: (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
    </svg>
  ),
  cancel: (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="9" /><path d="M5.6 5.6l12.8 12.8" />
    </svg>
  ),
}

function App() {
  const [url, setUrl] = useState('')
  const [parsing, setParsing] = useState(false)
  const [result, setResult] = useState<ParseResult | null>(null)
  const [error, setError] = useState('')
  const [tab, setTab] = useState<'videos' | 'audios' | 'images'>('videos')
  const [tasks, setTasks] = useState<Task[]>([])
  const [preview, setPreview] = useState<{ kind: string; url: string; title: string } | null>(null)
  const [dark, setDark] = useState(() => localStorage.getItem('theme') !== 'light')
  const [page, setPage] = useState<'home' | 'me'>('home')
  const [saveDir, setSaveDir] = useState('')

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light')
    localStorage.setItem('theme', dark ? 'dark' : 'light')
  }, [dark])

  useEffect(() => {
    fetch(`${API}/api/config`).then(r => r.json()).then(d => setSaveDir(d.saveDir || '')).catch(() => {})
  }, [])

  const refreshTasks = () => {
    fetch(`${API}/api/tasks`).then(r => r.json()).then(setTasks).catch(() => {})
  }

  useEffect(() => {
    refreshTasks()
    const t = setInterval(refreshTasks, 1000)
    return () => clearInterval(t)
  }, [])

  async function onParse() {
    setError('')
    setResult(null)
    setParsing(true)
    try {
      const r = await fetch(`${API}/api/parse`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url }),
      })
      const data = await r.json()
      if (!r.ok) throw new Error(data.detail || '解析失败')
      setResult(data)
    } catch (e: any) {
      setError(e.message)
    } finally {
      setParsing(false)
    }
  }

  const [toast, setToast] = useState('')
  useEffect(() => {
    if (!toast) return
    const t = setTimeout(() => setToast(''), 2500)
    return () => clearTimeout(t)
  }, [toast])

  function onDownload(fmt: Format, kind: 'video' | 'audio' | 'image') {
    if (!result) return
    const params = new URLSearchParams({
      kind,
      url: result.webpageUrl || url,
      title: result.title,
      formatId: fmt.formatId,
    })
    if (kind === 'image') params.set('directUrl', fmt.url)
    const a = document.createElement('a')
    a.href = `/api/download-stream?${params.toString()}`
    a.download = ''
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    setToast('已开始下载，前往「我的」查看进度')
  }

  function onPreview(fmt: Format, kind: string) {
    if (!result) return
    if (kind === 'images') {
      setPreview({ kind, url: proxyUrl(fmt.url, result.webpageUrl || url), title: result.title })
      return
    }
    // 视频/音频：走后端 yt-dlp 流式预览（自动合并音视频轨）
    const params = new URLSearchParams({
      kind: kind === 'audios' ? 'audio' : 'video',
      url: result.webpageUrl || url,
      title: result.title,
      formatId: fmt.formatId,
      preview: '1',
    })
    setPreview({ kind, url: `/api/download-stream?${params.toString()}`, title: result.title })
  }

  function onCancelTask(taskId: string) {
    fetch(`/api/cancel?taskId=${taskId}`, { method: 'POST' }).then(refreshTasks)
  }

  function onDeleteTask(taskId: string) {
    fetch(`/api/tasks/${taskId}`, { method: 'DELETE' }).then(refreshTasks)
  }

  const lists: { key: typeof tab; label: string; data: Format[] }[] = [
    { key: 'videos', label: `视频 ${result?.videos.length ? `(${result.videos.length})` : ''}`, data: result?.videos || [] },
    { key: 'audios', label: `音频 ${result?.audios.length ? `(${result.audios.length})` : ''}`, data: result?.audios || [] },
    { key: 'images', label: `图片 ${result?.images.length ? `(${result.images.length})` : ''}`, data: result?.images || [] },
  ]

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand" onClick={() => setPage('home')} style={{ cursor: 'pointer' }}>
          <span className="brand-mark">V</span>
          <span className="brand-name">VSP 解析</span>
        </div>
        <button className="icon-btn desktop-only" onClick={() => setPage('me')} title="我的">
          {Icon.user}
        </button>
      </header>

      {page === 'home' && (
        <main className="main">
          <section className="search">
            <input
              className="url-input"
              placeholder="粘贴视频 / 图片 / 音频页面链接，回车解析"
              value={url}
              onChange={e => setUrl(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter' && url.trim()) onParse() }}
            />
            <button className="primary-btn" onClick={onParse} disabled={parsing || !url.trim()}>
              {parsing ? '解析中…' : '解析'}
            </button>
          </section>

          {error && <section className="error">{error}</section>}

          {result && (
            <section className="result">
              <div className="meta">
                {result.thumbnail && (
                  <div className="thumb">
                    <img src={proxyUrl(result.thumbnail, result.webpageUrl)} alt="" />
                  </div>
                )}
                <div className="meta-text">
                  <h1 className="title">{result.title}</h1>
                  <div className="meta-row">
                    {result.uploader && <span className="chip">{result.uploader}</span>}
                    {result.duration > 0 && <span className="chip">{fmtDuration(result.duration)}</span>}
                  </div>
                  {result.description && <p className="desc">{result.description}</p>}
                </div>
              </div>

              <div className="tabs">
                {lists.map(t => (
                  <button key={t.key} className={`tab ${tab === t.key ? 'active' : ''}`} onClick={() => setTab(t.key)}>
                    {t.label}
                  </button>
                ))}
              </div>

              <div className="format-list">
                {lists.find(l => l.key === tab)!.data.length === 0 && (
                  <div className="empty">该分类下无可用格式</div>
                )}
                {lists.find(l => l.key === tab)!.data.map((f, i) => (
                  <div className="format-row" key={f.formatId + i}>
                    <div className="f-quality">{f.quality}</div>
                    <div className="f-meta">
                      <span className="f-ext">{f.container}</span>
                      {f.vcodec && <span className="f-codec">{f.vcodec.split('.')[0]}</span>}
                      {f.acodec && <span className="f-codec">{f.acodec.split('.')[0]}</span>}
                      {f.bitrate > 0 && <span className="f-codec">{Math.round(f.bitrate)}k</span>}
                    </div>
                    <div className="f-size">{f.size}</div>
                    <div className="f-actions">
                      <button className="ghost-btn" onClick={() => onPreview(f, tab)}>预览</button>
                      <button className="primary-btn small" onClick={() => onDownload(f, tab === 'videos' ? 'video' : tab === 'audios' ? 'audio' : 'image')}>
                        下载
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            </section>
          )}
        </main>
      )}

      {page === 'me' && (
        <main className="main me-page">
          <section className="me-head">
            <div className="me-avatar">{Icon.user}</div>
            <div>
              <div className="me-name">本地用户</div>
              <div className="me-subtitle">VSP 解析 {VERSION}</div>
            </div>
          </section>

          <section className="me-section">
            <h2 className="me-title">下载队列 <span className="me-count">{tasks.length}</span></h2>
            {tasks.length === 0 && <div className="empty">暂无下载任务</div>}
            {tasks.map(t => (
              <div className="task" key={t.taskId}>
                <div className="task-info">
                  <span className="task-title" title={t.title}>{t.title}</span>
                  <span className="task-status">
                    {t.status === 'running' && `${t.progress.toFixed(0)}% ${t.speed}`}
                    {t.status === 'done' && '完成'}
                    {t.status === 'error' && '失败'}
                  </span>
                </div>
                <div className="task-bar-row">
                  <div className="bar">
                    <div className={`bar-fill ${t.status}`} style={{ width: `${t.status === 'done' ? 100 : t.progress}%` }} />
                  </div>
                  {t.status === 'running' && (
                    <button className="task-action" onClick={() => onCancelTask(t.taskId)} title="取消">
                      {Icon.cancel}
                    </button>
                  )}
                  {(t.status === 'done' || t.status === 'error') && (
                    <button className="task-action" onClick={() => onDeleteTask(t.taskId)} title="删除">
                      {Icon.trash}
                    </button>
                  )}
                </div>
              </div>
            ))}
          </section>

          <section className="me-section">
            <h2 className="me-title">设置</h2>
            <div className="setting-row" onClick={() => setDark(d => !d)}>
              <span className="setting-icon">{dark ? Icon.moon : Icon.sun}</span>
              <span className="setting-label">外观</span>
              <span className="setting-value">{dark ? '深色' : '浅色'}</span>
            </div>
            <div className="setting-row" onClick={() => fetch('/api/open-folder')}>
              <span className="setting-icon">{Icon.folder}</span>
              <span className="setting-label">下载目录</span>
              <span className="setting-value path">{saveDir || '打开'}</span>
            </div>
          </section>

          <div className="me-footer">VSP 解析 {VERSION} · 基于 yt-dlp</div>
        </main>
      )}

      <nav className="mobile-tabbar">
        <button className={`tab-item ${page === 'home' ? 'active' : ''}`} onClick={() => setPage('home')}>
          {Icon.home}<span>首页</span>
        </button>
        <button className={`tab-item ${page === 'me' ? 'active' : ''}`} onClick={() => setPage('me')}>
          {Icon.user}<span>我的</span>
        </button>
      </nav>

      {toast && <div className="toast">{toast}</div>}

      {preview && (
        <div className="modal" onClick={() => setPreview(null)}>
          <div className="modal-body" onClick={e => e.stopPropagation()}>
            <div className="modal-head">
              <span className="modal-title">{preview.title}</span>
              <button className="icon-btn modal-close" onClick={() => setPreview(null)} title="关闭">
                {Icon.close}
              </button>
            </div>
            <div className="modal-content">
              {preview.kind === 'videos' && <video src={preview.url} controls autoPlay />}
              {preview.kind === 'audios' && <audio src={preview.url} controls autoPlay />}
              {preview.kind === 'images' && <img src={preview.url} alt="" />}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default App
