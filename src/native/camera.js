import { registerPlugin } from '@capacitor/core'

export const VibeCameraNative = registerPlugin('VibeCamera')

export const Camera = {
  startPreview(options = {}) {
    return VibeCameraNative.startPreview(options)
  },

  stopPreview() {
    return VibeCameraNative.stopPreview()
  },

  capturePhoto(options = {}) {
    return VibeCameraNative.capturePhoto(options)
  },

  switchCamera() {
    return VibeCameraNative.switchCamera()
  },

  getCameraState() {
    return VibeCameraNative.getCameraState()
  },

  setTorch(enabled) {
    return VibeCameraNative.setTorch({ enabled })
  },

  setFlashMode(mode) {
    return VibeCameraNative.setFlashMode({ mode })
  },

  getCapabilities() {
    return VibeCameraNative.getCapabilities()
  },

  getCaptureIdentity() {
    return VibeCameraNative.getCaptureIdentity()
  },

  startRecording(options = {}) {
    return VibeCameraNative.startRecording({
      withAudio: options.withAudio ?? true,
      captureSessionId: options.captureSessionId ?? null,
      nonce: options.nonce ?? null
    })
  },

  stopRecording() {
    return VibeCameraNative.stopRecording()
  },

  deleteCapture(path) {
    return VibeCameraNative.deleteCapture({ path })
  },

  clearCache() {
    return VibeCameraNative.clearCache()
  },

  addVideoRecordingFinishedListener(callback) {
    return VibeCameraNative.addListener('videoRecordingFinished', callback)
  },

  addVideoRecordingErrorListener(callback) {
    return VibeCameraNative.addListener('videoRecordingError', callback)
  }
}
