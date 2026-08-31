# Things to raise with Light

Findings from building a Home Assistant tool against the SDK (July 2026),
tested on a Light Phone III (TLP301) and in the LightOS emulator. Each item is
something we hit in practice, with what we observed and what would unblock it.

Tick items off as they are raised or resolved.

## Bugs — reproducible, with evidence

- [ ] **`RECORD_AUDIO` passes the build check but cannot be granted.**
  It is in `LightToolPolicy.ALLOWED_PERMISSIONS` (`plugin/src/main/kotlin/com/thelightphone/plugin/LightToolMetadata.kt`), so a tool declaring it builds fine, but requesting it at runtime logs:
  `W LightSdkPermissionActivity: requested permission: android.permission.RECORD_AUDIO is not grantable by this server`
  Without the microphone a tool cannot use voice at all — in our case Home Assistant's Assist pipeline, which works end to end otherwise.

- [ ] **`checkPermission` reports a permission as denied after the OS has granted it.**
  With `adb shell pm grant … android.permission.RECORD_AUDIO` applied (`dumpsys` shows `granted=true`), the `GetPermission` RPC still answers denied, so a tool that trusts the SDK's own API refuses to proceed. We had to bypass the check and let `AudioRecord` decide. Same pattern seen with `CAMERA` in the QR scanner.

- [ ] **`GetKeyboardOptions` crashes the tool on shipping LightOS builds.**
  `LightServiceMethod.GetKeyboardOptions.Response` requires `swipeEnabled`, the device's server omits it, and decoding throws:
  `kotlinx.serialization.MissingFieldException: Field 'swipeEnabled' is required … but it was missing`
  Any tool calling `rememberKeyboardOptions()` — i.e. any tool using `LightTextInputEditor` as documented — crashes on open. Making the field optional in `sdk/shared` would fix it for every existing device.

## Missing capabilities

- [ ] **Cleartext HTTP for local network addresses.**
  The generated manifest sets no `usesCleartextTraffic` and a hand-written `AndroidManifest.xml` is rejected by the plugin, so tools are https-only. Most Home Assistant users run `http://homeassistant.local:8123` at home. A `lighttool.toml` opt-in, or a default network-security-config permitting cleartext for RFC1918 / `.local` only, would cover the standard smart-home case without opening the door generally.

- [ ] **A battery-level primitive.**
  `BatteryManager` needs `getSystemService`, which the plugin blocks; `/sys/class/power_supply` is refused by SELinux. There is no way for a tool to read the charge level. Companion-style tools want to report it.

- [ ] **A location primitive.**
  `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` are on the permission allowlist, but `LocationManager` is unreachable for the same reason — the permission can be declared and nothing can use it. A one-shot fix plus something usable from a periodic `LightWork` job would be enough.

- [ ] **`ACCESS_BACKGROUND_LOCATION` on the permission allowlist.**
  Without it the two location permissions that *are* allowlisted are worth very little: Android grants them foreground-only, and rejects the app op on every run that happens while the tool is off-screen. A tool can therefore report a position only while the user is looking at it — which for a companion-style tool is precisely when it doesn't matter. On our phone this showed up as a `device_tracker` frozen at home while the battery sensor kept updating, and `appops` told the whole story:

  ```
  FINE_LOCATION (allow):
    Access: [top-s]  ...   ← tool on screen
    Reject: [bg-s]   ...   ← the periodic job
    Reject: [cch-s]  ...   ← cached process
  ```

  The permission also needs to be grantable through the LightOS permission activity; `pm grant` over adb is the only route today.

- [ ] **Bluetooth.**
  No Bluetooth permission is on the allowlist and there is no primitive. Detecting a car via a paired handsfree connection is a natural fit for a phone meant to be put away while driving.

- [ ] **The LightOS map component in `sdk/ui`.**
  LightOS has a good map with pinch-to-zoom and panning; tools cannot use it. We ended up drawing OpenStreetMap tiles ourselves. Exposing the existing component would save every location-aware tool the same work — and the bandwidth.

- [ ] **`initialDelay` on `LightWork.enqueue`.**
  `enqueuePeriodic` floors at 15 minutes and `enqueue` takes no delay, so a self-rescheduling chain — the standard way to vary cadence with context — is not expressible. WorkManager supports it; the wrapper just doesn't pass it through.

- [ ] **`android:allowBackup` in the generated manifest.**
  It is not set, so Android's default of `true` applies, and tools cannot override it because a custom manifest is forbidden. Tools that store credentials should be able to opt out. (Note: the official Home Assistant Android app gets this wrong in the other direction — its backup rules still exclude a file the tokens moved out of in 2023, so its cloud backups contain live tokens.)

## Questions

- [ ] **Push to third-party tools: timeline and contract.**
  Confirmed working: an external server can POST straight to the UnifiedPush endpoint and the bytes arrive verbatim in `onPushNotification`. But per [discussion #111](https://github.com/orgs/lightphone/discussions/111), LightOS does not yet display notifications from external tools. When it does, two details matter for Home Assistant compatibility: HA's `mobile_app` calls `response.json()` **before** checking the status code, so the push endpoint must return a JSON body or HA logs an error on every notification (delivery still succeeds); and what the size limit is in practice (UnifiedPush specifies 4096 bytes).
  Also worth noting: `LightPushManager.updatePushCredentials` has `// TODO save pubKeySet` and stores only the endpoint URL, so Web Push encryption is currently impossible from a tool — plaintext is the only route.

- [ ] **Sorting and hiding tools in the LightOS menu.**
  Already asked by someone else in [discussion #131](https://github.com/lightphone/light-sdk/discussions/131), no reply yet. Our tool sits at the bottom of the list with no way to move it. Worth a +1 — it affects every community tool.

- [ ] **Installing tools without ADB.**
  The Developer Mode toggle in the dashboard turns on ADB, but the dashboard shows no side-load option yet, and the FAQ describes uploading an APK there. What is the intended route today, and when does the dashboard upload arrive?

## What worked well, for balance

- The Compose component library made a tool that looks native on day one; `LightQrCodeScanner` in particular saved building a scanner.
- The LightOS emulator as a system app is an excellent development story — the whole app was built and tested before touching real hardware.
- `LightAudioCapture` delivers exactly the mono 16-bit PCM stream a voice pipeline needs, with cancellation tied to collection. Nothing to fight.
- AOSP proximity alerts (`addProximityAlert`) work from a tool and are a genuinely good fit for zone-based automation without Google Play Services.
