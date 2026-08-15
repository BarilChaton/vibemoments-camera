# VibeMoments Camera

A native Android camera engine and React camera interface built for **VibeMoments**.

`vibemoments-camera` provides a reusable camera layer for Capacitor applications, combining:

- Native Android camera functionality written in Kotlin
- CameraX for photo capture and normal preview
- Camera2 + MediaCodec for video recording
- H.264 video encoding
- AAC audio encoding
- React hooks and components
- Capacitor integration
- Temporary capture management
- Native camera lifecycle handling

The package was originally created for VibeMoments, but its camera engine is kept separate from the main application so it can be developed, tested, packaged, and versioned independently.

---

## Features

### Camera

- Native Android camera preview
- Front and rear camera switching
- Photo capture
- Video recording
- Flash modes
- Torch control
- Camera capability detection
- Automatic lifecycle handling
- Background recording handling
- Temporary capture cleanup

### Photo capture

Photos are captured using Android CameraX and stored temporarily in the application's private cache directory.

### Video recording

Video recording uses a custom Camera2 pipeline with:

- H.264 / AVC video
- AAC audio
- 1280×720 recording
- 30 FPS
- ~3 Mbps video bitrate
- ~128 kbps audio bitrate
- MP4 output
- Correct device orientation
- Front and rear camera support
- Synchronized audio/video timestamps
- 30-second hard recording limit
- Aspect-ratio-correct recording preview

### React

The package provides three main APIs:

- `Camera`
- `useCamera`
- `VibeCamera`

This allows applications to use either the low-level native API, the React hook, or the included React camera interface.

---

## Architecture

```text
React application
       │
       ▼
VibeCamera / useCamera
       │
       ▼
Camera JavaScript API
       │
       ▼
Capacitor bridge
       │
       ▼
VibeCameraPlugin.kt
       │
       ├── VibeCameraManager.kt
       │       │
       │       └── CameraX
       │           ├── Preview
       │           └── Photo capture
       │
       └── VibeVideoRecorder.kt
               │
               ├── Camera2
               ├── MediaCodec H.264
               ├── MediaCodec AAC
               ├── AudioRecord
               └── MediaMuxer
                       │
                       ▼
                      MP4
```

---

## Package structure

```text
vibemoments-camera/
│
├── android/
│   ├── build.gradle
│   ├── consumer-rules.pro
│   │
│   └── src/main/
│       ├── AndroidManifest.xml
│       │
│       └── java/com/vibemoments/camera/
│           ├── VibeCameraManager.kt
│           ├── VibeCameraPlugin.kt
│           └── VibeVideoRecorder.kt
│
├── src/
│   ├── components/
│   │   └── VibeCamera.jsx
│   │
│   ├── hooks/
│   │   └── useCamera.js
│   │
│   ├── native/
│   │   └── camera.js
│   │
│   └── index.js
│
├── dist/
│   └── vibemoments-camera.js
│
├── package.json
├── vite.config.js
└── README.md
```

---

## Installation

### Build the package

```bash
npm run build
```

### Create an npm package archive

```bash
npm pack
```

This creates a package such as:

```text
vibemoments-camera-0.1.0.tgz
```

### Install it in a Capacitor application

```bash
npm install ../vibemoments-camera/vibemoments-camera-0.1.0.tgz
```

Then synchronize Capacitor:

```bash
npx cap sync android
```

Open the Android project if needed:

```bash
npx cap open android
```

---

## Package exports

```js
import { Camera, useCamera, VibeCamera } from 'vibemoments-camera'
```

### `Camera`

Low-level JavaScript API for communicating directly with the native Capacitor plugin.

### `useCamera`

React hook that manages camera state and wraps the native API.

### `VibeCamera`

Ready-to-use React camera interface built on top of `useCamera`.

---

## Basic React usage

```jsx
import { VibeCamera } from 'vibemoments-camera'

function CameraScreen() {
  const handleCapture = (media) => {
    console.log('Captured:', media)
  }

  const handleError = (error) => {
    console.error('Camera error:', error)
  }

  const handleClose = () => {
    console.log('Close requested')
  }

  return <VibeCamera autoStart onCapture={handleCapture} onError={handleError} onClose={handleClose} />
}
```

The consuming application should normally control whether the camera screen is mounted.

Example:

