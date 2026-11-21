import { useState } from 'react'
import { Link2, ClipboardPaste, Pause, Play, Check, Film, Headphones } from 'lucide-react'
import { Header } from '../components/ui'
import { downloads, formatSize } from '../data/demo'

const siteName = { youtube: 'يوتيوب', facebook: 'فيسبوك', tiktok: 'تيك توك', web: 'ويب' }

export default function Downloads({ toast }: { toast: (m: string) => void }) {
  const [url, setUrl] = useState('')

  const paste = async () => {
    try {
      setUrl(await navigator.clipboard.readText())
    } catch {
      toast('مقدرتش أقرا الحافظة، الصق اللينك بإيدك')
    }
  }

  return (
    <>
      <Header title="التحميلات" />
      <div className="screen">
        <div className="paste">
          <label className="search">
            <Link2 size={18} />
            <input value={url} onChange={e => setUrl(e.target.value)} placeholder="الصق لينك يوتيوب أو فيسبوك أو تيك توك" dir="auto" />
          </label>
          <button className="btn ghost" onClick={paste} aria-label="لصق"><ClipboardPaste size={18} /></button>
        </div>
        <p className="hint">التحميل الفعلي هيشتغل في الخطوة الرابعة (المحمّل). دلوقتي دي شكل الشاشة بس.</p>
        <button className="btn" style={{ width: '100%', marginBottom: 22 }} disabled={!url.trim()}
          onClick={() => toast('المحمّل لسه في الخطوة الرابعة')}>
          تحليل اللينك
        </button>

        <div className="section-title"><h2>شغّال</h2></div>
        <div className="list">
          {downloads.filter(d => d.state !== 'done').map(d => <DownloadRow key={d.title} d={d} />)}
        </div>

        <div className="section-title"><h2>خلصت</h2></div>
        <div className="list">
          {downloads.filter(d => d.state === 'done').map(d => <DownloadRow key={d.title} d={d} />)}
        </div>
      </div>
    </>
  )
}

function DownloadRow({ d }: { d: (typeof downloads)[number] }) {
  const done = d.state === 'done'
  const Icon = d.type === 'audio' ? Headphones : Film
  return (
    <div className="dl">
      <div className="head">
        <div className={`ico ${d.type === 'audio' ? 'k-audio' : 'k-video'}`} style={{ width: 40, height: 40, borderRadius: 11, display: 'grid', placeItems: 'center' }}>
          <Icon size={20} strokeWidth={1.8} />
        </div>
        <div className="main" style={{ flex: 1, minWidth: 0 }}>
          <div className="title" style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', fontWeight: 500 }}>{d.title}</div>
          <div className="meta" style={{ color: 'var(--muted)', fontSize: 12.5 }}>
            {siteName[d.site]} · {d.type === 'audio' ? 'صوت بس' : 'فيديو'} · {formatSize(d.size)}
          </div>
        </div>
        <span className="icon-btn" style={{ color: done ? 'var(--ok)' : 'var(--gold)' }}>
          {done ? <Check size={20} /> : d.state === 'paused' ? <Play size={20} /> : <Pause size={20} />}
        </span>
      </div>
      {!done && (
        <>
          <div className={`bar ${d.state === 'paused' ? 'paused' : ''}`}><div style={{ width: `${d.progress * 100}%` }} /></div>
          <div className="foot">
            <span>{Math.round(d.progress * 100)}% · {formatSize(d.size * d.progress)} من {formatSize(d.size)}</span>
            <span>{d.state === 'paused' ? 'متوقف' : `${formatSize(d.speed ?? 0)}/ث`}</span>
          </div>
        </>
      )}
    </div>
  )
}
