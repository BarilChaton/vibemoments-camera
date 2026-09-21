# Lifecycle

[Documentation home](../README.md) · [Previous: Video pipeline](video-pipeline.md) · [Next: Media files](media-files.md)

## One owner per camera screen

The application owns screen visibility and routing. `useCamera` owns native preview cleanup; the component may call the hook's idempotent `stop()`. Avoid independent low-level teardown calls scattered across navigation, component, and hook effects.

```text
App mounts camera screen
 -> hook checks permission and starts preview
 -> native owns camera resources
 -> app unmounts screen
 -> hook releases preview once
```

The native layer also owns device lifecycle and recording finalization. React state cannot be used as evidence that hardware access or permission still exists.

## Idempotent preview stop

The supplied hook uses `activeRef` and `stoppingRef`:

1. Return if inactive or already stopping.
2. Set `stoppingRef` and mark `activeRef` false **before** awaiting native cleanup.
3. Call `Camera.stopPreview()`.
4. Reset UI state and release the stopping flag.

The unmount cleanup observes the same active flag. This prevents `VibeCamera` cleanup and hook cleanup from sending the duplicate native stop that appeared in the original integration.

Do not use an effect dependent on `isActive` whose cleanup always stops preview: changing `isActive` to false can itself invoke the old cleanup and stop twice.

Idempotent stop does not automatically solve all startup races. If a screen unmounts while `startPreview()` is still pending, the owner must ensure a late successful start is released. Serialize starts/stops and test rapid navigation and React development remounts rather than assuming `activeRef` is a pending-start lock.

## Pause and background recording

`handleOnPause()` marks the app paused and requests `stopIfRecording()` on the recorder. The intended path is:

```text
Background
 -> stop Camera2 capture and audio input
 -> drain H.264 / AAC through EOS
 -> finalize MP4
 -> release resources
 -> deliver signed partial capture if finalization succeeds
```

This is not background recording support: recording stops when the application backgrounds. A valid partial recording is retained when orderly finalization succeeds. Process termination or unrecoverable native failures may prevent completion.

Do not immediately remove the only completion listener if the app still needs to receive the partial recording. A screen-local listener disappears with its owner; applications requiring longer-lived delivery need an appropriately scoped controller. Durable recovery across process death is not established by the supplied hook.

## Resume and one-time permission loss

The agreed permission update adds a check at both `handleOnResume()` and `restoreCameraXPreview()`.

| Situation | Required behavior |
| --- | --- |
| Permission granted, screen owns preview, app active | Restore preview when Camera2 has released ownership |
| Permission absent | Cancel restore, clean stale camera state, emit permission change |
| Camera screen closed | Do not recreate preview behind another screen |
| Recording finalizes while app paused | Defer restoration until an eligible resume |
| App process restarted | Initialize anew and query current permissions |

“Allow only this time” is `granted` while active and may be revoked after leaving the foreground. A retained `PreviewView` does not mean access survived. Guard permission before an “already active” response as well as before actual binding.

The permission-loss cleanup must clear stale recording/start/restore state, release resources, and inform JavaScript. The UI must stop advertising an active camera and let the user deliberately request access or visit settings. Restoration must never implicitly request permission.

See [Permissions](permissions.md) for the `blocked` classification caveat and the exact event helper.

## Asynchronous listener cleanup

Listener registration returns promises. Cleanup must handle unmount before those promises settle. A custom controller can use this pattern:

```jsx
useEffect(() => {
  let disposed = false
  const handles = []

  async function register(createListener) {
    try {
      const handle = await createListener()
      if (disposed) await handle.remove()
      else handles.push(handle)
    } catch (error) {
      if (!disposed) reportError(error)
    }
  }

  register(() => Camera.addVideoRecordingFinishedListener((video) => {
    if (!disposed) handleVideo(video)
  }))
  register(() => Camera.addVideoRecordingErrorListener((event) => {
    if (!disposed) reportError(new Error(event.message || 'Recording failed'))
  }))

  return () => {
    disposed = true
    for (const handle of handles) handle.remove().catch(console.error)
  }
}, [handleVideo, reportError])
```

Supply stable callbacks and import `useEffect` and `Camera`. This is an integration-hardening pattern; the supplied hook uses simpler registration and optional cleanup and should be reviewed for this race. Do not add a second video-handling effect alongside the hook unless duplicate delivery is intentional and deduplicated.

## Integration checks

- Rapidly open and close during startup: no camera remains behind the closed screen.
- Close after capture: only one native stop is issued.
- Background during recording: finalization completes or reports failure, and recording does not continue indefinitely.
- Resume after one-time access expires: no unauthorized CameraX restoration.
- Re-enable access in settings: a fresh check and deliberate start succeed.
- Stop at 30 seconds: completion reaches the active listener and does not publish twice.
- Switch lenses and record repeatedly: CameraX and Camera2 release ownership between transitions.

These checks verify integration behavior; the supplied conversation did not establish that the final permission replacement passed a native build or this entire matrix.

---

[Previous: Video pipeline](video-pipeline.md) · [Next: Media files](media-files.md) · [Development](development.md)
