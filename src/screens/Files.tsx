import { useMemo, useState } from 'react'
import { Search, ChevronLeft, MoreVertical, LayoutGrid } from 'lucide-react'
import { Header, KindIcon, Banner, FileName } from '../components/ui'
import { categories, recent, folders, storage, formatSize, formatDate, type Kind, type FileItem } from '../data/demo'
import { isNative } from '../platform'

type View = { type: 'home' } | { type: 'category'; kind: Kind; label: string } | { type: 'folder'; name: string }

export default function Files({ toast, view, setView }: {
  toast: (m: string) => void
  view: View
  setView: (v: View) => void
}) {
  const [q, setQ] = useState('')
  const found = useMemo(
    () => (q.trim() ? [...recent, ...folders].filter(f => f.name.toLowerCase().includes(q.trim().toLowerCase())) : []),
    [q],
  )

  if (view.type === 'category') {
    const items = recent.filter(f => f.kind === view.kind)
    return (
      <>
        <Header title={view.label} onBack={() => setView({ type: 'home' })} />
        <div className="screen">
          <FileList items={items} onOpen={f => toast(`فتح «${f.name}» هيشتغل في خطوة متصفح الملفات`)} />
          {items.length === 0 && <div className="empty">مفيش ملفات تجريبية من النوع ده</div>}
        </div>
      </>
    )
  }

  if (view.type === 'folder') {
    return (
      <>
        <Header title={view.name} onBack={() => setView({ type: 'home' })} />
        <div className="screen">
          <FileList items={recent.filter(f => f.path.startsWith(view.name))} onOpen={f => toast(`«${f.name}»`)} />
        </div>
      </>
    )
  }

  const usedPct = (storage.used / storage.total) * 100

  return (
    <>
      <Header
        title="حمّل"
        actions={<button className="icon-btn" aria-label="عرض"><LayoutGrid size={20} /></button>}
      />
      <div className="screen">
        {!isNative && <Banner>دي نسخة الويب للتجربة، والملفات اللي فيها أمثلة. ملفات الموبايل الحقيقية هتظهر في الـ APK.</Banner>}

        <label className="search">
          <Search size={18} />
          <input value={q} onChange={e => setQ(e.target.value)} placeholder="دوّر في كل الملفات…" />
        </label>

        {q.trim() ? (
          found.length ? <FileList items={found} onOpen={f => toast(`«${f.name}»`)} /> : <div className="empty">مفيش نتايج لـ «{q}»</div>
        ) : (
          <>
            <div className="storage">
              <div className="label">مساحة الموبايل</div>
              <div className="big">
                {formatSize(storage.total - storage.used)} <span>فاضية من {formatSize(storage.total)}</span>
              </div>
              <div className="bar"><div style={{ width: `${usedPct}%` }} /></div>
            </div>

            <div className="grid">
              {categories.map(c => (
                <button key={c.kind} className="cat" onClick={() => setView({ type: 'category', kind: c.kind, label: c.label })}>
                  <KindIcon kind={c.kind} />
                  <div className="name">{c.label}</div>
                  <div className="sub">{c.count.toLocaleString('en')} · {formatSize(c.size)}</div>
                </button>
              ))}
            </div>

            <div className="section-title">
              <h2>آخر الملفات</h2>
              <button onClick={() => toast('القائمة الكاملة هتيجي مع متصفح الملفات')}>الكل</button>
            </div>
            <FileList items={recent.slice(0, 5)} onOpen={f => toast(`«${f.name}»`)} />

            <div className="section-title"><h2>الفولدرات</h2></div>
            <div className="list">
              {folders.map(f => (
                <button key={f.name} className="row" onClick={() => setView({ type: 'folder', name: f.name })}>
                  <KindIcon kind="folder" />
                  <div className="main"><div className="title">{f.name}</div></div>
                  <ChevronLeft size={18} className="end" />
                </button>
              ))}
            </div>
          </>
        )}
      </div>
    </>
  )
}

function FileList({ items, onOpen }: { items: FileItem[]; onOpen: (f: FileItem) => void }) {
  if (!items.length) return null
  return (
    <div className="list">
      {items.map(f => (
        <button key={f.name} className="row" onClick={() => onOpen(f)}>
          <KindIcon kind={f.kind} />
          <div className="main">
            <div className="title"><FileName name={f.name} /></div>
            <div className="meta">
              {f.kind === 'folder' ? 'فولدر' : `${formatSize(f.size)} · ${formatDate(f.modified)}`}
            </div>
          </div>
          <MoreVertical size={18} className="end" />
        </button>
      ))}
    </div>
  )
}

export type { View as FilesView }
