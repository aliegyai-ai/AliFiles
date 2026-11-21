import { Capacitor } from '@capacitor/core'

/** true inside the APK, false in a normal browser */
export const isNative = Capacitor.isNativePlatform()
