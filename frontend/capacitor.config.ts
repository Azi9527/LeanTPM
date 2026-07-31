import type { CapacitorConfig } from '@capacitor/cli'

const allowDebugLanHttp = process.env.LEANTPM_ANDROID_ALLOW_HTTP === 'true'

const config: CapacitorConfig = {
  appId: 'com.leantpm.mobile',
  appName: 'LeanTPM',
  webDir: 'dist',
  server: {
    androidScheme: 'https',
  },
  android: {
    allowMixedContent: allowDebugLanHttp,
    captureInput: false,
    webContentsDebuggingEnabled: false,
  },
}

export default config
