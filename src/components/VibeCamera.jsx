import { useEffect, useRef, useState } from 'react'
import { useCamera } from '../hooks/useCamera'

export function VibeCamera({ autoStart = true, onCapture, onError }) {
  const {
    isActive,
    isCapturing,
    isRecording,
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

    const interval = setInterval(() => {
      setRecordingSeconds((current) => current + 1)
    }, 1000)

    return () => clearInterval(interval)
  }, [isRecording])

  useEffect(() => {
    if (!recordedVideo) return

    onCapture?.(recordedVideo)
  }, [recordedVideo])

  async function handleCapture() {
    try {
      const media = await capturePhoto()
      onCapture?.(media)
    } catch (error) {
      onError?.(error)
    }
  }

  async function handleVideo() {
    try {
      if (!isRecording) {
        await startRecording()
        return
      }

      await stopRecording()
    } catch (error) {
      onError?.(error)
    }
  }

  return (
    <div className="vibemoments-camera-controls">
      <div className="vibemoments-camera-top-controls">
        {hasFlash && (
          <button type="button" onClick={() => setTorch(!torchEnabled)} disabled={isRecording}>
            Torch: {torchEnabled ? 'On' : 'Off'}
          </button>
        )}

        {hasFlash && (
          <button
            type="button"
            onClick={() => {
              const next = flashMode === 'off' ? 'auto' : flashMode === 'auto' ? 'on' : 'off'

              setFlashMode(next)
            }}
            disabled={isRecording}>
            Flash: {flashMode}
          </button>
        )}
      </div>

      <div className="vibemoments-camera-main-controls">
        <button type="button" onClick={switchCamera} disabled={!isActive || isRecording}>
          Flip
        </button>

        <button type="button" onClick={handleCapture} disabled={!isActive || isCapturing || isRecording}>
          {isCapturing ? 'Capturing...' : 'Capture'}
        </button>

        <button type="button" onClick={handleVideo} disabled={!isActive || isCapturing}>
          {isRecording ? 'Stop Video' : 'Record Video'}
        </button>
      </div>
    </div>
  )
}
