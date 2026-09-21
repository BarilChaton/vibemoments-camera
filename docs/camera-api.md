# Camera API

[Documentation home](../README.md) · [Previous: Capture security](capture-security.md) · [Next: React API](react-api.md)

```js
import { Camera, VibeCameraNative } from '@barilchaton/vibemoments-camera'
```

Methods are asynchronous. `Camera` adapts scalar arguments to native objects, for example `Camera.setTorch(true)` invokes `VibeCameraNative.setTorch({ enabled: true })`. Prefer the wrapper in application code.

## Permissions and identity

| Method | Result / behavior |
| --- | --- |
| `checkPermissions()` | `{ camera, microphone }`, each `granted`, `prompt`, `denied`, or `blocked`; no prompt |
| `requestPermissions()` | Explicitly requests camera only, then returns both statuses |
| `getCaptureIdentity()` | `{ deviceId, publicKey, algorithm, proofVersion }` |

See [Permissions](permissions.md) and [Capture security](capture-security.md). The permission API describes the subsequent agreed update, rather than the older archive alone.

## Preview and lens

```js
await Camera.startPreview({ lens: 'back' })
await Camera.stopPreview()
const lensResult = await Camera.switchCamera()
const cameraState = await Camera.getCameraState()
```

`startPreview(options = {})` starts CameraX preview. `lens` is `front` or `back`; the ordinary initial lens is `back`. Native requires current camera permission and does not prompt for it. Startup returns active/lens information.

`stopPreview()` releases preview resources. Coordinate ownership through a single hook or controller; see [Lifecycle](lifecycle.md).

`switchCamera()` changes the active lens and returns its state. Refresh capabilities afterward. Disable switching while capture or recording startup/recording is in progress.

`getCameraState()` is present in the uploaded wrapper and native plugin. Its result includes `active` and `lens`, with zoom information in the inspected implementation. It does not replace a fresh permission check.

## Photo capture

```js
const photo = await Camera.capturePhoto({
  captureSessionId: session.captureSessionId,
  nonce: session.nonce
})
```

Requires camera permission, an active usable camera, and nonblank session fields. The native CameraX path writes a temporary JPEG, hashes it, and signs the proof before returning.

Illustrative result; placeholders are not usable proof values:

```js
{
  type: 'photo',
  path: '/data/user/0/com.example.app/cache/vibemoments-camera/photo_123456.jpg',
  mimeType: 'image/jpeg',
  lens: 'back',
  sha256: '<64 lowercase hex characters>',
  captureSessionId: '<server session ID>',
  nonce: '<server nonce>',
  deviceId: '<device UUID>',
  captureSignature: '<base64 signature>',
  proofVersion: 'vibemoments-capture-v1',
  signatureAlgorithm: 'ECDSA_P256_SHA256'
}
```

The old no-argument capture example is insufficient for the secure capture path.

## Video recording

```js
await Camera.startRecording({
  withAudio: true,
  captureSessionId: session.captureSessionId,
  nonce: session.nonce
})
```

Use a session issued for `video`. The inspected native API defaults audio to enabled. With audio enabled, the native path can request microphone permission. `withAudio: false` selects silent recording through the low-level API.

```js
const video = await Camera.stopRecording()
```

In the inspected native implementation, the manual stop call resolves with the completed video after finalization and signing. Completion is also emitted as an event. Automatic duration-limit and background stops are delivered by events without requiring a JavaScript stop call. Choose one delivery path for publishing, or deduplicate by session/file so a manual stop result plus event does not publish twice.

The video result has the photo proof fields with `type: 'video'`, `mimeType: 'video/mp4'`, and an MP4 path, plus:

```js
{
  durationMs: 5421,
  videoBitrate: 3000000,
  audioBitrate: 128000
}
```

Bitrate fields describe configured values; the inspected plugin reports `audioBitrate` even for results where callers should independently establish whether audio was enabled. Do not use that field alone to prove an audio track exists.

The native hard limit is 30 seconds. [Video pipeline](video-pipeline.md) explains encoding, timestamps, and restoration.

## Capabilities, torch, and flash

