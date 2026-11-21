import type { ReactNode } from 'react'
import {
  Image, Film, Music, FileText, Package, Archive, Folder, Info, ChevronRight,
  type LucideIcon,
} from 'lucide-react'
import type { Kind } from '../data/demo'

export const kindIcon: Record<Kind, LucideIcon> = {
  image: Image, video: Film, audio: Music, doc: FileText, apk: Package, archive: Archive, folder: Folder,
}

export function KindIcon({ kind, size = 20 }: { kind: Kind; size?: number }) {
  const Icon = kindIcon[kind]
  return (
    <div className={`ico k-${kind}`}>
      <Icon size={size} strokeWidth={1.8} />
    </div>
  )
}

export function Header({ title, onBack, actions }: { title: string; onBack?: () => void; actions?: ReactNode }) {
  return (
    <header className="top">
      {onBack && (
        <button className="back" onClick={onBack} aria-label="رجوع">
          <ChevronRight size={20} />
        </button>
      )}
      <h1>{title}</h1>
      {actions}
    </header>
  )
}

export function Banner({ children }: { children: ReactNode }) {
  return (
    <div className="banner">
      <Info size={16} />
      <div>{children}</div>
    </div>
  )
}

/** Keeps ".pdf" on the correct side of Arabic file names */
export function FileName({ name }: { name: string }) {
  const dot = name.lastIndexOf('.')
  if (dot <= 0) return <bdi>{name}</bdi>
  return (
    <bdi>
      {name.slice(0, dot)}.<bdi dir="ltr" className="ext">{name.slice(dot + 1)}</bdi>
    </bdi>
  )
}
