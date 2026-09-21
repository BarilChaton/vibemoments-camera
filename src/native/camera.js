import { registerPlugin } from '@capacitor/core'

export const VibeCameraNative = registerPlugin('VibeCamera')

export const Camera = {
  // ---------------------------------------------------------------------------
  // Permissions
  // ---------------------------------------------------------------------------

  async checkPermissions() {
    return VibeCameraNative.checkPermissions()
  },

  async requestPermissions() {
    return VibeCameraNative.requestPermissions()
  },

  // ---------------------------------------------------------------------------
  // Capture identity
  // ---------------------------------------------------------------------------

  async getCaptureIdentity() {
    return VibeCameraNative.getCaptureIdentity()
  },

  // ---------------------------------------------------------------------------
  // Preview
  // ---------------------------------------------------------------------------

  async startPreview(options = {}) {
    return VibeCameraNative.startPreview(options)
  },

  async stopPreview() {
    return VibeCameraNative.stopPreview()
  },

  async switchCamera() {
    return VibeCameraNative.switchCamera()
  },

  // ---------------------------------------------------------------------------
  // Photo capture
  // ---------------------------------------------------------------------------

  async capturePhoto(options = {}) {
    return VibeCameraNative.capturePhoto(options)
  },

  // ---------------------------------------------------------------------------
  // Video recording
  // ---------------------------------------------------------------------------

  async startRecording(options = {}) {
    return VibeCameraNative.startRecording(options)
  },

  async stopRecording() {
    return VibeCameraNative.stopRecording()
  },

  async getRecordingState() {
    return VibeCameraNative.getRecordingState()
  },

  // ---------------------------------------------------------------------------
  // Camera capabilities
  // ---------------------------------------------------------------------------

  async getCapabilities() {
    return VibeCameraNative.getCapabilities()
  },

  // ---------------------------------------------------------------------------
  // Torch / flash
  // ---------------------------------------------------------------------------

  async setTorch(enabled) {
    return VibeCameraNative.setTorch({
      enabled
    })
  },

  async setFlashMode(mode) {
    return VibeCameraNative.setFlashMode({
      mode
    })
  },

  // ---------------------------------------------------------------------------
  // Zoom
  // ---------------------------------------------------------------------------

  async getZoomState() {
    return VibeCameraNative.getZoomState()
  },

  async setZoomRatio(ratio) {
    return VibeCameraNative.setZoomRatio({
      ratio
    })
  },

  // ---------------------------------------------------------------------------
  // Temporary capture cleanup
  // ---------------------------------------------------------------------------

  async deleteTemporaryCapture(path) {
    return VibeCameraNative.deleteTemporaryCapture({
      path
    })
  },

  // ---------------------------------------------------------------------------
  // Video recording events
  // ---------------------------------------------------------------------------

  async addVideoRecordingFinishedListener(callback) {
    return VibeCameraNative.addListener('videoRecordingFinished', callback)
  },

  async addVideoRecordingErrorListener(callback) {
    return VibeCameraNative.addListener('videoRecordingError', callback)
  },

  // ---------------------------------------------------------------------------
  // Permission events
  // ---------------------------------------------------------------------------

  async addCameraPermissionChangedListener(callback) {
    return VibeCameraNative.addListener('cameraPermissionChanged', callback)
  }
}

export default Camera