```jsx
import { useState } from 'react'
import { VibeCamera } from 'vibemoments-camera'

function CreateScreen() {
  const [cameraOpen, setCameraOpen] = useState(false)

  const handleCapture = (media) => {
    console.log(media)
    setCameraOpen(false)
  }

  return (
    <>
      {!cameraOpen && (
        <button type="button" onClick={() => setCameraOpen(true)}>
          Open Camera
        </button>
      )}

      {cameraOpen && <VibeCamera autoStart onCapture={handleCapture} onError={console.error} onClose={() => setCameraOpen(false)} />}
    </>
  )
}
```

This keeps navigation and screen ownership inside the consuming application while `VibeCamera` manages camera functionality.

---

## Using `useCamera`

For a fully custom camera UI:

```jsx
import { useCamera } from 'vibemoments-camera'

function CustomCamera() {
  const {
    isActive,
    isCapturing,
    isRecording,
    isStartingRecording,
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

  return (
    <div>
      <button type="button" onClick={start}>
        Start camera
      </button>

      <button type="button" onClick={capturePhoto}>
        Take photo
      </button>

      <button type="button" onClick={switchCamera}>
        Switch camera
      </button>

      {!isRecording ? (
        <button type="button" disabled={isStartingRecording} onClick={startRecording}>
          Record
        </button>
      ) : (
        <button type="button" onClick={stopRecording}>
          Stop
        </button>
      )}

      <button type="button" onClick={stop}>
        Stop camera
      </button>
    </div>
  )
}
```

---

# Low-level Camera API

```js
import { Camera } from 'vibemoments-camera'
```

---

## Start preview

```js
await Camera.startPreview()
```

An options object may also be supplied:

```js
await Camera.startPreview({
  lens: 'back'
})
```

---

## Stop preview

```js
await Camera.stopPreview()
```

---

## Capture photo

```js
const photo = await Camera.capturePhoto()
```

Example result:

```js
{
  type: 'photo',
  path: '/data/user/0/com.example.app/cache/vibemoments-camera/photo_123456.jpg',
  mimeType: 'image/jpeg',
  lens: 'back'
}
```

The returned `path` is a native Android filesystem path.

---

## Switch camera

```js
const result = await Camera.switchCamera()
```

Example:

```js
{
  lens: 'front'
}
```

Possible lens values:

```text
front
back
```

---

## Camera state

```js
const state = await Camera.getCameraState()
```

Example:

```js
{
  active: true,
  lens: 'back'
}
```

---

## Torch

Enable:

```js
await Camera.setTorch(true)
```

Disable:

```js
await Camera.setTorch(false)
```

Torch availability depends on the active camera.

---

## Flash mode

```js
await Camera.setFlashMode('auto')
```

Supported modes:

```text
off
auto
on
```

Examples:

```js
await Camera.setFlashMode('off')
await Camera.setFlashMode('auto')
await Camera.setFlashMode('on')
```

---

## Camera capabilities

```js
const capabilities = await Camera.getCapabilities()
```

Example:

```js
{
  hasFlash: true,
  lens: 'back'
}
```

---

# Video recording

Start recording:

```js
await Camera.startRecording({
  withAudio: true
})
```

Stop recording:

```js
const video = await Camera.stopRecording()
```

Example result:

```js
{
  type: 'video',
  path: '/data/user/0/com.example.app/cache/vibemoments-camera/video_123456.mp4',
  mimeType: 'video/mp4',
  durationMs: 5421,
  videoBitrate: 3000000,
  audioBitrate: 128000,
  lens: 'back'
}
```

The native recorder automatically stops when the 30-second limit is reached.

---

## Recording settings

```text
Resolution:        1280 × 720
Frame rate:        30 FPS
Video codec:       H.264 / AVC
Video bitrate:     ~3 Mbps
Audio codec:       AAC-LC
Audio bitrate:     ~128 kbps
Audio sample rate: 48 kHz
Audio channels:    Mono
Container:         MP4
Maximum duration:  30 seconds
```

These settings are intended to provide good visual quality on phone screens while keeping uploads reasonably small.

---

# Video events

## Recording finished

```js
const listener = await Camera.addVideoRecordingFinishedListener((video) => {
  console.log('Video finished:', video)
})
```

Remove the listener:

```js
await listener.remove()
```

---

## Recording error

```js
const listener = await Camera.addVideoRecordingErrorListener((error) => {
  console.error('Recording failed:', error)
})
```

Remove it:

```js
await listener.remove()
```

---

# Native video pipeline

Video recording uses a custom native pipeline instead of relying on the device camera application's recording settings.

