# Permissions

[Documentation home](../README.md) · [Previous: Architecture](architecture.md) · [Next: Capture security](capture-security.md)

## Explicit permission contract

```js
import { Camera } from '@barilchaton/vibemoments-camera'

const status = await Camera.checkPermissions()
// { camera: 'granted', microphone: 'prompt' }
```

Both fields use the same application-facing status values:

| Value | Meaning in this plugin | UI action |
| --- | --- | --- |
| `granted` | Android currently grants access | Proceed, retaining native operation checks |
| `prompt` | Not granted and no stored request history | Explain the need and offer an explicit request |
| `denied` | Not granted, requested previously, rationale is available | Explain why access is needed and offer another request |
| `blocked` | Not granted, requested previously, rationale is unavailable | Offer Android Settings guidance and recheck on return |

`checkPermissions()` does not display a dialog or start the camera.

```js
const statusAfterRequest = await Camera.requestPermissions()
```

In the agreed update, `requestPermissions()` requests **camera permission only** and returns the status of both camera and microphone. It does not open preview. There is no confirmed `requestMicrophonePermission()` API or supported permission-selector argument.

## Onboarding and feature screens

Use an explicit user action to request access:

```js
export async function requestCameraFromButton() {
  const current = await Camera.checkPermissions()
  if (current.camera === 'granted' || current.camera === 'blocked') {
    return current
  }
  return Camera.requestPermissions()
}
```

For `blocked`, explain how to enable camera access in Android Settings. This package does not expose a confirmed settings-opening method. On return from settings, query again; do not infer success from leaving the app.

Before mounting a camera screen, check permission again. `useCamera.start()` also checks, and native `startPreview()`, `capturePhoto()`, and `startRecording()` independently enforce access. Native rejects a missing camera grant with `CAMERA_PERMISSION_REQUIRED` rather than initiating a camera request. Permission checking is before the preview-already-exists shortcut, so stale views cannot bypass the guard.

The application should catch this error, leave the capture UI in an inactive state, and display the appropriate permission action. Do not repeatedly retry preview startup when access is missing.

## Native declarations

The library manifest declares:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

The Capacitor plugin declares matching aliases, `camera` and `microphone`. Manifest declarations alone do not grant runtime access. Optional camera hardware declaration also means installation alone does not establish that a usable camera exists.

## Request history and classification limits

The discussed resolver checks actual permission first, then persistent request history, then `shouldShowRequestPermissionRationale`. History is stored in `vibecamera_permissions`, using `requested_camera` and `requested_microphone` flags, and updated when a request is made.

A false rationale result before the first request is not enough to classify a permission as blocked. Request history distinguishes that initial state. However, the resolver is still an application classification: revocation, one-time access expiry, or OS policy may produce `blocked` under this rule without proving the user selected a permanent-denial option. Do not present `blocked` as infallible evidence of a particular user choice.

## One-time grants and permission loss

“Allow only this time” appears as `granted` while valid; it is not a fifth status. Backgrounding does not imply immediate revocation on every device. Access must be re-evaluated when the app resumes and before native operations.

The updated lifecycle contract is:

1. `handleOnResume()` rechecks actual camera access.
2. If access is absent, cancel pending CameraX restoration and clean preview/recording state.
3. Emit `cameraPermissionChanged` so JavaScript can invalidate stale UI state.
4. `restoreCameraXPreview()` performs its own guard before binding CameraX.
5. Only restore when the application is active, the camera screen still owns preview, and permission remains granted.

```js
const listener = await Camera.addCameraPermissionChangedListener((event) => {
  if (event.camera !== 'granted') {
    // Close/invalidate the camera screen in application state.
    // Recheck permission before a later attempt to reopen it.
  }
})

// Remove when the owning screen or controller is disposed.
await listener.remove()
```

The event communicates observed permission changes; it is not a substitute for checks or a promise that every OS change will produce a live notification. Process termination destroys in-memory state and listeners. A fresh process must initialize and check again.

## Microphone behavior

Photo capture does not require microphone permission. `Camera.startRecording({ withAudio: true, captureSessionId, nonce })` uses the existing native microphone request path when needed; the request is included in microphone history tracking. The low-level API supports `withAudio: false`. The supplied React hook always requests audio-enabled recording, even if a caller passes a different `withAudio` value to that hook.

Do not silently assume denied microphone access creates a silent video: the audio-enabled path must successfully obtain permission or report failure. Use the low-level API for an explicitly silent recording flow.

## Migration from the old README

Replace “the plugin requests permissions when necessary” with the distinction above: camera requests are explicit, microphone requests remain tied to audio recording. Deploy the native permission methods and matching wrapper together before the hook starts calling `checkPermissions()`.

See [release verification](development.md#release-verification) for the source-versus-update boundary and [Lifecycle](lifecycle.md) for cleanup ownership.

---

[Previous: Architecture](architecture.md) · [Next: Capture security](capture-security.md) · [Troubleshooting](troubleshooting.md)