```js
const capabilities = await Camera.getCapabilities()
await Camera.setTorch(true)
await Camera.setTorch(false)
await Camera.setFlashMode('auto')
```

Capabilities include `hasFlash`, `lens`, `recording`, `zoomRatio`, `minZoomRatio`, and `maxZoomRatio` in the inspected source. Query for the active lens rather than assuming front and rear cameras have identical hardware.

`setTorch(enabled)` returns `{ enabled }`. `setFlashMode(mode)` accepts `off`, `auto`, or `on` and returns `{ mode }`. Torch is continuous illumination; photo flash is a capture setting. The supplied UI disables changes during recording and hides flash controls when unavailable.

## Zoom

```js
const zoom = await Camera.getZoomState()
// { ratio, minRatio, maxRatio, recording }

const requested = 2
await Camera.setZoomRatio(
  Math.min(zoom.maxRatio, Math.max(zoom.minRatio, requested))
)
```

The bridge routes zoom to CameraX in normal preview and to the Camera2 recorder during recording. Bounds depend on the device, lens, and active engine. Native pinch zoom is supported; an opaque or touch-intercepting WebView overlay can prevent gestures reaching preview. Re-query bounds after switching lens rather than hard-coding a maximum.

## Events

| Wrapper method | Native event | Payload |
| --- | --- | --- |
| `addVideoRecordingFinishedListener(callback)` | `videoRecordingFinished` | Completed signed video result |
| `addVideoRecordingErrorListener(callback)` | `videoRecordingError` | Error information including `message` |
| `addCameraPermissionChangedListener(callback)` | `cameraPermissionChanged` | Observed camera permission status, including `camera` |

Subscribe before starting recording, especially when relying on automatic stops:

```js
const finished = await Camera.addVideoRecordingFinishedListener(handleVideo)
const failed = await Camera.addVideoRecordingErrorListener(handleVideoError)

// After recording finishes and this owner is being disposed:
await finished.remove()
await failed.remove()
```

`handleVideo` and `handleVideoError` are application callbacks. Keep subscriptions alive for the completion you need; do not remove them immediately after starting. React asynchronous listener setup also needs cleanup if unmount occurs before registration finishes; see [Lifecycle](lifecycle.md).

## Temporary files

Verified archive APIs:

```js
await Camera.deleteCapture(media.path)
// { deleted: true, alreadyDeleted: false }
// or { deleted: false, alreadyDeleted: true }

await Camera.clearCache()
// { deleted: 4 }
```

Deletion is restricted to the camera cache directory. `clearCache()` is maintenance, not normal per-capture upload cleanup. See [Media files](media-files.md).

## Version compatibility

The later proposed permission wrapper was not a strict superset of the uploaded API:

| Method/export | Uploaded source | Later proposed wrapper | Integration action |
| --- | --- | --- | --- |
| Permission methods/event helper | Missing from old wrapper | Added | Install matching native permission update |
| `VibeCameraNative` | Exported | Initially made private, then corrected | Retain `export const VibeCameraNative` |
| `getCameraState()` | Wrapper and native present | Omitted | Preserve if maintaining existing API |
| `deleteCapture(path)` | Wrapper and native present | Replaced by `deleteTemporaryCapture(path)` | Keep verified method until both layers support a rename |
| `clearCache()` | Wrapper and native present | Omitted | Preserve if still part of supported cleanup API |
| `getRecordingState()` | No matching native method found | Added | Do not rely on it until implemented and tested |
| `deleteTemporaryCapture(path)` | No matching native method found | Added | A wrapper alone does not create a native method |

Examples here use verified `deleteCapture()` and recording events, and the agreed permission update. If adopting the proposed replacement, reconcile these differences first. Do not assume the two cleanup names are working aliases.

## Errors

Handle `CAMERA_PERMISSION_REQUIRED` as a permission UX transition. Missing session proof, unavailable camera/recorder, duplicate recording starts, encoding failures, and hashing/signing failures also reject operations or emit recording errors. Do not invent stable error codes from log-message text; only the camera permission code is established here as the new explicit contract.

---

[Previous: Capture security](capture-security.md) · [Next: React API](react-api.md) · [Troubleshooting](troubleshooting.md)
