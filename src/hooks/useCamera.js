import { useCallback, useEffect, useRef, useState } from 'react'
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

  const activeRef = useRef(false)
  const stoppingRef = useRef(false)

  const start = useCallback(async () => {
    console.log('[VibeCamera] useCamera.start called')

    if (activeRef.current) {
      console.log('[VibeCamera] preview already active, ignoring start')
      return
    }

    try {
      setError(null)

      console.log('[VibeCamera] calling native startPreview')

      const result = await Camera.startPreview({
        lens
      })

      const capabilities = await Camera.getCapabilities()

      activeRef.current = true

      setHasFlash(capabilities?.hasFlash === true)
      setIsActive(true)

      return result
    } catch (err) {
      activeRef.current = false

      console.error('[VibeCamera] start failed:', err)

      setError(err)

      throw err
    }
  }, [lens])

  const stop = useCallback(async () => {
    if (!activeRef.current || stoppingRef.current) {
      return
    }

    stoppingRef.current = true

    /*
     * Mark the preview inactive before awaiting native cleanup.
     *
     * This makes stop() idempotent. If another cleanup path runs while
     * stopPreview() is in progress, it will see activeRef=false and return
     * without issuing another native stopPreview call.
     */
    activeRef.current = false

    try {
      console.log('[VibeCamera] stopping native preview')

      await Camera.stopPreview()

      setIsActive(false)
      setTorchEnabled(false)
      setFlashModeState('off')
    } catch (err) {
      setError(err)

      throw err
    } finally {
      stoppingRef.current = false
    }
  }, [])

  const capturePhoto = useCallback(async (options = {}) => {
    try {
      setIsCapturing(true)
      setError(null)

      return await Camera.capturePhoto(options)
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

  const startRecording = useCallback(async (options = {}) => {
    try {
      setError(null)
      setIsStartingRecording(true)

      console.log('[VibeCamera] starting video recording')

      const result = await Camera.startRecording({
        withAudio: true,
        captureSessionId: options.captureSessionId ?? null,
        nonce: options.nonce ?? null
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

  // ---------------------------------------------------------------------------
  // Native preview cleanup
  // ---------------------------------------------------------------------------

  useEffect(() => {
    return () => {
      if (!activeRef.current || stoppingRef.current) {
        return
      }

      activeRef.current = false

      console.log('[VibeCamera] cleaning up native preview on unmount')

      Camera.stopPreview().catch((err) => {
        console.error('[VibeCamera] failed to clean up preview on unmount:', err)
      })
    }
  }, [])

  // ---------------------------------------------------------------------------
  // Video recording events
  // ---------------------------------------------------------------------------

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
