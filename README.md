# VibeMoments Camera

A native Android camera engine and React camera interface built for **VibeMoments**.

`@barilchaton/vibemoments-camera` combines CameraX photo capture and preview with a Camera2 + MediaCodec video recorder, exposed through Capacitor. Use the low-level `Camera` API, the `useCamera` hook, or the `VibeCamera` component.

The camera engine is independently developed, packaged, and versioned. The consuming application owns onboarding, navigation, capture-session creation, publishing, and permanent storage.

## Features

- CameraX JPEG photos and aspect-ratio-preserving preview.
- Front/rear cameras, flash, torch, native pinch zoom, and programmatic zoom.
- Camera2 recording with H.264 video, optional AAC audio, and MP4 output.
- 1280 × 720 video, a target of 30 FPS, approximately 3 Mbps video and 128 kbps audio, and a native 30-second limit.
- Explicit camera permission requests and defensive permission checks.
- One-time permission loss handling and conditional preview restoration.
- Capture sessions, SHA-256 file hashes, and device signatures for backend verification.
- Private temporary files, recording events, and React lifecycle cleanup.

**Android only.** There is no implemented iOS backend or browser camera fallback. Browser development can render UI but cannot exercise the native camera.

## Documentation

| Guide | Contents |
| --- | --- |
| [Architecture](docs/architecture.md) | Layers, native classes, ownership, package structure |
| [Permissions](docs/permissions.md) | Explicit check/request API and four permission states |
| [Capture security](docs/capture-security.md) | Sessions, nonces, exact signature payload, verification |
| [Camera API](docs/camera-api.md) | Methods, results, zoom, events, compatibility |
| [React API](docs/react-api.md) | `VibeCamera`, `useCamera`, session handling, styling |
| [Video pipeline](docs/video-pipeline.md) | Camera2, codecs, audio, muxing, preview transitions |
| [Lifecycle](docs/lifecycle.md) | Pause/resume, permission loss, idempotent cleanup |
| [Media files](docs/media-files.md) | Preview URLs, uploads, deletion, cache management |
| [Development](docs/development.md) | Builds, packaging, dependencies, Android verification |
| [Troubleshooting](docs/troubleshooting.md) | Permission, preview, recording, proof, and build failures |

## Architecture at a glance

```text
Application / backend-issued capture session
                    |
         VibeCamera / useCamera
                    |
         Camera JavaScript API
                    |
          Capacitor: VibeCamera
                    |
          VibeCameraPlugin.kt
          /         |          \
 CameraX manager  CaptureHasher  Camera2 video recorder
 Preview + JPEG   CaptureSigner  MediaCodec + AudioRecord
                    |           MediaMuxer -> MP4
                    v
        Signed capture + temporary file
                    |
     Application upload -> backend verification
```

## Install

From a package checkout:

```bash
npm install
npm run build
npm pack
```

Install the generated `.tgz` in the consuming Capacitor app, using the actual filename printed by `npm pack`:

```bash
npm install ../vibemoments-camera/barilchaton-vibemoments-camera-0.1.21.tgz
npx cap sync android
npx cap open android
```

The filename above illustrates the supplied `0.1.21` package; use your built version. The package is scoped, so examples use `@barilchaton/vibemoments-camera`, replacing the old README's unscoped imports. See [Development](docs/development.md) for GitHub Packages configuration and release contents.

## Minimal photo flow

Call this from an explicit user action. `session` must come from your authenticated backend and be issued for a **photo** capture; the camera package does not create it.

```js
import { Camera } from '@barilchaton/vibemoments-camera'

export async function takePhoto(session) {
  let permissions = await Camera.checkPermissions()
  if (permissions.camera === 'prompt' || permissions.camera === 'denied') {
    permissions = await Camera.requestPermissions()
  }
  if (permissions.camera !== 'granted') {
    throw new Error('Enable camera access before taking a photo')
  }

  await Camera.startPreview({ lens: 'back' })
  try {
    return await Camera.capturePhoto({
      captureSessionId: session.captureSessionId,
      nonce: session.nonce
    })
  } finally {
    await Camera.stopPreview()
  }
}
```

For a camera screen, use the [React integration](docs/react-api.md). Preserve the returned proof alongside the media and verify it on the backend before publishing. A valid signature binds the file to a signing identity and challenge; it is not an AI detector or proof of physical sensor input.

## Documentation baseline

These pages consolidate the supplied 1,659-line README, the uploaded source snapshot (`0.1.21`), and the subsequent permission architecture established in the VibeMoments Camera discussion. The permission update supersedes automatic camera prompting during preview startup.

The source archive predates the final permission replacement. That replacement was reported in the discussion but its Android compilation was not confirmed. The proposed JavaScript replacement also introduced two methods without corresponding native methods in the inspected archive. [API compatibility](docs/camera-api.md#version-compatibility) and [release verification](docs/development.md#release-verification) identify these boundaries rather than treating every proposed method as a tested release feature.

## License

Private project. Copyright © VibeMoments.