```text
Camera2
   │
   ├──────────────► Preview Surface
   │
   ▼
H.264 MediaCodec
   │
   │
   │       Microphone
   │           │
   │           ▼
   │      AudioRecord
   │           │
   │           ▼
   │      AAC MediaCodec
   │           │
   ▼           ▼
      MediaMuxer
          │
          ▼
         MP4
```

This gives the package direct control over:

- Resolution
- Frame rate
- Video bitrate
- Audio bitrate
- Recording duration
- Audio/video timestamps
- Preview behaviour
- MP4 finalization

One of the reasons for using this approach is to avoid recording unnecessarily large high-resolution video and then requiring a slow compression step afterward.

---

# Photo preview

Normal camera preview uses CameraX:

```text
CameraX
    ↓
PreviewView
```

The preview uses:

```text
FILL_CENTER
```

This preserves the camera image's aspect ratio while filling the available screen area using center cropping.

---

# Video preview

Recording temporarily switches from CameraX to Camera2.

```text
Camera2
    ↓
SurfaceTexture
    ↓
TextureView
```

A raw Android `TextureView` can otherwise stretch a camera stream to match the physical dimensions of the phone display.

`vibemoments-camera` therefore:

- Configures the preview buffer size
- Preserves the 16:9 camera aspect ratio
- Applies a native transformation matrix
- Uses center-crop behaviour
- Prevents elongated or stretched objects

The current recording preview buffer is:

```text
1280 × 720
```

---

# Switching from photo to video

CameraX and Camera2 cannot independently own the same physical camera at the same time.

Starting video therefore performs the following transition:

```text
CameraX preview
      │
      ▼
pauseForVideo()
      │
      ▼
CameraX releases camera
      │
      ▼
Create Camera2 preview
      │
      ▼
Open Camera2
      │
      ▼
Start H.264 + AAC recording
```

When recording finishes:

```text
Finalize MP4
      │
      ▼
Close Camera2
      │
      ▼
Remove video TextureView
      │
      ▼
Resume CameraX
      │
      ▼
Photo preview restored
```

---

# Application lifecycle

Android can remove camera access when an application enters the background.

The plugin automatically handles this.

When the app enters the background while recording:

```text
Application backgrounded
           │
           ▼
handleOnPause()
           │
           ▼
stopIfRecording()
           │
           ▼
Stop Camera2 capture
           │
           ▼
Finish H.264 stream
           │
           ▼
Finish AAC stream
           │
           ▼
Finalize MP4
           │
           ▼
Release camera resources
```

The partial recording is preserved rather than discarded.

When the application returns to the foreground, CameraX can restore the preview.

---

# Duplicate recording protection

The package prevents multiple recording sessions from being started at the same time.

Protection exists at several levels:

```text
React UI
   │
   ▼
isStartingRecording
   │
   ▼
VibeCameraPlugin
   │
   ▼
recordingStartPending
   │
   ▼
VibeVideoRecorder
   │
   ▼
starting / recording / stopping
```

This protects against rapid taps and duplicate Capacitor calls.

---

# Temporary capture storage

Captured photos and videos are stored temporarily in:

```text
<application cache>/vibemoments-camera/
```

Example:

```text
/data/user/0/com.example.app/cache/vibemoments-camera/
```

Example files:

```text
photo_1786830476791.jpg
video_1786797661925.mp4
```

These are application-private temporary files.

---

# Deleting a capture

Use:

```js
await Camera.deleteCapture(media.path)
```

Example result:

```js
{
  deleted: true,
  alreadyDeleted: false
}
```

If the file is already gone:

```js
{
  deleted: false,
  alreadyDeleted: true
}
```

The native implementation only permits deletion of files located inside the `vibemoments-camera` cache directory.

This prevents JavaScript callers from using the method to delete arbitrary application files.

---

# Clearing the cache

```js
const result = await Camera.clearCache()
```

Example:

```js
{
  deleted: 4
}
```

`clearCache()` removes temporary camera files from the package cache directory.

It is intended mainly for maintenance and abandoned captures.

For normal media publishing flows, prefer deleting individual captures after they are no longer required.

---

# Displaying native files in Capacitor

A capture result contains a native Android filesystem path.

This path should be converted before using it inside the WebView.

```js
import { Capacitor } from '@capacitor/core'

const webPath = Capacitor.convertFileSrc(media.path)
```

Photo example:

```jsx
<img src={Capacitor.convertFileSrc(photo.path)} alt="Captured" />
```

Video example:

```jsx
<video src={Capacitor.convertFileSrc(video.path)} controls playsInline />
```

