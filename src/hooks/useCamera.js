import { useCallback, useEffect, useState } from 'react'
import { Camera } from '../native/camera'

export function useCamera() {
  const [isActive, setIsActive] = useState(false)
  const [isCapturing, setIsCapturing] = useState(false)
  const [lens, setLens] = useState('back')
  const [error, setError] = useState(null)

  const start = useCallback(async () => {
    console.log('[VibeCamera] useCamera.start called')

    try {
      setError(null)

      console.log('[VibeCamera] calling native startPreview')

      const result = await Camera.startPreview({
        lens
      })

      console.log('[VibeCamera] native startPreview result:', result)

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

      return result
    } catch (err) {
      setError(err)
      throw err
    }
  }, [])

  useEffect(() => {
    return () => {
      if (isActive) Camera.stopPreview()
    }
  }, [isActive])

  return {
    isActive,
    isCapturing,
    lens,
    error,
    start,
    stop,
    capturePhoto,
    switchCamera
  }
}
