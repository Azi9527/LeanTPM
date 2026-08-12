export interface InspectionItemPhotoRule {
  photoRequired: boolean
  photoMinCount: number
}

function normalizedMinimum(value: number) {
  return Number.isFinite(value) ? Math.max(0, Math.trunc(value)) : 0
}

export function afterPhotoRequiredChange(
  photoRequired: boolean,
  photoMinCount: number,
): InspectionItemPhotoRule {
  const minimum = normalizedMinimum(photoMinCount)
  return {
    photoRequired,
    photoMinCount: photoRequired ? Math.max(1, minimum) : 0,
  }
}

export function afterPhotoMinimumChange(
  _photoRequired: boolean,
  photoMinCount: number,
): InspectionItemPhotoRule {
  const minimum = normalizedMinimum(photoMinCount)
  return {
    photoRequired: minimum > 0,
    photoMinCount: minimum,
  }
}
