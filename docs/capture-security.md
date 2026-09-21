# Capture security

[Documentation home](../README.md) · [Previous: Permissions](permissions.md) · [Next: Camera API](camera-api.md)

## Capture provenance

The architecture binds a backend-issued challenge to the exact file produced by native capture:

```text
Register device public key under authenticated backend policy
    -> backend issues captureSessionId + nonce for photo or video
    -> native captures media
    -> finalize file
    -> SHA-256 file bytes
    -> device key signs canonical proof
    -> upload unchanged media and proof
    -> backend verifies and consumes session
    -> publish
```

JavaScript validation improves UX, but the backend is the enforcement boundary. This module produces proof; it does not contain the application's backend verifier or guarantee that a deployed backend performs every check described here.

## Device identity

```js
const identity = await Camera.getCaptureIdentity()
// {
//   deviceId: '<app-local UUID>',
//   publicKey: '<base64-encoded public key>',
//   algorithm: 'ECDSA_P256_SHA256',
//   proofVersion: 'vibemoments-capture-v1'
// }
```

`CaptureSigner.kt` creates an EC P-256 (`secp256r1`) key in `AndroidKeyStore` under alias `vibemoments_capture_key_v1`. It stores a generated device UUID in app preferences `vibemoments_camera_security`, under `capture_device_id`.

`deviceId` is an app-local identifier, not a hardware serial number. The public key is Base64 without wrapping over the encoded public key (SubjectPublicKeyInfo). JavaScript receives no private key. Do not assume every Android Keystore key is hardware-backed or attested; this source does not implement key attestation.

The authenticated backend must associate the public key with the permitted user/device identity. Never allow an upload to replace an already trusted key just by including a new public key. Reinstallation, app data reset, or unavailable keys require an explicit registration/recovery policy.

## Capture sessions and nonces

The application obtains a fresh session from its backend before capture. Sessions should be bound to the authenticated user, expected device/key, media type, expiry, and single-use policy. Generate nonces with a cryptographically secure random source on the backend. Their purpose is freshness and replay binding; they are not a substitute for authentication.

```js
const photo = await Camera.capturePhoto({
  captureSessionId: session.captureSessionId,
  nonce: session.nonce
})

// Use a separately issued video session for recording:
await Camera.startRecording({
  withAudio: true,
  captureSessionId: videoSession.captureSessionId,
  nonce: videoSession.nonce
})
```

Native rejects blank or missing session fields. It does not itself validate session ownership, expiry, or backend consumption status. The supplied source reports `Missing capture session proof` for absent fields.

When the React UI changes from photo to video, request a session for the new type and set `sessionUpdating` until it is ready. Reusing a photo session for video caused a real publishing mismatch in the original integration. See [React session handling](react-api.md#capture-session-and-mode-changes).

## Returned proof

Both photos and completed videos include:

| Field | Meaning |
| --- | --- |
| `type` | `photo` or `video` |
| `path` | Temporary native file path |
| `mimeType` | `image/jpeg` or `video/mp4` |
| `lens` | `front` or `back` |
| `sha256` | Lowercase hexadecimal SHA-256 of the completed file |
| `captureSessionId` | Challenge session supplied to native |
| `nonce` | Challenge nonce supplied to native |
| `deviceId` | Signing identity's app-local UUID |
| `captureSignature` | Base64 ECDSA signature |
| `proofVersion` | `vibemoments-capture-v1` |
| `signatureAlgorithm` | `ECDSA_P256_SHA256` |

Video adds `durationMs`, `videoBitrate`, and `audioBitrate`. Identity responses use `algorithm`; capture results use `signatureAlgorithm`.

## Exact signed payload

The version-1 signer creates these six lines in this exact order, joined by one LF (`\n`) and **without a trailing newline**:

```text
vibemoments-capture-v1
<captureSessionId>
<nonce>
<mediaType>
<lowercase-sha256>
<deviceId>
```

The payload is encoded as UTF-8 and signed with Java `SHA256withECDSA`. `mediaType` is `photo` or `video`, derived from capture `type`, not the MIME type. SHA-256 of the file and the signature's SHA-256 digest of the payload are distinct operations.

The native signature is Base64 over the Java ECDSA signature encoding (DER). Verifiers must use matching encoding or explicitly convert it if their cryptography API expects fixed-width `r || s`. Do not assume a Web Crypto verifier accepts DER directly.

Payload reconstruction:

```js
function capturePayload(proof) {
  return [
    'vibemoments-capture-v1',
    proof.captureSessionId,
    proof.nonce,
    proof.type,
    proof.sha256.toLowerCase(),
    proof.deviceId
  ].join('\n')
}
```

This helper only reconstructs bytes; it does not validate a proof. Validate field formats, including disallowing line breaks in line-oriented identifiers and nonce values, before construction. Accept only known versions and algorithms.

`path`, `mimeType`, `lens`, duration, and bitrate fields are not independently included in the version-1 signed payload. The hash binds file contents, but these separate metadata fields still need validation if used for policy.

## Backend verification requirements

1. Authenticate the publishing request and load the server-side capture session.
2. Check ownership, expected device identity, expected media type, expiry, nonce equality, and unused status.
3. Retrieve the registered public key from trusted backend state.
4. Hash the actual uploaded original file bytes on the backend. Compare to `sha256`; do not trust a client-declared hash alone.
5. Reconstruct the exact canonical payload and verify the ECDSA signature with the registered key.
6. Validate media type, size, duration, and other publishing rules independently of unsigned metadata.
7. Atomically consume the session with acceptance so concurrent requests cannot both publish using one challenge.

Define retry semantics explicitly: a retried upload or acceptance request should not become a second publication. Keep storage objects private/unpublished until verification succeeds. These are backend responsibilities, not native API calls.

## Preserve original bytes

Hash and sign photos after writing the JPEG and videos after MP4 finalization. Upload the exact original bytes. Re-encoding, metadata rewriting, recompression, or substituting a thumbnail changes the hash and invalidates the original proof.

Renaming the upload alone does not change its bytes. Store transformed display variants separately from the verified original, under a backend policy that preserves their relationship. See [Media files](media-files.md).

## What this protects, and its limits

With correct backend enforcement, replacing a signed file, fabricating a JavaScript media object, or replaying a consumed challenge cannot pass the same proof checks. Merely receiving a nonempty signature in React is not verification.

This is capture provenance, not an AI-image classifier. A compromised runtime, emulator, virtual camera, or modified camera source may feed synthetic frames into capture before hashing/signing. A physical camera can also photograph a screen. The proof does not establish that a depicted event is real or that pixels originated directly from an uncompromised physical sensor.

Play Integrity and hardware-backed key attestation were discussed as potential additional layers; they are not implemented features established by the supplied camera source. Do not advertise them as part of the current proof.

---

[Previous: Permissions](permissions.md) · [Next: Camera API](camera-api.md) · [Media files](media-files.md)
