# React API

[Documentation home](../README.md) · [Previous: Camera API](camera-api.md) · [Next: Video pipeline](video-pipeline.md)

```js
import { Camera, useCamera, VibeCamera } from '@barilchaton/vibemoments-camera'
```

Use `VibeCamera` for the supplied controls or `useCamera` for custom UI. Mount one camera owner at a time. The application controls permission prompts, session creation, navigation, and when the camera screen is mounted.

## `VibeCamera` props

| Prop | Default | Purpose |
| --- | --- | --- |
| `autoStart` | `true` | Starts preview on initial mount; does not request camera permission |
| `captureSession` | `null` | Object containing backend-issued `captureSessionId` and `nonce` |
| `sessionUpdating` | `false` | Disables capture while replacing a session |
| `initialMode` | `'photo'` | Initial mode: `photo` or `video` |
| `onCapture(media)` | — | Receives signed photo or completed video |
| `onModeChange(mode)` | — | Lets the application issue a matching session |
| `onError(error)` | — | Reports errors forwarded by component handlers |
| `onClose()` | — | Asks the parent to close/unmount the camera screen |

`initialMode` initializes internal state; it is not a controlled `mode` prop. In the supplied component, `autoStart` is read by the mount effect; toggling it later is not a start command. For explicit start controls, use the hook.

The component's capture button requires an active camera, a non-null session, and no capture/session-update/recording-start operation in progress. Native still validates session fields. The UI shows a 30-second recording timer; native enforces the actual limit.

## Permission-gated screen

This example receives a fresh photo session from its parent. Session creation and identity registration belong to the application backend integration.

```jsx
import { useState } from 'react'
import { Capacitor } from '@capacitor/core'
import { Camera, VibeCamera } from '@barilchaton/vibemoments-camera'

export function CameraEntry({ photoSession, onCapture, onModeChange }) {
  const [open, setOpen] = useState(false)
  const [message, setMessage] = useState('')

  async function openCamera() {
    try {
      if (Capacitor.getPlatform() !== 'android') {
        setMessage('Camera capture is available in the Android app.')
        return
      }
      let permissions = await Camera.checkPermissions()
      if (['prompt', 'denied'].includes(permissions.camera)) {
        permissions = await Camera.requestPermissions()
      }
      if (permissions.camera !== 'granted') {
        setMessage('Enable camera access in Android Settings to continue.')
        return
      }
      setMessage('')
      setOpen(true)
    } catch (error) {
      setMessage(error.message || 'Unable to open camera')
    }
  }

  return (
    <main style={{ background: open ? 'transparent' : undefined }}>
      {!open && <button onClick={openCamera}>Open camera</button>}
      {message && <p role="alert">{message}</p>}
      {open && (
        <VibeCamera
          captureSession={photoSession}
          onModeChange={onModeChange}
          onCapture={(media) => {
            onCapture(media)
            setOpen(false)
          }}
          onError={(error) => {
            setMessage(error.message || 'Camera operation failed')
            if (error.code === 'CAMERA_PERMISSION_REQUIRED') setOpen(false)
          }}
          onClose={() => setOpen(false)}
        />
      )}
    </main>
  )
}
```

For a two-mode screen, replace `photoSession` with application state that follows mode changes, as below. The minimal screen above illustrates permission gating, not complete session-refresh logic. App-level backgrounds must also be transparent while open.

## Capture session and mode changes

The parent should manage `mode`, `captureSession`, and `sessionUpdating` together. Issue a new session for a new capture or a mode change. Do not let an older asynchronous response replace a newer session.

An application-side controller can use a sequence guard:

```jsx
const requestSequence = useRef(0)
const [session, setSession] = useState(null)
const [sessionUpdating, setSessionUpdating] = useState(false)

async function refreshSession(mode) {
  const sequence = ++requestSequence.current
  setSession(null)
  setSessionUpdating(true)
  try {
    // Application helper: authenticated backend call, not a Camera method.
    const next = await createCaptureSession(mode)
    if (sequence === requestSequence.current) setSession(next)
  } catch (error) {
    if (sequence === requestSequence.current) reportError(error)
  } finally {
    if (sequence === requestSequence.current) setSessionUpdating(false)
  }
}

// Call refreshSession('photo') before initial capture, and render:
<VibeCamera
  captureSession={session}
  sessionUpdating={sessionUpdating}
  onModeChange={refreshSession}
  onCapture={handleCapture}
  onError={reportError}
  onClose={closeCamera}
/>
```

