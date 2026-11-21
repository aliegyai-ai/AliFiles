// Sample data shown in the web version (a browser can't read the phone's storage).
// In the APK these get replaced by the real files in step 3.

export type Kind = 'image' | 'video' | 'audio' | 'doc' | 'apk' | 'archive' | 'folder'

export interface FileItem {
  name: string
  kind: Kind
  size: number // bytes
  modified: string // ISO date
  path: string
}

export const storage = { total: 128e9, used: 83.4e9 }

export const categories: { kind: Kind; label: string; count: number; size: number }[] = [
  { kind: 'image', label: 'الصور', count: 4210, size: 18.2e9 },
  { kind: 'video', label: 'الفيديو', count: 312, size: 29.6e9 },
  { kind: 'audio', label: 'الصوت', count: 846, size: 4.1e9 },
  { kind: 'doc', label: 'المستندات', count: 538, size: 2.3e9 },
  { kind: 'apk', label: 'التطبيقات', count: 27, size: 1.9e9 },
  { kind: 'archive', label: 'المضغوط', count: 41, size: 3.4e9 },
]

const d = (daysAgo: number, h = 10) => {
  const t = new Date()
  t.setDate(t.getDate() - daysAgo)
  t.setHours(h, 15, 0, 0)
  return t.toISOString()
}

export const recent: FileItem[] = [
  { name: 'فاتورة نوفمبر.pdf', kind: 'doc', size: 2.4e6, modified: d(0, 9), path: 'Documents' },
  { name: 'جدول المصروفات.xlsx', kind: 'doc', size: 860e3, modified: d(0, 8), path: 'Documents' },
  { name: 'VID_20261120_171044.mp4', kind: 'video', size: 148e6, modified: d(1, 17), path: 'DCIM/Camera' },
  { name: 'IMG_20261120_142233.jpg', kind: 'image', size: 4.2e6, modified: d(1, 14), path: 'DCIM/Camera' },
  { name: 'تسجيل صوتي.mp3', kind: 'audio', size: 38e6, modified: d(2, 7), path: 'Download/AliFiles' },
  { name: 'دليل الاستخدام.pdf', kind: 'doc', size: 5.1e6, modified: d(4, 21), path: 'Download' },
  { name: 'صور.zip', kind: 'archive', size: 212e6, modified: d(6, 12), path: 'Download' },
]

export const folders: FileItem[] = [
  { name: 'DCIM', kind: 'folder', size: 0, modified: d(1), path: '' },
  { name: 'Download', kind: 'folder', size: 0, modified: d(0), path: '' },
  { name: 'Documents', kind: 'folder', size: 0, modified: d(0), path: '' },
  { name: 'Music', kind: 'folder', size: 0, modified: d(9), path: '' },
  { name: 'Pictures', kind: 'folder', size: 0, modified: d(3), path: '' },
  { name: 'WhatsApp', kind: 'folder', size: 0, modified: d(0), path: '' },
]

export interface DownloadItem {
  title: string
  site: 'youtube' | 'facebook' | 'tiktok' | 'web'
  type: 'video' | 'audio'
  size: number
  progress: number // 0..1
  speed?: number // bytes/s
  state: 'running' | 'paused' | 'done'
}

export const downloads: DownloadItem[] = [
  { title: 'فيديو من يوتيوب', site: 'youtube', type: 'video', size: 96e6, progress: 0.62, speed: 3.1e6, state: 'running' },
  { title: 'فيديو من فيسبوك', site: 'facebook', type: 'video', size: 54e6, progress: 0.18, state: 'paused' },
  { title: 'محاضرة (صوت بس)', site: 'youtube', type: 'audio', size: 41e6, progress: 1, state: 'done' },
  { title: 'tiktok_7431.mp4', site: 'tiktok', type: 'video', size: 12e6, progress: 1, state: 'done' },
]

export function formatSize(bytes: number): string {
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} جيجا`
  if (bytes >= 1e6) return `${(bytes / 1e6).toFixed(bytes >= 1e8 ? 0 : 1)} ميجا`
  if (bytes >= 1e3) return `${Math.round(bytes / 1e3)} كيلو`
  return `${bytes} بايت`
}

export function formatDate(iso: string): string {
  const t = new Date(iso)
  const days = Math.floor((Date.now() - t.getTime()) / 864e5)
  const time = t.toLocaleTimeString('ar-EG-u-nu-latn', { hour: 'numeric', minute: '2-digit' })
  if (days <= 0) return `النهارده ${time}`
  if (days === 1) return `امبارح ${time}`
  return t.toLocaleDateString('ar-EG-u-nu-latn', { day: 'numeric', month: 'long' })
}
