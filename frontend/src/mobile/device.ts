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
  brandName: string
  faultLocationText: string
  photoCompressionQuality: number
}

export interface PhotoEvidenceMetadata {
  workflowType: 'INSPECTION' | 'MAINTENANCE'
  taskId: number
  taskItemId: number
  capturedDeviceTime: string
  serverReferenceTime: string
  deviceClockOffsetSeconds: number
  faultLocationText: string
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
  compressionQuality = 82,
): Promise<File> {
  const photo = await Camera.getPhoto({
    quality: Math.max(40, Math.min(95, compressionQuality)),
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
  const originalFile = await capturePhotoFile(
    filenamePrefix, maxSizeMb, context.photoCompressionQuality,
  )
  const capturedAt = new Date()
  const serverReference = new Date(context.serverTime)
  const offsetSeconds = Number.isNaN(serverReference.getTime())
    ? 0
    : Math.round((capturedAt.getTime() - serverReference.getTime()) / 1000)
  const calibratedTime = Number.isNaN(serverReference.getTime())
    ? capturedAt : serverReference
  const lines = [
    context.brandName,
    `${context.equipmentName} (${context.equipmentCode})`,
    `${context.taskCode} · ${context.itemName}`,
    `服务时间 ${formatWatermarkTime(calibratedTime)}  执行人 ${context.executorName}`,
    `故障位置/部位 ${context.faultLocationText}`,
  ]
  const watermarkedBlob = await renderWatermark(
    originalFile, lines, context.photoCompressionQuality,
  )
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
      faultLocationText: context.faultLocationText,
      watermarkText: lines.join('\n'),
    },
  }
}

async function renderWatermark(
  file: File,
  lines: string[],
  compressionQuality: number,
): Promise<Blob> {
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
    Math.max(.4, Math.min(.95, compressionQuality / 100)),
  ))
}

function formatWatermarkTime(value: Date): string {
  const two = (number: number) => String(number).padStart(2, '0')
  return `${value.getFullYear()}-${two(value.getMonth() + 1)}-${two(value.getDate())}`
    + ` ${two(value.getHours())}:${two(value.getMinutes())}:${two(value.getSeconds())}`
}
