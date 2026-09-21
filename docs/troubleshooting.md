# Troubleshooting

[Documentation home](../README.md) · [Previous: Development](development.md)

## Camera permission required

`CAMERA_PERMISSION_REQUIRED` means native or the hook rejected use without current camera access. Call `Camera.checkPermissions()` and let the UI explicitly invoke `requestPermissions()` where appropriate. `startPreview()` no longer prompts automatically.

For `blocked`, provide Android Settings guidance and recheck on return. Remember that this status is based on request history and rationale availability; it does not prove which OS dialog choice occurred. See [Permissions](permissions.md).

## `checkPermissions` is undefined or not implemented

The wrapper, hook, and native build are out of step. The uploaded hook already called the new method while the older wrapper lacked it. Install the matching wrapper and native permission update, rebuild the package, install it in the host app, run Capacitor sync, and rebuild Android.

A native method-not-implemented error cannot be fixed just by adding another JavaScript call.

## `VibeCameraNative` is not exported

The proposed replacement once declared the proxy privately while `src/index.js` still re-exported it. Keep:

```js
export const VibeCameraNative = registerPlugin('VibeCamera')
```

Then rebuild. This preserves the existing public export rather than removing it from the package entry point.

## Cleanup or recording-state method is missing

The later proposed wrapper introduced `deleteTemporaryCapture()` and `getRecordingState()` without matching native methods in the inspected archive. That archive has `deleteCapture()`, `clearCache()`, and `getCameraState()` instead. Reconcile both layers using the [compatibility table](camera-api.md#version-compatibility); do not assume a wrapper rename is a native alias.

## Black preview

Check current permission, successful CameraX binding, and transparent WebView/app-shell backgrounds. An opaque React element can hide a functioning native preview.

Useful log fragments from the existing implementation include:

```text
Binding camera lens: back
Camera bound successfully: back
Camera preview ready
```

If binding succeeds but no image is visible, inspect layering before changing capture code. If binding fails, inspect native logs and camera ownership. See [Architecture](architecture.md) and [React styling](react-api.md#preview-layering-and-styling).

## Captured photo or video will not display

Convert the returned native path:

```js
const webPath = Capacitor.convertFileSrc(media.path)
```

Use it in `<img>` or `<video controls playsInline>`. Confirm the temporary file has not been deleted or cleared. Thumbnail generators should also receive the converted URL. See [Media files](media-files.md).

## Capture button disabled or missing session proof

`VibeCamera` disables capture without an active camera or session, or while session replacement/capture/recording startup is pending. A non-null object is not enough if it lacks `captureSessionId` or `nonce`: native rejects blank fields.

Obtain a backend-issued session and pass it through `captureSession`. With the hook, call `capturePhoto({ captureSessionId, nonce })`; do not directly assign that method as an event handler and accidentally pass a React click event.

## Video publishes as a session-type mismatch

A session created for `photo` cannot authorize a `video` capture. Wire `onModeChange` into application session creation and set `sessionUpdating` until the matching response arrives. Guard against older responses replacing newer mode sessions. This resolved the photo/video mismatch reported in the source conversation.

## Hash or signature verification fails

Verify that the backend hashes the uploaded original bytes and that no recompression or metadata rewriting occurred. Reconstruct the six-line UTF-8 payload exactly, with LF separators, lowercase file hash, `photo`/`video` type, and no trailing newline.

Check registered device key, nonce, session ownership, expiry/consumption state, accepted proof version, and DER-versus-raw ECDSA encoding. Identity uses `algorithm`; capture results use `signatureAlgorithm`. See [Capture security](capture-security.md) for the complete format.

Do not bypass backend verification because the file plays correctly or because JavaScript contains a nonempty signature.

## Recording will not start

Check camera permission, session fields, microphone permission when audio is enabled, and whether a start is already pending. Look under `VibeCamera` and `VibeVideoRecorder` in Logcat.

Useful audio-enabled progress messages include:

```text
Starting video recorder
Camera2 device opened
Camera2 video session configured
Microphone recording started
Video recording started
Audio encoder format ready
Video encoder format ready
MediaMuxer started with video + audio
```

Asynchronous ordering can vary; audio-specific messages are not expected for silent recording. If Camera2 cannot acquire the device, verify CameraX was released before startup. If format readiness or muxing stalls, inspect codec/audio errors rather than only React state.

## Recording already starting or in progress

A duplicate request reached a busy recorder. Disable controls while `isStartingRecording` or `isRecording` requires it, and avoid registering multiple start handlers. Native guards remain necessary even with disabled buttons. See [Video pipeline](video-pipeline.md).

## Recording preview stretched or output rotated

For stretching, verify the 1280 × 720 preview buffer and the `TextureView` transform agree with encoder dimensions. For rotated playback, inspect output orientation metadata separately from the preview transformation. Test both lenses; a correct photo preview does not establish that the Camera2 video preview is configured correctly.

## Zoom does not work during recording

Confirm `setZoomRatio` is routed to the active Camera2 recorder and query its range with `getZoomState()`. A CameraX-only zoom update cannot control a camera released for recording. For pinch failures, inspect whether the WebView or React overlay consumes gestures over the preview.

## Camera error after minimizing

Backgrounding is supposed to stop recording and finalize a partial file. Relevant messages include:

```text
Stopping recording because app entered background
Stopping video recording
Video encoder EOS reached
Audio encoder EOS reached
Finalizing video
Video saved
```

Verify lifecycle callbacks, finalization errors, and whether the completion listener remains mounted. A killed process may not deliver a finished event. Recheck permissions on resume, particularly after one-time access.

## Camera does not restore after recording

Expected eligible transition: CameraX preview → Camera2 recording → CameraX preview. Inspect Camera2 cleanup and `CameraX preview restored` logs.

Restoration should be suppressed when the app is paused, the camera screen is gone, or permission is missing. Do not force binding to make this log appear. `restoreCameraXPreview()` must retain its own permission guard. See [Lifecycle](lifecycle.md).

## Duplicate stop or blank screen on close

Let the application unmount the camera through `onClose`, and retain the hook's idempotent cleanup. Mark inactive before awaiting native stop; avoid effects that stop again merely because `isActive` changes.

Restore the app's normal background and navigation when the screen closes. If a late preview start completes after unmount, ensure that resource is stopped too.

## Duplicate video capture or upload

A manual `Camera.stopRecording()` result and `videoRecordingFinished` can represent the same capture. Use one delivery path or deduplicate using session/file identity. The supplied hook delivers through `recordedVideo`; its `stopRecording()` does not return that media object.

Keep publication success separate from local deletion failure. Retrying publication because cache cleanup failed can duplicate app-level work even though the upload succeeded.

## Styles missing or invalid hook call

For missing styles, make the app's Tailwind compiler scan the scoped package and provide the UI's custom theme/safe-area classes. For invalid hook calls, check that React is provided by the consumer and externalized from the library bundle rather than bundled twice. See [React API](react-api.md) and [Development](development.md).

## Kotlin change has no effect

Rebuild/pack the library, install the new version, synchronize Capacitor, and rebuild/relaunch the Android app. A Vite rebuild alone cannot update installed native code. If compilation fails after the permission update, inspect the exact installed Capacitor signatures and compiler error; the final replacement was not confirmed compiled in the original discussion.

---

[Previous: Development](development.md) · [Documentation home](../README.md) · [Camera API](camera-api.md)
