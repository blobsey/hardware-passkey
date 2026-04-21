# Hardware Passkey

Minimal Android credential provider that stores WebAuthn passkeys in the Android Keystore

## Requirements

Android 14 (API 34) or higher. Earlier versions don't have the `CredentialProviderService` API.

## Building

Debug build (uses Android Studio's default debug keystore):

```
./gradlew :app:assembleDebug
```

Release build requires a keystore at `app/hardware-passkey-release.keystore`. Generate one with `keytool`, then:

```
RELEASE_KEYSTORE_PASSWORD="REALLY-COOL-PASSWORD-123" ./gradlew :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`.
