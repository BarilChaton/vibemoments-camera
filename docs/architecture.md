# Architecture

[Documentation home](../README.md) · [Next: Permissions](permissions.md)

## Responsibility boundaries

| Layer | Owns |
| --- | --- |
| Consuming application | Permission UX, navigation, camera screen mounting, session requests, captions, upload, permanent storage, backend integration, styling |
| React package | Camera controls, UI state, hook lifecycle, event subscriptions, forwarding session data |
| JavaScript bridge | Public `Camera` methods and argument adaptation to Capacitor |
| Native Android plugin | Permission enforcement, camera ownership, native views, capture, recording, proof generation, temporary files, lifecycle |
| Backend | Device-key registration policy, authenticated challenges, expiry, replay prevention, uploaded-byte hashing, signature verification, publishing authorization |

The native package does not implement application routing, Supabase authentication, or a publishing service. Backend helpers in application examples must be supplied by the consumer.

## Package structure

```text
vibemoments-camera/
├── README.md
├── docs/                         # These linked guides
├── package.json
├── vite.config.js
├── src/
│   ├── index.js
│   ├── native/camera.js
│   ├── hooks/useCamera.js
│   └── components/VibeCamera.jsx
├── dist/vibemoments-camera.js
└── android/
    ├── build.gradle
    ├── consumer-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/vibemoments/camera/
            ├── VibeCameraPlugin.kt
            ├── VibeCameraManager.kt
            ├── VibeVideoRecorder.kt
            ├── CaptureHasher.kt
            └── CaptureSigner.kt
```

## Public layers

`Camera` is the low-level promise-based API. `VibeCameraNative` is the exported Capacitor proxy registered as `VibeCamera`; keep that export because `src/index.js` exposes it. Most consumers should use `Camera`, whose scalar arguments differ from the native object's argument objects.

`useCamera` wraps the bridge with React state and lifecycle handling. `VibeCamera` provides controls over the hook and accepts a backend-issued capture session. See the [Camera API](camera-api.md) and [React API](react-api.md).

## Native classes

### `VibeCameraPlugin.kt`

The bridge coordinates permission checks, explicit requests, preview view creation, transparent WebView layering, camera switching, capture proof generation, video events, lifecycle restoration, and temporary-file cleanup. It owns the transition between camera engines so they do not compete for the same camera.

### `VibeCameraManager.kt`

The CameraX manager initializes the camera provider, binds `Preview` and `ImageCapture`, selects the lens, captures JPEG files, and controls photo flash, torch, and zoom. `pauseForVideo()` releases CameraX ownership; restoration uses the manager's resume path after Camera2 closes.

### `VibeVideoRecorder.kt`

The Camera2 recorder owns the device and capture session, preview and encoder surfaces, H.264/AAC encoders, microphone input, timestamps, MP4 muxing, duration limit, orientation metadata, and stop/finalization cleanup. It handles camera loss and guards starting/recording/stopping transitions.

### `CaptureHasher.kt` and `CaptureSigner.kt`

The hasher streams the completed file through SHA-256. The signer maintains an Android Keystore EC signing key and an app-local device UUID, then signs a canonical challenge-and-hash payload. The private key is not returned to JavaScript. See [Capture security](capture-security.md) for the exact format and trust limits.

## Camera ownership and rendering

```text
Photo mode: CameraX -> PreviewView + ImageCapture
Video mode: Camera2 -> TextureView + MediaCodec input surface
```

Normal preview uses CameraX `PreviewView` with `FILL_CENTER`. Recording uses a `TextureView` with a configured source buffer and a transformation matrix. Both preserve aspect ratio and use center cropping, so the screen can crop the camera image without stretching it.

The native view sits behind the Capacitor WebView. React supplies controls over transparent content. Opaque backgrounds anywhere in the app shell can hide a functioning camera. See [React styling](react-api.md#preview-layering-and-styling).

## Permission architecture

```text
Application explicitly checks / requests camera permission
    -> hook checks before start
    -> native checks before preview, photo, and recording
    -> resume and CameraX restoration check again
```

A saved `previewView`, React `isActive`, or previous permission grant is not current authorization. The Android permission result is authoritative. Camera prompting belongs to onboarding or another deliberate UI action. Audio recording retains its separate microphone request path.

## Design and platform scope

Direct encoding keeps output sizes predictable without requiring an expensive compression pass after every recording. Separating the camera engine from VibeMoments permits independent testing and versioning while keeping Camera2 complexity outside the app UI.

The implementation is Android-only and relies on Android camera, codec, audio, and lifecycle APIs. An iOS backend would require a separate native implementation; it is not provided by the shared JavaScript interface.

---

[Previous: Home](../README.md) · [Next: Permissions](permissions.md) · [Video pipeline](video-pipeline.md)