---

# Creating a File for upload

The converted URL can be fetched and converted into a JavaScript `File`.

```js
import { Capacitor } from '@capacitor/core'

const webPath = Capacitor.convertFileSrc(media.path)

const response = await fetch(webPath)

const blob = await response.blob()

const extension = media.type === 'video' ? 'mp4' : 'jpeg'

const file = new File([blob], `capture-${Date.now()}.${extension}`, {
  type: blob.type || media.mimeType
})
```

The resulting file can be uploaded to services such as Supabase Storage.

---

# Recommended upload lifecycle

```text
Capture media
     │
     ▼
Temporary native file
     │
     ▼
Preview / compose
     │
     ▼
Upload
     │
     ├── Failed
     │     │
     │     └── Keep temporary file
     │
     └── Successful
           │
           ▼
Camera.deleteCapture()
```

Example:

```js
try {
  await uploadMedia(file)

  await Camera.deleteCapture(media.path)
} catch (error) {
  console.error(error)

  // Keep the temporary file so the upload
  // can potentially be retried.
}
```

---

# Example VibeMoments integration

```jsx
import { useState } from 'react'
import { Capacitor } from '@capacitor/core'
import { Camera, VibeCamera } from 'vibemoments-camera'

function CreateVibe() {
  const [cameraOpen, setCameraOpen] = useState(false)

  const [media, setMedia] = useState(null)

  const handleCapture = (capture) => {
    const webPath = Capacitor.convertFileSrc(capture.path)

    setMedia({
      ...capture,
      webPath
    })

    setCameraOpen(false)
  }

  const removeMedia = async () => {
    if (media?.path) {
      await Camera.deleteCapture(media.path)
    }

    setMedia(null)
  }

  return (
    <>
      {!cameraOpen && !media && (
        <button type="button" onClick={() => setCameraOpen(true)}>
          Open Camera
        </button>
      )}

      {cameraOpen && <VibeCamera autoStart onCapture={handleCapture} onError={console.error} onClose={() => setCameraOpen(false)} />}

      {media?.type === 'photo' && <img src={media.webPath} alt="Captured Vibe" />}

      {media?.type === 'video' && <video src={media.webPath} controls playsInline />}

      {media && (
        <button type="button" onClick={removeMedia}>
          Remove
        </button>
      )}
    </>
  )
}
```

---

# Preview layering

The native camera preview is inserted behind the Capacitor WebView.

Conceptually:

```text
┌──────────────────────────────┐
│ Capacitor WebView            │
│                              │
│ React camera controls        │
│                              │
│ Transparent content area     │
├──────────────────────────────┤
│ Native camera preview        │
└──────────────────────────────┘
```

Because of this, the application's camera screen must not render an opaque background over the preview.

A camera container should normally be transparent:

```jsx
<div className="bg-transparent">
```

rather than:

```jsx
<div className="bg-black">
```

if that background would cover the native camera layer.

---

# Application shell integration

If the consuming application has an opaque global background, temporarily make it transparent while the camera is open.

Example:

```jsx
<main
  className={`flex h-dvh flex-col ${
    cameraOpen
      ? 'bg-transparent'
      : 'bg-vibe-bg'
  }`}>
```

Application navigation can also be hidden while capturing:

```jsx
{
  !cameraOpen && <BottomNavigation />
}
```

Recommended ownership:

```text
VibeCamera
    │
    └── Handles camera functionality

Application
    │
    ├── Opens camera
    ├── Closes camera
    ├── Controls navigation
    └── Controls page backgrounds
```

The camera package should not control application routing.

---

# Camera close behaviour

A recommended pattern is:

```jsx
<VibeCamera onClose={() => setCameraOpen(false)} />
```

When `cameraOpen` becomes false, the camera component unmounts and its cleanup lifecycle releases the native preview.

Avoid independently stopping the same native preview from multiple places at the same time, as duplicate teardown calls can cause race conditions.

---

# Styling

The React camera UI can use the styling system provided by the consuming application.

`vibemoments-camera` itself does not need Tailwind as a runtime dependency.

If the component contains Tailwind utility classes, the consuming application's Tailwind compiler must be able to discover them.

For Tailwind setups using explicit source declarations, something similar may be required:

```css
@import 'tailwindcss';

@source "../node_modules/vibemoments-camera/dist";
```

The exact relative path depends on the consuming application's project structure.

---

# React dependencies

React should be provided by the consuming application.