This is a controller fragment: import `useRef`/`useState` and supply the named application helpers. Invalidate pending requests when the controller is disposed. Keep the active recording's session stable until completion; issuing a session must not overwrite proof associated with an in-flight recording. A retry after session expiry or consumption needs a backend-approved fresh session.

## `useCamera()` state

| State | Meaning |
| --- | --- |
| `isActive` | Hook believes preview is active |
| `isCapturing` | Photo capture call is pending |
| `isStartingRecording` | Native recording startup is pending |
| `isRecording` | Recording has started and has not completed/failed |
| `recordedVideo` | Most recent video completion payload |
| `lens` | Current selected lens, initially `back` |
| `hasFlash` | Active camera reports flash capability |
| `torchEnabled` | Last accepted torch setting |
| `flashMode` | `off`, `auto`, or `on` |
| `error` | Most recent hook error or `null` |

These are UI states, not authoritative permission grants. The supplied hook checks permissions in `start()`, but it does not yet subscribe to the later `cameraPermissionChanged` event. Integrate that event in the camera owner or complete the hook subscription before relying on live invalidation after permission loss.

## `useCamera()` actions

| Action | Behavior |
| --- | --- |
| `start()` | Checks permission, starts preview for `lens`, then loads capabilities |
| `stop()` | Idempotent preview stop using refs to prevent duplicate teardown |
| `capturePhoto(options)` | Forwards `captureSessionId` and `nonce`; returns signed photo |
| `switchCamera()` | Switches lens and refreshes capabilities |
| `setTorch(enabled)` | Applies torch setting and updates state |
| `setFlashMode(mode)` | Applies flash setting and updates state |
| `startRecording(options)` | Forwards session fields and starts audio-enabled recording |
| `stopRecording()` | Requests stop; completed video arrives through `recordedVideo` |

Unlike low-level `Camera.stopRecording()`, the supplied hook's `stopRecording()` does not return the video result. It awaits native stop, while the finished listener updates `recordedVideo`. The component then forwards that state through `onCapture`.

The hook hard-codes `withAudio: true`. It does not expose zoom methods, a session-creation method, or a permission-request action. Use `Camera` for those supported low-level operations and your own backend for sessions.

For custom controls, pass an options object explicitly:

```jsx
<button
  disabled={!isActive || isCapturing || !session}
  onClick={() => capturePhoto({
    captureSessionId: session.captureSessionId,
    nonce: session.nonce
  }).then(handleCapture).catch(reportError)}>
  Take photo
</button>
```

Do not use `onClick={capturePhoto}`: that passes a React event as options and omits required proof. Disable recording controls while startup is pending and avoid concurrent native starts.

## Preview layering and styling

Native preview is behind the WebView:

```text
React controls and transparent WebView content
Native PreviewView / TextureView
```

Make the camera content and covering app-shell backgrounds transparent while the camera is open. Restore normal backgrounds when it closes. Hide application navigation if it covers camera controls. The package should not modify application routes.

The supplied UI uses Tailwind utility classes and app-specific tokens, including `bg-vibe-apricot`, `text-vibe-text`, and `safe-top`. Define those in the app or adapt the component styling. Tailwind is not a camera runtime dependency, but the consuming compiler must discover the package's class names.

For an app using explicit Tailwind source declarations, adjust this relative path to the stylesheet location:

```css
@import 'tailwindcss';
@source '../node_modules/@barilchaton/vibemoments-camera/dist';
```

The outer camera overlay uses `pointer-events-none`, with interactive control areas using `pointer-events-auto`. Preserve suitable touch routing for native pinch gestures.

## Closing and cleanup

Use `onClose={() => setCameraOpen(false)}` so the application unmounts the screen. The hook owns native cleanup and marks its active ref false before awaiting stop, preventing component cleanup and hook cleanup from issuing duplicate stops.

Keep video completion delivery alive if backgrounding or closing must preserve a recording for later upload. The supplied hook's listeners live with the component and do not provide persistent delivery across process death. See [Lifecycle](lifecycle.md) for integration edges and listener cleanup.

---

[Previous: Camera API](camera-api.md) · [Next: Video pipeline](video-pipeline.md) · [Permissions](permissions.md)
