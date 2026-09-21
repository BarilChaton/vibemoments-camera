# Media files

[Documentation home](../README.md) · [Previous: Lifecycle](lifecycle.md) · [Next: Development](development.md)

## Temporary storage

Photos and videos are written to the application's private cache:

```text
<application cache>/vibemoments-camera/
```

Typical paths resemble:

```text
/data/user/0/com.example.app/cache/vibemoments-camera/photo_1786830476791.jpg
/data/user/0/com.example.app/cache/vibemoments-camera/video_1786797661925.mp4
```

Treat the returned `path` as authoritative; do not construct absolute cache paths yourself. Cache is temporary, not a gallery export or durable upload queue. The application owns permanent storage and must account for missing files when retrying later.

## Displaying files in the WebView

Convert native paths before using them as browser media URLs:

```js
import { Capacitor } from '@capacitor/core'

const webPath = Capacitor.convertFileSrc(media.path)
```

```jsx
<img src={Capacitor.convertFileSrc(photo.path)} alt="Captured Vibe" />
<video src={Capacitor.convertFileSrc(video.path)} controls playsInline />
```

A converted URL is for the local Capacitor WebView; it is not a public upload URL. Keep the native `path` for cleanup and keep all capture proof fields. Do not replace the capture object with just the converted URL.

If generating a video thumbnail in the WebView, supply the converted URL. A generated thumbnail is a separate artifact and does not inherit the original video's signed hash.

## Creating an upload `File`

```js
import { Capacitor } from '@capacitor/core'

export async function captureToFile(media) {
  const response = await fetch(Capacitor.convertFileSrc(media.path))
  if (!response.ok) throw new Error(`Unable to read capture: ${response.status}`)

  const blob = await response.blob()
  const extension = media.type === 'video' ? 'mp4' : 'jpg'
  return new File([blob], `capture-${Date.now()}.${extension}`, {
    type: media.mimeType || blob.type
  })
}
```

Wrapping the unchanged blob as a `File` preserves its bytes. Avoid re-encoding, canvas export, EXIF rewriting, or video transcoding before proof verification. This method buffers media into WebView memory; account for that in upload UX and resource usage.

## Upload and verification lifecycle

```text
Capture -> temporary file + proof -> preview / compose
                                    |
                           upload unchanged bytes
                                    |
                            backend verifies proof
                         /                         \
                     failure                    acceptance
                       |                            |
           keep for a permitted retry        delete local capture
```

The backend must verify the actual uploaded bytes, challenge, and device signature before treating the media as accepted. A successful storage upload alone is not necessarily successful publication.

```js
import { Camera } from '@barilchaton/vibemoments-camera'

export async function publishCapture(media, uploadAndVerify, reportCleanupError) {
  const file = await captureToFile(media)
  // Application helper: uploads original bytes and submits proof for verification.
  const accepted = await uploadAndVerify({ file, proof: media })

  // Cleanup failure must not turn accepted publication into a publish retry.
  try {
    await Camera.deleteCapture(media.path)
  } catch (error) {
    reportCleanupError(error)
  }
  return accepted
}
```

`uploadAndVerify` must reject when publication/verification fails and implement the application's retry policy. Preserve media and proof together for retries. Do not blindly reuse a consumed session or publish twice after a cleanup failure.

## Deleting one capture

The verified archive API is:

```js
const result = await Camera.deleteCapture(media.path)
```

Results distinguish successful deletion from an already absent file:

```js
{ deleted: true, alreadyDeleted: false }
{ deleted: false, alreadyDeleted: true }
```

The native implementation restricts deletion to the camera cache directory. It must not be used as a general filesystem deletion API. Delete when the user discards a capture or after acceptance makes the temporary original unnecessary.

The later proposed wrapper used `deleteTemporaryCapture(path)`, but the inspected native source implements `deleteCapture`. See [API compatibility](camera-api.md#version-compatibility) before changing names; they are not established aliases.

## Clearing abandoned captures

```js
const result = await Camera.clearCache()
// { deleted: 4 }
```

Use this verified archive method for maintenance when there are no active captures, uploads, or pending media that need the files. The later proposed wrapper omitted it, so preserve its bridge method if exposing it in your release.

For ordinary publishing, delete the individual accepted/discarded file. Broad cache clearing during active recording or upload can remove files still in use. Private cache storage does not guarantee persistence across OS cleanup or application reset.

---

[Previous: Lifecycle](lifecycle.md) · [Next: Development](development.md) · [Capture security](capture-security.md)
