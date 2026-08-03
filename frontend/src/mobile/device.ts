import {
  CapacitorBarcodeScanner,
  CapacitorBarcodeScannerAndroidScanningLibrary,
  CapacitorBarcodeScannerCameraDirection,
  CapacitorBarcodeScannerScanOrientation,
  CapacitorBarcodeScannerTypeHint,
} from '@capacitor/barcode-scanner'
import {
  Camera,
  CameraResultType,
  CameraSource,
} from '@capacitor/camera'
import { WebPlugin, registerPlugin } from '@capacitor/core'

interface NativeLocation {
  latitude: number
  longitude: number
  accuracy: number
  capturedAt: number
  provider: string
}

interface FieldLocationPlugin {
  getCurrentPosition(options: { timeoutMs: number }): Promise<NativeLocation>
}

class FieldLocationWeb extends WebPlugin implements FieldLocationPlugin {
  getCurrentPosition(options: { timeoutMs: number }): Promise<NativeLocation> {
    return new Promise((resolve, reject) => {
      if (!navigator.geolocation) return reject(new Error('当前设备不支持定位'))
      navigator.geolocation.getCurrentPosition(
        ({ coords, timestamp }) => resolve({
          latitude: coords.latitude,
          longitude: coords.longitude,
          accuracy: coords.accuracy,
          capturedAt: timestamp,
          provider: 'WEB_GEOLOCATION',
        }),
        (error) => reject(new Error(error.message || '获取定位失败')),
        { enableHighAccuracy: true, timeout: options.timeoutMs, maximumAge: 30_000 },
      )
    })
  }
}

const FieldLocation = registerPlugin<FieldLocationPlugin>('FieldLocation', {
  web: () => Promise.resolve(new FieldLocationWeb()),
})

export interface PhotoCaptureContext {
  workflowType: 'INSPECTION' | 'MAINTENANCE'
  taskId: number
  taskItemId: number
  taskCode: string
  equipmentCode: string
  equipmentName: string
  itemName: string
  executorName: string
  serverTime: string
  locationRequired: boolean
}

export interface PhotoEvidenceMetadata {
  workflowType: 'INSPECTION' | 'MAINTENANCE'
  taskId: number
  taskItemId: number
  capturedDeviceTime: string
  serverReferenceTime: string
  deviceClockOffsetSeconds: number
  latitude?: number
  longitude?: number
  locationAccuracyMeters?: number
  locationProvider?: string
  addressText?: string
  watermarkText: string
}

export interface CapturedPhotoEvidence {
  originalFile: File
  watermarkedFile: File
  metadata: PhotoEvidenceMetadata
}

