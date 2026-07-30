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
