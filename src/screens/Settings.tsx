import { Moon, FolderDown, EyeOff, Info, Smartphone, Globe } from 'lucide-react'
import { Header } from '../components/ui'
import { isNative } from '../platform'

export type Theme = 'dark' | 'light'

export default function Settings({ theme, setTheme, showHidden, setShowHidden }: {
  theme: Theme
  setTheme: (t: Theme) => void
  showHidden: boolean
  setShowHidden: (v: boolean) => void
}) {
  return (
    <>
      <Header title="الإعدادات" />
      <div className="screen">
        <div className="group-label">الشكل</div>
        <div className="list">
          <Toggle icon={<Moon size={20} />} title="الوضع الداكن" on={theme === 'dark'} onChange={v => setTheme(v ? 'dark' : 'light')} />
        </div>

        <div className="group-label">الملفات</div>
        <div className="list">
          <Toggle icon={<EyeOff size={20} />} title="إظهار الملفات المخفية" sub="الملفات اللي اسمها بيبدأ بنقطة" on={showHidden} onChange={setShowHidden} />
          <div className="row">
            <div className="ico k-folder"><FolderDown size={20} /></div>
            <div className="main">
              <div className="title">مكان التحميل</div>
              <div className="meta" dir="ltr" style={{ textAlign: 'right' }}>Download/Hammel</div>
            </div>
          </div>
        </div>

        <div className="group-label">عن التطبيق</div>
        <div className="list">
          <div className="row">
            <div className="ico k-doc"><Info size={20} /></div>
            <div className="main">
              <div className="title">حمّل · نسخة 2.0</div>
              <div className="meta">المرحلة الأولى · الخطوة 1 من 4 (الواجهة)</div>
            </div>
          </div>
          <div className="row">
            <div className="ico k-apk">{isNative ? <Smartphone size={20} /> : <Globe size={20} />}</div>
            <div className="main">
              <div className="title">{isNative ? 'شغّال على الموبايل' : 'شغّال في المتصفح'}</div>
              <div className="meta">{isNative ? 'نسخة الـ APK' : 'نسخة التجربة، الملفات فيها أمثلة'}</div>
            </div>
          </div>
        </div>
      </div>
    </>
  )
}

function Toggle({ icon, title, sub, on, onChange }: {
  icon: React.ReactNode; title: string; sub?: string; on: boolean; onChange: (v: boolean) => void
}) {
  return (
    <button className="row" onClick={() => onChange(!on)} role="switch" aria-checked={on}>
      <div className="ico k-doc">{icon}</div>
      <div className="main">
        <div className="title">{title}</div>
        {sub && <div className="meta">{sub}</div>}
      </div>
      <span className={`switch ${on ? 'on' : ''}`} />
    </button>
  )
}