export function extractEquipmentToken(value: string): string | null {
  const normalized = value.trim()
  if (/^[a-fA-F0-9]{64}$/.test(normalized)) return normalized.toLowerCase()
  try {
    const url = new URL(normalized)
    const match = url.pathname.match(/\/m\/e\/([a-fA-F0-9]{64})\/?$/)
    return match?.[1]?.toLowerCase() ?? null
  } catch {
    const match = normalized.match(/\/m\/e\/([a-fA-F0-9]{64})(?:[/?#]|$)/)
    return match?.[1]?.toLowerCase() ?? null
  }
}

export async function scanEquipmentToken(): Promise<string> {
  const result = await CapacitorBarcodeScanner.scanBarcode({
    hint: CapacitorBarcodeScannerTypeHint.QR_CODE,
    cameraDirection: CapacitorBarcodeScannerCameraDirection.BACK,
    scanOrientation: CapacitorBarcodeScannerScanOrientation.ADAPTIVE,
    scanInstructions: '将设备二维码放入取景框',
    scanButton: false,
    cancelButtonAccessibilityLabel: '取消扫码',
    torchButtonOnAccessibilityLabel: '关闭手电筒',
    torchButtonOffAccessibilityLabel: '打开手电筒',
    android: {
      scanningLibrary: CapacitorBarcodeScannerAndroidScanningLibrary.MLKIT,
    },
    web: {
      showCameraSelection: true,
      scannerFPS: 10,
    },
  })
  const token = extractEquipmentToken(result.ScanResult)
  if (!token) throw new Error('未识别到 LeanTPM 设备二维码')
  return token
}

export async function capturePhotoFile(
  filenamePrefix: string,
  maxSizeMb: number,
): Promise<File> {
  const photo = await Camera.getPhoto({
    quality: 82,
    width: 1920,
    height: 1920,
    allowEditing: false,
    resultType: CameraResultType.Uri,
    source: CameraSource.Camera,
    saveToGallery: false,
    correctOrientation: true,
  })
  if (!photo.webPath) throw new Error('相机未返回可上传的照片')
  const blob = await (await fetch(photo.webPath)).blob()
  const limit = Math.max(1, maxSizeMb) * 1024 * 1024
  if (blob.size > limit) {
    throw new Error(`照片超过 ${maxSizeMb} MB，请降低分辨率后重试`)
  }
  const extension = photo.format === 'png' ? 'png' : 'jpeg'
  return new File(
    [blob],
    `${filenamePrefix}-${Date.now()}.${extension}`,
    { type: blob.type || `image/${extension}` },
  )
}

export async function capturePhotoEvidence(
  filenamePrefix: string,
  maxSizeMb: number,
  context: PhotoCaptureContext,
): Promise<CapturedPhotoEvidence> {
  const originalFile = await capturePhotoFile(filenamePrefix, maxSizeMb)
  const capturedAt = new Date()
  let location: NativeLocation | undefined
  try {
    location = await FieldLocation.getCurrentPosition({ timeoutMs: 12_000 })
  } catch (error) {
    if (context.locationRequired) {
      throw new Error(`现场照片需要定位：${error instanceof Error ? error.message : '请授予定位权限'}`)
    }
  }

  const serverReference = new Date(context.serverTime)
  const offsetSeconds = Number.isNaN(serverReference.getTime())
    ? 0
    : Math.round((capturedAt.getTime() - serverReference.getTime()) / 1000)
  const locationLine = location
    ? `定位 ${location.latitude.toFixed(6)}, ${location.longitude.toFixed(6)}  精度 ±${Math.round(location.accuracy)}m`
    : '定位 未获取'
  const lines = [
    `${context.equipmentName} (${context.equipmentCode})`,
    `${context.taskCode} · ${context.itemName}`,
    `拍摄 ${formatWatermarkTime(capturedAt)}  执行人 ${context.executorName}`,
    locationLine,
  ]
  const watermarkedBlob = await renderWatermark(originalFile, lines)
  const limit = Math.max(1, maxSizeMb) * 1024 * 1024
  if (watermarkedBlob.size > limit) {
    throw new Error(`水印照片超过 ${maxSizeMb} MB，请重新拍摄`)
  }
  const watermarkedFile = new File(
    [watermarkedBlob],
    `${filenamePrefix}-${Date.now()}-watermarked.jpeg`,
    { type: 'image/jpeg' },
  )
  return {
    originalFile,
    watermarkedFile,
    metadata: {
      workflowType: context.workflowType,
      taskId: context.taskId,
      taskItemId: context.taskItemId,
      capturedDeviceTime: capturedAt.toISOString(),
      serverReferenceTime: Number.isNaN(serverReference.getTime())
        ? capturedAt.toISOString()
        : serverReference.toISOString(),
      deviceClockOffsetSeconds: offsetSeconds,
      latitude: location?.latitude,
      longitude: location?.longitude,
      locationAccuracyMeters: location?.accuracy,
      locationProvider: location?.provider,
      watermarkText: lines.join('\n'),
    },
  }
}

async function renderWatermark(file: File, lines: string[]): Promise<Blob> {
  const bitmap = await createImageBitmap(file)
  const scale = Math.min(1, 1920 / Math.max(bitmap.width, bitmap.height))
  const width = Math.max(1, Math.round(bitmap.width * scale))
  const height = Math.max(1, Math.round(bitmap.height * scale))
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const drawing = canvas.getContext('2d')
  if (!drawing) throw new Error('当前设备无法生成照片水印')
  drawing.drawImage(bitmap, 0, 0, width, height)
  bitmap.close()
  const fontSize = Math.max(22, Math.round(width / 42))
  const lineHeight = Math.round(fontSize * 1.45)
  const padding = Math.round(fontSize * .8)
  const panelHeight = lineHeight * lines.length + padding * 2
  const top = Math.max(0, height - panelHeight)
  drawing.fillStyle = 'rgba(3, 25, 34, .74)'
  drawing.fillRect(0, top, width, panelHeight)
  drawing.font = `600 ${fontSize}px sans-serif`
  drawing.fillStyle = '#ffffff'
  drawing.textBaseline = 'top'
  lines.forEach((line, index) => drawing.fillText(
    line,
    padding,
    top + padding + index * lineHeight,
    width - padding * 2,
  ))
  return new Promise((resolve, reject) => canvas.toBlob(
    (blob) => blob ? resolve(blob) : reject(new Error('水印照片编码失败')),
    'image/jpeg',
    .84,
  ))
}

function formatWatermarkTime(value: Date): string {
  const two = (number: number) => String(number).padStart(2, '0')
  return `${value.getFullYear()}-${two(value.getMonth() + 1)}-${two(value.getDate())}`
    + ` ${two(value.getHours())}:${two(value.getMinutes())}:${two(value.getSeconds())}`
}
