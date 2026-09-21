import { useEffect, useRef, useState } from 'react'
import { FiCamera, FiRefreshCw, FiZap, FiX } from 'react-icons/fi'
import { useCamera } from '../hooks/useCamera'

export function VibeCamera({
  autoStart = true,
  captureSession = null,
  sessionUpdating = false,
  initialMode = 'photo',
  onCapture,
  onModeChange,
  onError,
  onClose
}) {
  const {
    isActive,
    isCapturing,
    isRecording,
    isStartingRecording,
    recordedVideo,
    lens,
    hasFlash,
    torchEnabled,
    flashMode,
    start,
    stop,
    capturePhoto,
    switchCamera,
    setTorch,
    setFlashMode,
    startRecording,
    stopRecording
  } = useCamera()

  const hasStarted = useRef(false)

  const [mode, setMode] = useState(initialMode)
  const [recordingSeconds, setRecordingSeconds] = useState(0)

  useEffect(() => {
    if (!autoStart || hasStarted.current) return

    hasStarted.current = true

    start().catch((error) => {
      hasStarted.current = false
      onError?.(error)
    })

    return () => {
      stop().catch(() => {})
    }
  }, [])

  useEffect(() => {
    if (!isRecording) {
      setRecordingSeconds(0)
      return
    }

    const startedAt = Date.now()

    const interval = setInterval(() => {
      const elapsed = Math.floor((Date.now() - startedAt) / 1000)

      setRecordingSeconds(Math.min(elapsed, 30))
    }, 250)

    return () => clearInterval(interval)
  }, [isRecording])

  useEffect(() => {
    if (!recordedVideo) return

    onCapture?.(recordedVideo)
  }, [recordedVideo])

  const handleModeChange = (nextMode) => {
    if (nextMode === mode) return

    setMode(nextMode)
    onModeChange?.(nextMode)
  }

  const cycleFlashMode = async () => {
    try {
      const next = flashMode === 'off' ? 'auto' : flashMode === 'auto' ? 'on' : 'off'

      await setFlashMode(next)
    } catch (error) {
      onError?.(error)
    }
  }

  const handlePhoto = async () => {
    try {
      const media = await capturePhoto({
        captureSessionId: captureSession?.captureSessionId ?? null,
        nonce: captureSession?.nonce ?? null
      })

      console.log('VibeCamera photo captured:', media)

      onCapture?.(media)
    } catch (error) {
      onError?.(error)
    }
  }

  const handleVideo = async () => {
    try {
      if (isRecording) {
        await stopRecording()
        return
      }

      await startRecording({
        captureSessionId: captureSession?.captureSessionId ?? null,
        nonce: captureSession?.nonce ?? null
      })
    } catch (error) {
      onError?.(error)
    }
  }

  const handleCapture = () => {
    if (mode === 'video') {
      handleVideo()
      return
    }

    handlePhoto()
  }

  const handleClose = () => {
    onClose?.()
  }

  const captureDisabled = !isActive || !captureSession || isCapturing || isStartingRecording || sessionUpdating

  const flipDisabled = !isActive || isCapturing || isRecording || isStartingRecording

  return (
    <div className="pointer-events-none absolute inset-0 z-20 flex flex-col">
      <div className="pointer-events-auto safe-top flex items-center justify-between px-5 pt-4">
        <button
          className="flex size-11 items-center justify-center rounded-full bg-black/45 text-white backdrop-blur-md transition active:scale-95"
          type="button"
          onClick={handleClose}>
          <FiX className="text-2xl" />
        </button>

        <div className="flex items-center gap-3">
          {hasFlash && (
            <button
              className="flex h-11 items-center gap-2 rounded-full bg-black/45 px-4 text-sm font-semibold text-white backdrop-blur-md transition active:scale-95 disabled:opacity-40"
              type="button"
              disabled={isRecording}
              onClick={cycleFlashMode}>
              <FiZap className="text-lg" />

              <span>
                {flashMode === 'off' && 'Off'}
                {flashMode === 'auto' && 'Auto'}
                {flashMode === 'on' && 'On'}
              </span>
            </button>
          )}

          {hasFlash && (
            <button
              className={`flex size-11 items-center justify-center rounded-full backdrop-blur-md transition active:scale-95 disabled:opacity-40 ${
                torchEnabled ? 'bg-vibe-apricot text-vibe-text' : 'bg-black/45 text-white'
              }`}
              type="button"
              disabled={isRecording}
              onClick={() => setTorch(!torchEnabled)}>
              <FiZap className="text-xl" />
            </button>
          )}
        </div>
      </div>

      <div className="flex-1" />

      <div className="pointer-events-auto px-5 pb-[calc(env(safe-area-inset-bottom)+1.5rem)]">
        {isRecording && (
          <div className="mb-5 flex justify-center">
            <div className="rounded-full bg-red-500/90 px-4 py-2 text-sm font-bold text-white backdrop-blur-md">
              0:{String(recordingSeconds).padStart(2, '0')}
            </div>
          </div>
        )}

        {!isRecording && (
          <div className="mb-5 flex justify-center">
            <div className="flex rounded-full bg-black/45 p-1 backdrop-blur-md">
              <button
                className={`rounded-full px-5 py-2 text-sm font-bold transition ${mode === 'photo' ? 'bg-white text-black' : 'text-white'}`}
                type="button"
                disabled={sessionUpdating}
                onClick={() => handleModeChange('photo')}>
                Photo
              </button>

              <button
                className={`rounded-full px-5 py-2 text-sm font-bold transition ${mode === 'video' ? 'bg-white text-black' : 'text-white'}`}
                type="button"
                disabled={sessionUpdating}
                onClick={() => handleModeChange('video')}>
                Video
              </button>
            </div>
          </div>
        )}

        <div className="grid grid-cols-3 items-center">
          <div />

          <div className="flex justify-center">
            <button
              className={`relative flex size-20 items-center justify-center rounded-full border-4 border-white transition active:scale-95 disabled:opacity-40 ${
                isRecording ? 'bg-transparent' : 'bg-white/20'
              }`}
              type="button"
              disabled={captureDisabled}
              onClick={handleCapture}>
              {mode === 'photo' && !isRecording && <div className="size-16 rounded-full bg-white" />}

              {mode === 'video' && !isRecording && <div className="size-16 rounded-full bg-red-500" />}

              {isRecording && <div className="size-8 rounded-lg bg-red-500" />}
            </button>
          </div>

          <div className="flex justify-end">
            <button
              className="flex size-12 items-center justify-center rounded-full bg-black/45 text-white backdrop-blur-md transition active:scale-95 disabled:opacity-40"
              type="button"
              disabled={flipDisabled}
              onClick={switchCamera}>
              <FiRefreshCw className="text-xl" />
            </button>
          </div>
        </div>

        <div className="mt-3 text-center text-xs font-medium text-white/70">
          {mode === 'photo' && 'Tap to capture'}
          {mode === 'video' && !isRecording && 'Up to 30 seconds'}
          {isRecording && 'Tap to stop recording'}
        </div>
      </div>
    </div>
  )
}
