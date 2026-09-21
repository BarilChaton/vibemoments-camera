# Video pipeline

[Documentation home](../README.md) · [Previous: React API](react-api.md) · [Next: Lifecycle](lifecycle.md)

## Encoding profile

| Setting | Supplied implementation |
| --- | --- |
| Resolution | 1280 × 720 |
| Frame-rate target | 30 FPS |
| Video codec | H.264 / AVC |
| Video bitrate | 3,000,000 bits/second |
| Audio codec | AAC-LC |
| Audio bitrate | 128,000 bits/second |
| Audio sample rate | 48 kHz |
| Audio channels | Mono |
| Container | MP4 |
| Maximum recording duration | 30 seconds, enforced natively |

These are configured targets, not guarantees about every device's achieved frame rate or exact file size. Audio is optional through low-level `withAudio: false`; the supplied React hook enables audio.

The custom pipeline avoids recording unnecessarily large source videos and then waiting for an expensive compression pass. It also gives the module control over output quality, timestamps, duration, orientation, and finalization.

## Data flow

```text
Camera2 capture session
    |                       Microphone
    +-> Preview Surface         |
    |                       AudioRecord (PCM)
    v                           |
H.264 MediaCodec             AAC MediaCodec
    |                           |
    +----------> MediaMuxer <----+
                    |
               finalized MP4
                    |
              SHA-256 + signature
                    |
          videoRecordingFinished
```

Camera2 delivers frames to the preview surface and the video encoder's input surface. AudioRecord supplies PCM to the audio encoder when enabled. Encoded samples are written to the muxer with their track indices and presentation timestamps.

The muxer must have the required encoder output formats before starting its tracks. An audio-enabled run needs both video and audio format readiness; a silent run must not wait for a nonexistent audio track. Codec configuration and end-of-stream buffers require correct handling rather than being treated as ordinary media samples.

## CameraX to Camera2 transition

CameraX and the custom Camera2 recorder must not independently own the same physical camera simultaneously.

```text
CameraX preview
 -> validate permissions and video session
 -> resolve microphone permission if needed
 -> pauseForVideo(): release CameraX ownership
 -> create TextureView / SurfaceTexture / preview surface
 -> open Camera2 device and capture session
 -> start codec and audio processing
 -> recording
```

On successful completion:

```text
Stop capture and audio input
 -> drain encoders through end-of-stream
 -> finalize MP4 and release recorder resources
 -> hash and sign completed file
 -> deliver result / finished event
 -> restore CameraX only if lifecycle and permissions allow
```

Actual callbacks may coordinate restoration and result delivery separately. Neither a stop button press nor an existing output filename establishes that finalization and signing have completed. Wait for the completed result/event before uploading.

## Preview geometry and orientation

Normal photo preview uses CameraX `PreviewView` with `FILL_CENTER`. Video preview uses `TextureView`, a 1280 × 720 source buffer, and a native transformation matrix to preserve the source aspect ratio while center-cropping into the available view.

A raw `TextureView` can stretch the image to phone-screen dimensions if the transform is missing. When changing recording resolution, update the preview buffer dimensions and transform together. Preview appearance and encoded-file rotation are separate: the recorder also writes orientation metadata for playback. Test front and rear cameras in supported device orientations.

## Audio/video timestamps

The recorder coordinates video and audio presentation timestamps so both tracks share a coherent recording timeline. Audio timestamps must account for the number of PCM samples and the 48 kHz sample rate rather than arbitrary JavaScript timer timing. Timestamps within each track must remain monotonic.

If synchronization regresses, inspect native timestamps, encoder drains, and muxer ordering. A React recording timer is only a UI indicator and cannot correct muxed timestamps.

## Zoom during recording

Native pinch and `Camera.setZoomRatio()` route to the Camera2 recorder while recording. Normal preview uses CameraX zoom. Read active bounds through `getZoomState()` and test transitions at non-default zoom, since supported ranges can differ by lens and engine.

## Stop paths and concurrency

The recorder can stop through explicit `stopRecording()`, the native duration limit, app backgrounding, or an error/camera-loss path. React `isStartingRecording`, plugin `recordingStartPending`, and recorder starting/recording/stopping guards protect against overlapping sessions.

Backgrounding requests orderly stop so a valid partial recording can be finalized rather than deliberately discarded. Abrupt process death, storage failure, or unrecoverable encoder errors cannot promise a usable file. A finished event is sent only after the successful result path; failures use the recording error path.

After any stop, release Camera2 sessions/device, surfaces, audio input, encoders, muxer, and worker resources as appropriate. Restoration must wait for camera ownership to be available and must honor [permission and lifecycle guards](lifecycle.md).

## Events and secure output

Subscribe to `videoRecordingFinished` and `videoRecordingError` before recording. Automatic stops do not require a pending JavaScript stop call. Manual stop can resolve with the same completed result that is also emitted as an event, so consumers must avoid duplicate publication.

The plugin retains the recording's `captureSessionId` and nonce until finalization, signs the completed file, and clears the in-flight proof. If proof is missing or signing fails, treat the operation as failed rather than publishing an unsigned fallback. See [Capture security](capture-security.md).

---

[Previous: React API](react-api.md) · [Next: Lifecycle](lifecycle.md) · [Troubleshooting](troubleshooting.md)
