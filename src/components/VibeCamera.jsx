import { useEffect, useRef, useState } from 'react'
import { useCamera } from '../hooks/useCamera'

export function VibeCamera({ autoStart = true, onCapture, onError }) {
  const {
    isActive,
    isCapturing,
    isRecording,
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

      const video = await stopRecording()

      console.log('VIDEO CAPTURED:', video)

      onCapture?.(video)
    } catch (error) {
      console.error('VIDEO ERROR:', error)

      onError?.(error)
    }
  }

  return (
    <div className="vibemoments-camera">
      <div id="vibemoments-camera-preview" className="vibemoments-camera-preview" />

      {isRecording && <div className="vibemoments-camera-recording-time">REC {recordingSeconds}s / 30s</div>}

      <div className="vibemoments-camera-controls">
        <button type="button" onClick={switchCamera} disabled={!isActive || isRecording}>
          Flip
        </button>

        <button type="button" onClick={handleCapture} disabled={!isActive || isCapturing}>
          {isCapturing ? 'Capturing...' : 'Capture'}
        </button>

        <button type="button" onClick={handleVideo} disabled={!isActive || isCapturing}>
          {isRecording ? 'Stop Video' : 'Record Video'}
        </button>

        {hasFlash && (
          <button type="button" onClick={() => setTorch(!torchEnabled)}>
            Torch: {torchEnabled ? 'On' : 'Off'}
          </button>
        )}

        {hasFlash && (
          <button
            type="button"
            onClick={() => {
              const next = flashMode === 'off' ? 'auto' : flashMode === 'auto' ? 'on' : 'off'

              setFlashMode(next)
            }}>
            Flash: {flashMode}
          </button>
        )}
      </div>
    </div>
  )
}
