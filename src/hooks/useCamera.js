import { useCallback, useEffect, useState } from 'react'
import { Camera } from '../native/camera'

export function useCamera() {
  const [isActive, setIsActive] = useState(false)
  const [isCapturing, setIsCapturing] = useState(false)
  const [lens, setLens] = useState('back')
  const [torchEnabled, setTorchEnabled] = useState(false)
  const [flashMode, setFlashModeState] = useState('off')
  const [hasFlash, setHasFlash] = useState(false)
  const [error, setError] = useState(null)
  const [isRecording, setIsRecording] = useState(false)
  const [isStartingRecording, setIsStartingRecording] = useState(false)
  const [recordedVideo, setRecordedVideo] = useState(null)

  const start = useCallback(async () => {
    console.log('[VibeCamera] useCamera.start called')

    try {
      setError(null)

      console.log('[VibeCamera] calling native startPreview')

      const result = await Camera.startPreview({
        lens
      })

      const capabilities = await Camera.getCapabilities()

      setHasFlash(capabilities?.hasFlash === true)
      setIsActive(true)
    } catch (err) {
      console.error('[VibeCamera] start failed:', err)

      setError(err)
      throw err
    }
  }, [lens])

  const stop = useCallback(async () => {
    try {
      await Camera.stopPreview()
      setIsActive(false)
    } catch (err) {
      setError(err)
      throw err
    }
  }, [])

  const capturePhoto = useCallback(async () => {
    try {
      setIsCapturing(true)
      setError(null)

      return await Camera.capturePhoto()
    } catch (err) {
      setError(err)
      throw err
    } finally {
      setIsCapturing(false)
    }
  }, [])

  const switchCamera = useCallback(async () => {
    try {
      setError(null)

      const result = await Camera.switchCamera()

      if (result?.lens) {
        setLens(result.lens)
      }

      const capabilities = await Camera.getCapabilities()

      setHasFlash(capabilities?.hasFlash === true)

      if (!capabilities?.hasFlash) {
        setTorchEnabled(false)
        setFlashModeState('off')
      }

      return result
    } catch (err) {
      setError(err)
      throw err
    }
  }, [])

  const setTorch = useCallback(async (enabled) => {
    try {
      setError(null)

      const result = await Camera.setTorch(enabled)

      setTorchEnabled(result.enabled)

      return result
    } catch (err) {
      setError(err)
      throw err
    }
  }, [])

  const setFlashMode = useCallback(async (mode) => {
    try {
      setError(null)

      const result = await Camera.setFlashMode(mode)

      setFlashModeState(result.mode)

      return result
    } catch (err) {
      setError(err)
      throw err
    }
  }, [])

  const startRecording = useCallback(async () => {
    try {
      setError(null)
      setIsStartingRecording(true)

      console.log('[VibeCamera] starting video recording')

      const result = await Camera.startRecording({
        withAudio: true
      })

      setIsRecording(true)

      return result
    } catch (err) {
      setError(err)
      setIsRecording(false)
      throw err
    } finally {
      setIsStartingRecording(false)
    }
  }, [])

  const stopRecording = useCallback(async () => {
    try {
      setError(null)

      console.log('[VibeCamera] requesting video stop')

      await Camera.stopRecording()
    } catch (err) {
      setError(err)
      setIsRecording(false)
      throw err
    }
  }, [])

  useEffect(() => {
    return () => {
      if (isActive) Camera.stopPreview()
    }
  }, [isActive])

  useEffect(() => {
    let finishedListener
    let errorListener

    async function setupRecordingListeners() {
      finishedListener = await Camera.addVideoRecordingFinishedListener((video) => {
        console.log('[VibeCamera] video recording finished', video)

        setIsRecording(false)
        setRecordedVideo(video)
      })

      errorListener = await Camera.addVideoRecordingErrorListener((event) => {
        console.error('[VibeCamera] video recording error', event)

        setIsRecording(false)

        setError(new Error(event?.message || 'Video recording failed'))
      })
    }

    setupRecordingListeners()

    return () => {
      finishedListener?.remove()
      errorListener?.remove()
    }
  }, [])

  return {
    isActive,
    isCapturing,
    isRecording,
    lens,
    error,

    hasFlash,
    torchEnabled,
    flashMode,
    recordedVideo,
    isStartingRecording,

    start,
    stop,
    capturePhoto,
    switchCamera,
    setTorch,
    setFlashMode,
    startRecording,
    stopRecording
  }
}
