# Development and packaging

[Documentation home](../README.md) · [Previous: Media files](media-files.md) · [Next: Troubleshooting](troubleshooting.md)

## Source baseline

The uploaded package identifies itself as `@barilchaton/vibemoments-camera` version `0.1.21`, an ES module published to GitHub Packages. The old README used an unscoped name and older example versions. Use the name and actual tarball filename from the package being built.

The supplied native configuration declares minSdk 23, compileSdk/targetSdk 36, Java/Kotlin target 17, Android Gradle Plugin 8.13.0, Kotlin plugin 2.2.21, CameraX 1.5.2, and a Capacitor Android 8.5.0 dependency fallback. These are source snapshot settings, not a claim about current latest releases or every consuming app's minimum requirements. The host application's toolchain and dependencies must also be compatible.

Peer dependencies in the snapshot are `@capacitor/core`, `react`, and `react-icons`. Inspect the current `package.json` before upgrading or consuming a different release.

## Local builds

```bash
npm install
npm run dev
npm run build
```

The library output is `dist/vibemoments-camera.js`. Vite development mode is useful for UI work; it does not supply Android camera hardware in the browser. Native validation requires a built Capacitor Android app and an appropriate test device.

## Exports and library build

Keep public exports aligned with source:

```js
export { Camera, VibeCameraNative } from './native/camera'
export { useCamera } from './hooks/useCamera'
export { VibeCamera } from './components/VibeCamera'
```

The proxy declaration must remain exported:

```js
export const VibeCameraNative = registerPlugin('VibeCamera')
```

Vite builds `src/index.js` as an ES library. Shared React and Capacitor dependencies remain external to avoid duplicate runtimes. The supplied Vite configuration already externalizes React, React DOM, JSX runtimes, and Capacitor; React Icons is a peer dependency but its subpath was not explicitly externalized in that configuration.

If retaining React Icons as consumer-provided code, include the used subpath as an external, as the original README recommended:

```js
rollupOptions: {
  external: [
    'react', 'react-dom', 'react/jsx-runtime', 'react/jsx-dev-runtime',
    '@capacitor/core', 'react-icons/fi'
  ]
}
```

Review actual bundle contents after changing externals. A peer dependency declaration alone does not establish that Vite externalized every import.

## Package archive

The supplied scripts include:

```json
{
  "dev": "vite",
  "build": "vite build",
  "prepack": "npm run build"
}
```

Build and inspect the pack list:

```bash
npm pack --dry-run
npm pack
```

`prepack` rebuilds the JavaScript before packing. The scoped package produces a filename such as `barilchaton-vibemoments-camera-0.1.21.tgz`; use the emitted filename rather than an unscoped example from the old README.

## Include the linked documentation

The supplied `files` allowlist does not include `docs`. Add it so the landing README's links work in the published archive:

```json
{
  "files": [
    "dist",
    "android/build.gradle",
    "android/consumer-rules.pro",
    "android/src",
    "docs"
  ]
}
```

Required package contents include the root README, all ten `docs/*.md` pages, `package.json`, the built JavaScript, Android Gradle/consumer configuration, manifest, and native sources including `CaptureHasher.kt` and `CaptureSigner.kt`.

Do not ship generated Android output such as `android/build/`, `intermediates/`, `kotlin/`, `tmp/`, or `outputs/`, nor local caches or secrets. Inspect the pack list after changing the allowlist.

## Install in the consuming app

```bash
npm install ../vibemoments-camera/barilchaton-vibemoments-camera-0.1.21.tgz
npx cap sync android
npx cap open android
```

For a registry install, the supplied package's `publishConfig` points to `https://npm.pkg.github.com`. Consumers need the appropriate scoped registry configuration and authorized credentials. Do not embed tokens in the README or a published package. Local tarball installation does not require publishing.

For each release, increment the package version, pack again, install the new archive, synchronize Capacitor, and rebuild/relaunch the Android app. A JavaScript rebuild or WebView refresh alone does not install Kotlin changes.

Use the consuming Android project's configured Gradle build. For example, from its `android` directory on Windows:

```powershell
.\gradlew.bat assembleDebug
```

Use the tasks and build variant appropriate to the host app. A successful Vite build does not prove the Kotlin plugin compiles.

## Release verification

These docs include the later explicit permission design. The uploaded archive predates that final native replacement, and the discussion reported that the replacement could not be compiled in its environment. This documentation task has not built or modified the camera implementation.

Before publishing an updated package:

1. Reconcile the [wrapper/native compatibility table](camera-api.md#version-compatibility), preserving existing exports and methods unless deliberately versioning a breaking change.
2. Ensure `checkPermissions()`, `requestPermissions()`, and the permission event exist across the native/JavaScript boundary before enabling the updated hook.
3. Compile the JavaScript and consuming Android app, checking Capacitor method signatures for the installed version.
4. Test fresh permission prompts, denial, repeated denial, settings recovery, one-time grant expiry, and resume without access on a physical Android device.
5. Test photos and videos with valid sessions, mode changes, missing proof, and backend rejection of changed bytes or reused sessions.
6. Test manual stop, the 30-second limit, background stop, camera switching, pinch/programmatic zoom, orientation, and recording errors.
7. Verify single preview teardown, late-start cleanup, event-listener removal, and no duplicate publication from stop result plus event.
8. Inspect the final archive for Android sources, signing helpers, all documentation, and working relative links.

The source hook also needs integration review for asynchronous listener teardown and permission-event state invalidation; these are documented in [Lifecycle](lifecycle.md) and [React API](react-api.md).

## Support scope

Android is the implemented target. iOS would require a separate native backend, likely using platform camera APIs; no iOS implementation is included. Do not treat a successful browser UI render as a native camera test.

---

[Previous: Media files](media-files.md) · [Next: Troubleshooting](troubleshooting.md) · [Architecture](architecture.md)
