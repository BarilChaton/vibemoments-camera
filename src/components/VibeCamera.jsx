import { useEffect, useRef } from 'react'
import { useCamera } from '../hooks/useCamera'

export function VibeCamera({ autoStart = true, onCapture, onError }) {
  const { isActive, isCapturing, start, stop, capturePhoto, switchCamera } = useCamera()
  const hasStarted = useRef(false)

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

  async function handleCapture() {
    try {
      const media = await capturePhoto()
      onCapture?.(media)
    } catch (error) {
      onError?.(error)
    }
  }

  return (
    <div className="vibemoments-camera">
      <div id="vibemoments-camera-preview" className="vibemoments-camera-preview" />

      <div className="vibemoments-camera-controls">
        <button type="button" onClick={switchCamera} disabled={!isActive}>
          Flip
        </button>

        <button type="button" onClick={handleCapture} disabled={!isActive || isCapturing}>
          {isCapturing ? 'Capturing...' : 'Capture'}
        </button>
      </div>
    </div>
  )
}
