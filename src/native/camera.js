import { registerPlugin } from '@capacitor/core'

export const VibeCameraNative = registerPlugin('VibeCamera')

export const Camera = {
  startPreview(options = {}) {
    return VibeCameraNative.startPreview(options)
  },

  stopPreview() {
    return VibeCameraNative.stopPreview(options)
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

  startRecording(options = {}) {
    return VibeCameraNative.startRecording({
      withAudio: options.withAudio ?? false
    })
  },

  stopRecording() {
    return VibeCameraNative.stopRecording()
  }
}