It should be listed as a peer dependency rather than bundled into `vibemoments-camera`.

The same principle applies to shared frontend dependencies such as Capacitor and React Icons.

This prevents multiple React runtimes from being bundled into the same application.

---

# Android permissions

Camera permission:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

Microphone permission:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

The Capacitor plugin requests the required permissions when necessary.

---

# Native classes

## `VibeCameraManager.kt`

Responsible for CameraX.

It handles:

- Camera provider initialization
- Photo preview
- ImageCapture
- Camera selection
- Front/rear switching
- Torch
- Flash mode
- CameraX pause before recording
- CameraX restoration after recording

---

## `VibeCameraPlugin.kt`

The Capacitor bridge between React/JavaScript and Android.

It handles:

- Camera permissions
- Microphone permissions
- Native view creation
- Preview layering
- CameraX startup
- Photo capture calls
- Camera switching
- Torch and flash
- Camera2 video preview
- Video recording calls
- Native recording events
- App lifecycle
- Camera restoration
- Temporary capture cleanup

---

## `VibeVideoRecorder.kt`

The native Camera2 recording engine.

It handles:

- Camera2 device selection
- Camera2 sessions
- H.264 MediaCodec
- AAC MediaCodec
- AudioRecord
- Audio/video timestamp synchronization
- MediaMuxer
- MP4 creation
- Orientation metadata
- Recording duration
- 30-second automatic stop
- Encoder end-of-stream handling
- Resource cleanup
- Camera loss handling

---

# Development

Install dependencies:

```bash
npm install
```

Run development mode:

```bash
npm run dev
```

Build:

```bash
npm run build
```

The JavaScript package output is generated at:

```text
dist/vibemoments-camera.js
```

---

# Vite configuration

The library is built as an ES module.

Shared dependencies should remain external.

Example:

```js
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],

  build: {
    lib: {
      entry: 'src/index.js',
      name: 'VibeMomentsCamera',
      formats: ['es'],
      fileName: 'vibemoments-camera'
    },

    rollupOptions: {
      external: ['react', 'react-dom', 'react/jsx-runtime', 'react/jsx-dev-runtime', '@capacitor/core', 'react-icons/fi']
    }
  }
})
```

---

# Creating an npm package

`package.json` can use:

```json
{
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "prepack": "npm run build"
  }
}
```

Then simply run:

```bash
npm pack
```

The package is rebuilt before the `.tgz` archive is created.

---

# Package contents

The npm package should contain only the required source and distribution files.

Example:

```text
android/build.gradle
android/consumer-rules.pro
android/src/main/AndroidManifest.xml
android/src/main/java/com/vibemoments/camera/VibeCameraManager.kt
android/src/main/java/com/vibemoments/camera/VibeCameraPlugin.kt
android/src/main/java/com/vibemoments/camera/VibeVideoRecorder.kt
dist/vibemoments-camera.js
package.json
README.md
```

Do not package generated Android build output such as:

```text
android/build/
android/build/intermediates/
android/build/kotlin/
android/build/tmp/
android/build/outputs/
```

---

# Updating the package

Increment the package version:

```json
{
  "version": "0.1.4"
}
```

Then:

```bash
npm pack
```

Install the new tarball in the consuming application:

```bash
npm install ../vibemoments-camera/vibemoments-camera-0.1.4.tgz
```

Synchronize Capacitor:

```bash
npx cap sync android
```

Native Kotlin changes require rebuilding and relaunching the Android application.

---

# Troubleshooting

## Camera preview is black

Check:

- Camera permission is granted
- CameraX successfully bound the camera
- The WebView is transparent
- The application shell is transparent while the camera is open
- No opaque React element is covering the native preview

Useful Logcat messages:

```text
Binding camera lens: back
Camera bound successfully: back
Camera preview ready
```

---

## Photo is captured but does not display

The native path cannot normally be used directly by the WebView.

Convert it:

```js
const webPath = Capacitor.convertFileSrc(capture.path)
```

Then:

```jsx
<img src={webPath} alt="Captured" />
```

---

## Video cannot be loaded

Convert its native path before using it:

```js
const webPath = Capacitor.convertFileSrc(video.path)
```

Then:

```jsx
<video src={webPath} playsInline />
```

---

## Video thumbnail creation fails

Make sure the thumbnail generator receives the converted WebView URL rather than the raw Android filesystem path.

Correct:

```js
createVideoThumbnail(Capacitor.convertFileSrc(capture.path))
```

---

## Recording preview looks stretched

