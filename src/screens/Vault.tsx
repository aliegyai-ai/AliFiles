import { useState } from 'react'
import { Lock, Fingerprint, Delete } from 'lucide-react'
import { Header } from '../components/ui'

const PIN_LENGTH = 4

// UI only for now — real encryption (AES-256) and the PIN check come in step 2.
export default function Vault({ toast }: { toast: (m: string) => void }) {
  const [pin, setPin] = useState('')
  const [shake, setShake] = useState(false)

  const press = (n: string) => {
    if (pin.length >= PIN_LENGTH) return
    const next = pin + n
    setPin(next)
    if (next.length === PIN_LENGTH) {
      setTimeout(() => {
        setShake(true)
        toast('الخزنة هتتفعّل في الخطوة التانية')
        setTimeout(() => { setShake(false); setPin('') }, 400)
      }, 150)
    }
  }

  return (
    <>
      <Header title="الخزنة" />
      <div className="screen">
        <div className="vault">
          <div className="lock"><Lock size={38} strokeWidth={1.6} /></div>
          <h2>الخزنة مقفولة</h2>
          <p>اكتب الرمز السري أو استخدم البصمة</p>

          <div className={`dots ${shake ? 'shake' : ''}`}>
            {Array.from({ length: PIN_LENGTH }, (_, i) => <span key={i} className={i < pin.length ? 'on' : ''} />)}
          </div>

          <div className="pad">
            {['1', '2', '3', '4', '5', '6', '7', '8', '9'].map(n => (
              <button key={n} onClick={() => press(n)}>{n}</button>
            ))}
            <button className="plain" aria-label="بصمة" onClick={() => toast('البصمة هتشتغل في الـ APK')}>
              <Fingerprint size={28} />
            </button>
            <button onClick={() => press('0')}>0</button>
            <button className="plain" aria-label="مسح" onClick={() => setPin(p => p.slice(0, -1))}>
              <Delete size={24} />
            </button>
          </div>
        </div>
      </div>
    </>
  )
}
