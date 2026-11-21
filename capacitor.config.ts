import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  // new id so the old Kotlin app (com.ali.files) can stay installed until its vault is emptied
  appId: 'com.ali.hammel',
  appName: 'حمّل',
  webDir: 'dist',
  android: { backgroundColor: '#0e0d0b' },
}

export default config