The Camera2 recording preview uses a 1280×720 source buffer.

If recording resolution is changed, update the video preview buffer and transformation logic accordingly.

A mismatch between:

```text
recording resolution
```

and:

```text
TextureView source dimensions
```

can produce distorted previews.

---

## Recording does not start

Check Logcat for:

```text
VibeVideoRecorder
VibeCamera
```

Expected sequence:

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

---

## `Recording already starting or in progress`

A second start request was received while the recorder was already busy.

The UI should respect:

```js
isStartingRecording
```

and:

```js
isRecording
```

when enabling or disabling recording controls.

---

## Camera error after minimizing app

The plugin is designed to stop recording when the app enters the background.

Expected behaviour:

```text
Stopping recording because app entered background
Stopping video recording
Video encoder EOS reached
Audio encoder EOS reached
Finalizing video
Video saved
```

If Android reports a camera error before this happens, verify that the Capacitor plugin lifecycle callbacks are still active.

---

## Camera does not restore after video

Expected transition:

```text
CameraX preview
→ Camera2 recording
→ CameraX preview
```

Check Logcat for:

```text
CameraX preview restored
```

---

## Closing camera produces a blank screen

Let the consuming application own the camera screen state.

Recommended:

```jsx
<VibeCamera onClose={() => setCameraOpen(false)} />
```

Avoid manually tearing down the native preview multiple times from different components.

---

# Design philosophy

`vibemoments-camera` separates responsibilities between three layers.

## Native layer

Responsible for:

- Camera hardware
- CameraX
- Camera2
- Preview surfaces
- Photo capture
- Video encoding
- Audio recording
- MP4 muxing
- Device lifecycle
- Native temporary files

## React package

Responsible for:

- Camera state
- Camera controls
- React lifecycle
- Communicating with Capacitor
- Reusable camera UI

## Consuming application

Responsible for:

- Navigation
- Opening and closing the camera
- Media composition
- Captions
- Permanent storage
- Uploading
- Backend integration
- Application-specific limits
- Application styling

This keeps the camera engine independent from the application that uses it.

---

# Current status

| Feature                            | Status             |
| ---------------------------------- | ------------------ |
| Native Android preview             | ✅                 |
| Rear camera                        | ✅                 |
| Front camera                       | ✅                 |
| Photo capture                      | ✅                 |
| Video recording                    | ✅                 |
| Microphone audio                   | ✅                 |
| H.264 encoding                     | ✅                 |
| AAC encoding                       | ✅                 |
| MP4 muxing                         | ✅                 |
| 720p video                         | ✅                 |
| 30 FPS                             | ✅                 |
| ~3 Mbps video bitrate              | ✅                 |
| ~128 kbps audio bitrate            | ✅                 |
| 30-second recording limit          | ✅                 |
| Correct video orientation          | ✅                 |
| Audio/video synchronization        | ✅                 |
| Aspect-ratio-correct photo preview | ✅                 |
| Aspect-ratio-correct video preview | ✅                 |
| Torch                              | ✅                 |
| Flash modes                        | ✅                 |
| Camera switching                   | ✅                 |
| Duplicate recording protection     | ✅                 |
| Background recording cleanup       | ✅                 |
| Camera restoration after recording | ✅                 |
| Temporary file cleanup             | ✅                 |
| React hook                         | ✅                 |
| React camera UI                    | ✅                 |
| Capacitor Android integration      | ✅                 |
| iOS                                | ❌ Not implemented |

---

# Android support

`vibemoments-camera` currently supports Android only.

The native implementation depends on:

- CameraX
- Camera2
- MediaCodec
- MediaMuxer
- AudioRecord
- Android Lifecycle APIs

An eventual iOS implementation would require a separate native backend, most likely based on AVFoundation.

The JavaScript API could remain largely unchanged so consuming React code would not need to know which native backend is being used.

---

# Built for VibeMoments

`vibemoments-camera` was created as the native camera engine for **VibeMoments**.

The goal is to provide fast, predictable photo and video capture while giving VibeMoments direct control over media quality.

In particular, the custom video recorder avoids producing unnecessarily large camera files and then forcing users to wait for expensive post-recording compression before uploading a Vibe.

Keeping the camera engine as a standalone package also allows it to:

- Be tested independently
- Be versioned independently
- Be reused by other parts of the project
- Keep native camera complexity out of the main application
- Evolve without tightly coupling Camera2 logic to the VibeMoments UI

---

## License

Private project.

Copyright © VibeMoments.
