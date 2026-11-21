import { useCallback, useEffect, useRef, useState } from 'react'
import { FolderOpen, Download, Lock, Settings as SettingsIcon } from 'lucide-react'
import { App as CapApp } from '@capacitor/app'
import Files, { type FilesView } from './screens/Files'
import Downloads from './screens/Downloads'
import Vault from './screens/Vault'
import Settings, { type Theme } from './screens/Settings'
import { downloads } from './data/demo'
import { isNative } from './platform'

type Tab = 'files' | 'downloads' | 'vault' | 'settings'

const tabs: { id: Tab; label: string; icon: typeof FolderOpen }[] = [
  { id: 'files', label: 'الملفات', icon: FolderOpen },
  { id: 'downloads', label: 'التحميلات', icon: Download },
  { id: 'vault', label: 'الخزنة', icon: Lock },
  { id: 'settings', label: 'الإعدادات', icon: SettingsIcon },
]

function load<T>(key: string, fallback: T): T {
  try {
    const v = localStorage.getItem(key)
    return v === null ? fallback : (JSON.parse(v) as T)
  } catch {
    return fallback
  }
}
function save(key: string, value: unknown) {
  try { localStorage.setItem(key, JSON.stringify(value)) } catch { /* storage unavailable */ }
}

export default function App() {
  const [tab, setTab] = useState<Tab>('files')
  const [filesView, setFilesView] = useState<FilesView>({ type: 'home' })
  const [theme, setTheme] = useState<Theme>(() => load('theme', 'dark'))
  const [showHidden, setShowHidden] = useState(() => load('showHidden', false))
  const [msg, setMsg] = useState<string | null>(null)
  const timer = useRef<number>(undefined)

  const toast = useCallback((m: string) => {
    setMsg(m)
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setMsg(null), 2400)
  }, [])

  useEffect(() => {
    document.documentElement.dataset.theme = theme
    document.querySelector('meta[name=theme-color]')?.setAttribute('content', theme === 'dark' ? '#0e0d0b' : '#f6f1e7')
    save('theme', theme)
  }, [theme])
  useEffect(() => save('showHidden', showHidden), [showHidden])

  // Android back button: leave a sub-page first, then go to the files tab, then exit
  const state = useRef({ tab, filesView })
  state.current = { tab, filesView }
  useEffect(() => {
    if (!isNative) return
    const sub = CapApp.addListener('backButton', () => {
      const s = state.current
      if (s.tab === 'files' && s.filesView.type !== 'home') setFilesView({ type: 'home' })
      else if (s.tab !== 'files') setTab('files')
      else CapApp.exitApp()
    })
    return () => { sub.then(h => h.remove()) }
  }, [])

  const running = downloads.filter(d => d.state === 'running').length

  return (
    <div className="app">
      {tab === 'files' && <Files toast={toast} view={filesView} setView={setFilesView} />}
      {tab === 'downloads' && <Downloads toast={toast} />}
      {tab === 'vault' && <Vault toast={toast} />}
      {tab === 'settings' && <Settings theme={theme} setTheme={setTheme} showHidden={showHidden} setShowHidden={setShowHidden} />}

      <nav className="nav">
        {tabs.map(t => (
          <button key={t.id} className={tab === t.id ? 'on' : ''} onClick={() => {
            if (t.id === 'files' && tab === 'files') setFilesView({ type: 'home' })
            setTab(t.id)
          }}>
            <t.icon size={22} strokeWidth={tab === t.id ? 2.2 : 1.8} />
            {t.label}
            {t.id === 'downloads' && running > 0 && <span className="badge">{running}</span>}
          </button>
        ))}
      </nav>

      {msg && <div className="toast" role="status">{msg}</div>}
    </div>
  )
}
