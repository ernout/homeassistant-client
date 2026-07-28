# Home — Home Assistant companion voor de Light Phone 3

Plan voor een LightOS-tool waarmee je Home Assistant kunt bedienen vanaf de LP3.
Onderzoek uitgevoerd op 2026-07-28.

## Status (2026-07-28)

- ✅ **Fase 0 afgerond**: SDK gecloned, build werkt (JDK 17 via Homebrew), LP3-emulator (AVD "LightPhone3") met LightOS-emulator als system app.
- ✅ **Fase 1 afgerond en end-to-end getest** tegen een lokale test-HA (Docker) via https: dashboard `light-phone` wordt over WebSocket uitgelezen en native gerenderd (entities/headers/scripts, meerdere views met switcher), states via REST, toggles en scripts werken en zijn geverifieerd in HA. Code in `tool/src/main/kotlin/com/thelightphone/homeassistant/`.
- Implementatiekeuzes: **kale OkHttp voor REST** (ktor's client bleek na een WebSocket-sessie stuk te gaan — "Parent job is Completed"; ktor blijft alleen voor de WS dashboard-fetch), kotlinx-serialization, DataStore voor serverconfig.
- ⚠️ **Nieuwe bevinding — alleen https**: Light's gegenereerde manifest staat geen cleartext-HTTP toe en een eigen AndroidManifest is verboden. Lokale `http://`-instanties werken dus niet; Nabu Casa/https wél. Aan Light vragen: cleartext of network-security-config voor lokaal verkeer (standaard IoT-usecase).
- Testomgeving (op deze Mac): HA in Docker (`ha-test`, poort 8123, user `lp3test`) + Caddy TLS-proxy (`lp3-tls`, poort 8443, self-signed CA in emulator system store — de CA-overlay op `/apex/com.android.conscrypt/cacerts` moet na elke emulator-boot opnieuw via het bind-mount-commando).
- ✅ **Draait op echte hardware** (2026-07-29): getest op een Light Phone III (TLP301) tegen een live HA-instantie via Nabu Casa — onboarding met QR-tokenscan, dashboard en bediening werken. Drie hardware-specifieke crashes gefixt (main-thread-I/O bij close, kapotte GetKeyboardOptions-RPC, permission-RPC-mismatch). Sideload: Developer Mode-toggle in het Light Dashboard activeert ADB over USB; camera-permissie eenmalig via `adb shell pm grant`.
- ✅ **Fase 2 grotendeels afgerond**: de app registreert zichzelf bij HA's `mobile_app`-integratie (device + `device_tracker.light_phone_3` + `notify.mobile_app_light_phone_3` aangemaakt, webhook-credentials opgeslagen, 410-herregistratie afgehandeld). Webhook-kanaal end-to-end geverifieerd: batterijsensor (`sensor.light_phone_3_battery_level`) en `update_location` (device_tracker kreeg GPS-coördinaten) werken via de geregistreerde webhook.
- ⚠️ **Sandbox-gat — batterij én locatie hebben geen bron op het toestel**: de Light-sandbox blokkeert `getSystemService`/receivers (dus BatteryManager en LocationManager), er is (nog) geen Light-primitief voor, en sysfs (`/sys/class/power_supply`) wordt door SELinux geweigerd. De app-code werkt en degradeert netjes (slaat updates over zonder bron); zodra Light een battery/location-API levert is het aansluiten van één functie. **Feature-requests voor Light**: (1) cleartext/netwerk-config voor lokaal HA-verkeer, (2) battery-API, (3) location-API (permissie staat nota bene al op hun allowlist).
- Volgende: fase 3 (live updates via `subscribe_entities`), fase 4 (camera + notificaties via UnifiedPush/local push), fase 5 (multi-server UI, polish). QR-onboarding zit in de app maar is in de emulator niet met een echte camera getest.

## TL;DR haalbaarheid

Alles wat we willen kan met bestaande, gedocumenteerde API's aan beide kanten:

| Wens | Oplossing |
|---|---|
| Eruitzien als een Light-tool | SDK is Kotlin/Compose met open-source design system (`sdk/ui`: `LightTheme`, `LightText`, `LightTopBar`, `LightBarButton`, …) |
| In het toolmenu | Standaard: elke SDK-tool is een APK in de LightOS toolbox; metadata via `lighttool.toml` |
| Long-lived token + remote URL | HA accepteert long-lived tokens (10 jaar) overal; Nabu Casa remote URL werkt gewoon als base URL |
| Beheren wát zichtbaar is, in welke volgorde, over meerdere schermen | Een **dedicated HA-dashboard** ("Light Phone") als configuratiebron: de app leest de dashboard-config uit en rendert die native. Beheer = gewoon de HA dashboard-editor (drag & drop) |
| Scripts/devices bedienen | `POST /api/services/<domain>/<service>` (REST) of `call_service` (WebSocket) |
| Notificaties vanaf HA | `mobile_app`-registratie → `notify.mobile_app_lightphone` → **local push over WebSocket** (geen Google/FCM nodig — de LP3 heeft geen Play Services, dus dit is de enige én de juiste route) |
| Locatie naar HA | `update_location` webhook-bericht van de `mobile_app`-integratie |
| Camera bekijken | Snapshot via `GET /api/camera_proxy/<entity_id>` (JPEG), auto-refresh elke paar seconden. Geen videostream — wel prima "wie staat er voor de deur" op het monochrome scherm |
| Meerdere instanties (thuis + werk) | Per server: URL('s) + token + eigen `mobile_app`-registratie + eigen dashboard; server-switcher in de UI |

## Bronnen

- Light SDK: https://github.com/lightphone/light-sdk (docs in `docs/`, voorbeelden in `examples/`)
- Developer program (gratis, open): https://developers.thelightphone.com/
- HA native app integration: https://developers.home-assistant.io/docs/api/native-app-integration/ (subpagina's `/setup`, `/sending-data`, `/sensors`, `/notifications`)
- HA auth: https://developers.home-assistant.io/docs/auth_api/
- HA WebSocket API: https://developers.home-assistant.io/docs/api/websocket
- Local push: https://companion.home-assistant.io/docs/notifications/notification-local/
- Referentie-implementatie registratie/webhook/local push: `common/`-module van https://github.com/home-assistant/android
- Referentie WebSocket-client: https://github.com/home-assistant/home-assistant-js-websocket

## Wat de Light SDK is (feiten)

- Native Android (API 34), Kotlin + Jetpack Compose, MVVM. Geen webapps.
- Elke tool is een Gradle-module → APK, gestart via `LightActivity`; schermen zijn `LightScreen`-subklassen, navigatie met `navigateTo(::Screen)`.
- Metadata + permissions in één `lighttool.toml`; build-plugin handhaaft een **allowlist van permissions én third-party libraries**.
- Toegestane permissions (volledig): `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `VIBRATE`, `POST_NOTIFICATIONS`, `CAMERA`, `RECORD_AUDIO`, `READ_MEDIA_AUDIO`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `NFC`.
- Background werk via `@LightJob` + `LightWork.enqueue()/enqueuePeriodic()` (WorkManager-stijl) — géén vrij draaiende background services.
- Notificaties via LightOS' eigen push-laag (`LightPushService`/`LightPushManager`).
- Emulator meegeleverd (1080×1240, API 34, geen Play services); ontwikkelen kan zonder LP3.
- **Sandbox-regels** (geverifieerd in `plugin/.../LightSdkPlugin.kt`, build-time afgedwongen):
  - Dependency-allowlist bevat o.a.: **OkHttp** ✔, **ktor** ✔, **kotlinx-serialization** ✔, Room, DataStore, androidx.work, media3, en `org.unifiedpush.android:connector`. Retrofit staat er níét op (en reflectie is sowieso geblokkeerd) → wij gebruiken **OkHttp + kotlinx-serialization** voor REST én WebSocket.
  - Geblokkeerde imports/patronen: `Context`, `Intent`, `getSystemService()`, `startActivity()`, BroadcastReceivers, reflectie, `LocalContext`. Gevolg: **geen mDNS-discovery** (NsdManager vereist `getSystemService`) en alles via Light-primitieven.
- **Push = UnifiedPush**: `LightPushManager` in de SDK bewaart een UnifiedPush `PushEndpoint`-URL. Elke server die naar dat endpoint POST, bereikt de tool in de achtergrond via Lights push-infra.
- Distributie: nu sideloaden via ADB/Developer Mode in het Light Dashboard; de curated **Tool Library** (gratis, open source, non-commercieel) wordt ~oktober 2026 verwacht. Een open-source HA-client past prima binnen hun contentregels.

## Wat we van Home Assistant gebruiken (feiten)

1. **Auth**: long-lived access token, `Authorization: Bearer <token>`. Alleen nodig voor registratie + WebSocket; daarna is het `webhook_id` de credential.
2. **Registratie**: `POST /api/mobile_app/registrations` → `webhook_id` (+ bij Nabu Casa: `cloudhook_url` en `remote_ui_url`). Maakt een device aan in HA en activeert `notify.mobile_app_<naam>`.
3. **Webhook-kanaal**: `POST /api/webhook/<webhook_id>` met `{"type": ..., "data": ...}`. Berichttypes die we gebruiken: `update_location`, `call_service`, `register_sensor`/`update_sensor_states` (batterij!), `get_config`, `stream_camera`. HTTP 410 = registratie verwijderd → opnieuw registreren.
4. **WebSocket** (`wss://<host>/api/websocket`): auth-handshake, daarna `subscribe_entities` (gefilterd op entity_ids — zuinig, diff-based) en `mobile_app/push_notification_channel` voor notificaties. Met `support_confirm: true` bewaart HA notificaties tot ze bevestigd zijn.
5. **Dashboard als configuratie**: de Lovelace-config van een dashboard is uitleesbaar over WebSocket (de frontend doet dit zelf; exact commando — `lovelace/config` met `url_path` — in fase 0 verifiëren tegen `home-assistant-js-websocket`/frontend-source). De app leest het dashboard met url_path `light-phone` en rendert de views/cards native.
6. **Camera**: `GET /api/camera_proxy/<entity_id>` levert een JPEG-snapshot (Bearer-auth). Polling elke 1–2 s ≈ een traag maar bruikbaar "live" beeld; zuinig en werkt over Nabu Casa.
7. **URL-strategie** zoals de officiële app: interne URL indien bereikbaar, anders remote/Nabu Casa URL; webhooks in volgorde cloudhook → remote → lokaal.

## Configuratiemodel: een HA-dashboard als bron van waarheid

In plaats van losse labels gebruiken we een **dedicated dashboard in HA** (naam "Light Phone", url_path `light-phone`). Dat lost drie dingen tegelijk op:

- **Wat** er op de telefoon staat: alleen entities die je op dat dashboard zet.
- **Volgorde**: de kaartvolgorde in de HA-editor (drag & drop) ís de lijstvolgorde op de LP3.
- **Meerdere schermen + navigatie**: elke **view** (tab) van het dashboard wordt een scherm op de LP3; navigatie tussen views via de topbar/menu. Eén view = één scherm — jouw basisscenario (sloten, alarm, paar scenes) is gewoon een dashboard met één view.

De app ondersteunt bewust een **kleine subset van card-types** en mapt ze op Light-componenten:

| HA-card | LP3-weergave |
|---|---|
| `entities` / `tile` / `entity` | rij(en) in een lijst: naam + toestand + actie per domein |
| `button` | grote knop (scene/script/toggle) |
| `picture-entity` met camera / `camera` | camerascherm: snapshot met auto-refresh |
| `markdown` (titel/tekst) | `LightText`-blok |
| onbekende card | wordt overgeslagen, met melding in een debug-scherm |

Per entity-domein een vaste, simpele weergave: `light`/`switch`/`lock` → toggle (slot: open/dicht met bevestiging), `alarm_control_panel` → status + arm/disarm-scherm (met code-invoer), `script`/`scene`/`button` → uitvoeren, `sensor`/`binary_sensor` → waarde, `climate` → detailscherm met +/−, `cover` → open/dicht.

```
┌──────────────────────┐    ┌──────────────────────┐
│ THUIS      Beneden ▾ │    │ ← Voordeur           │
│                      │    │                      │
│ Voordeur      Op slot│    │  ┌────────────────┐  │
│ Alarm            Uit │    │  │   [snapshot]   │  │
│ Alles uit          ▷ │    │  │  refresh ~2 s  │  │
│ Film kijken        ▷ │    │  └────────────────┘  │
│ Deurbelcamera      ▸ │    │                      │
└──────────────────────┘    └──────────────────────┘
```

Fallback (plan B): als het uitlezen/parsen van Lovelace-config te gammel blijkt, terugvallen op entity-**labels** (`light-phone`) + volgorde via alfabetische sortering of label-per-scherm. Minder flexibel, wél triviaal te bouwen.

## Datamodel

```kotlin
data class ServerConfig(
  val id: String,            // uuid
  val name: String,          // "Thuis", "Werk"
  val localUrl: String?,     // http://homeassistant.local:8123
  val remoteUrl: String,     // Nabu Casa / eigen reverse proxy
  val token: String,         // long-lived, in encrypted storage (Keystore, zie authenticator-voorbeeld)
  val webhookId: String?,    // na registratie
  val cloudhookUrl: String?,
  val dashboardPath: String = "light-phone",
)
```

Opslag: `LightDb` (Room) of `DataStore`, token versleuteld via Android Keystore (het `authenticator`-voorbeeld in de SDK doet precies dit).

## Onboarding-flow (token invoeren zonder typwerk)

Er is geen knippen/plakken op de LP3, dus alles moet via scannen of automatische discovery. De SDK heeft een kant-en-klare **`LightQrCodeScanner`**-component, en HA's profielpagina heeft een **"Generate QR code"-knop** bij het aanmaken van een long-lived token (geverifieerd in de frontend-source: de QR bevat de kale token-string, géén URL).

Flow "Server toevoegen" (mDNS-discovery valt af — de sandbox blokkeert `getSystemService`, zie "Sandbox-regels"):

1. **URL invoeren**: kort en typbaar voor lokaal (`homeassistant.local:8123`), óf stap 1+2 in één keer via onze eigen QR (optie B hieronder).
2. **Token scannen**: HA-profiel op je computer → token aanmaken → "Generate QR code" (native HA-feature; QR bevat de kale token-string) → scannen met de LP3.
3. App test de verbinding en registreert via `mobile_app`. De **remote URL komt automatisch mee**: bij Nabu Casa geeft de registratie `remote_ui_url` + `cloudhook_url` terug, anders lezen we `external_url` uit `GET /api/config`. Je hoeft dus nooit een lange Nabu Casa-URL in te tikken.

Optie B (aanbevolen voor werk-instantie / remote-only): een mini-webpagina (statisch, client-side) die `{url, token, name}` in één QR stopt — token verlaat de browser niet; één scan en klaar.

## Fases

### Fase 0 — Setup (klein)
- Geen aanmelding nodig: `light-sdk` clonen kan direct. Alleen een GitHub personal access token met `read:packages` in `local.properties` (`gpr.user`/`gpr.key`) of als env vars (`GITHUB_ACTOR`/`GITHUB_TOKEN`) — hun libraries staan op GitHub Packages. Aanmelden bij het developer program is optioneel (community/updates).
- `light-sdk` scaffold clonen in deze map, emulator-image opzetten (1080×1240, API 34, geen Play services), `examples/ui-demo` en `examples/weather` draaien. Sideloaden op de echte LP3 kan nu al via ADB; de "seamless" install via het Light Dashboard komt later.
- **Checken of OkHttp (of ktor) op de library-allowlist staat** — nodig voor REST én WebSocket. Zo niet: aanvragen bij Light (ze accepteren requests expliciet).
- Verifiëren van het WebSocket-commando om een dashboard-config op te halen (tegen frontend/`home-assistant-js-websocket`-source).

### Fase 1 — MVP: één scherm bedienen (kern)
- Eén server, token via QR-onboarding.
- Dashboard `light-phone` uitlezen, eerste view renderen als lijst (subset: `entities`/`tile`/`button`-cards; domeinen: light, switch, lock, alarm, script, scene, sensor).
- Service-calls via REST. States verversen bij openen + refresh-knop.
- Werkt al volledig in de emulator.

### Fase 2 — mobile_app-registratie
- `POST /api/mobile_app/registrations` per server; `webhook_id` opslaan.
- Batterijniveau als sensor registreren (`register_sensor`/`update_sensor_states`).
- Locatie: `update_location` via webhook. Start met "stuur locatie bij openen app" + optioneel periodiek via `LightWork.enqueuePeriodic`.

### Fase 3 — Live updates + meerdere schermen
- WebSocket zolang de app open is: `subscribe_entities` gefilterd op de dashboard-entities → realtime updates; service-calls via `call_service` op dezelfde socket.
- Meerdere views → schermen + navigatie (topbar-menu of `LightBottomBar`).
- Reconnect-logica overnemen uit `home-assistant-js-websocket` / de `common`-module van de Android-app.

### Fase 4 — Camera + notificaties
- Camerascherm: `camera_proxy`-snapshot met auto-refresh (~2 s) zolang het scherm open is; entry via camera-card of vanuit een notificatie.
- **Primaire route — echte background push via UnifiedPush**: LightOS-push ís UnifiedPush; de tool krijgt een `PushEndpoint`-URL. Die geven we bij de `mobile_app`-registratie op als `app_data.push_url`. Als je dan in HA `notify.mobile_app_lightphone` aanroept, POST HA Core de notificatie-JSON rechtstreeks naar Lights push-endpoint → LightOS wekt de tool → tonen via `LightPushService`. Geen relay, geen persistente verbinding. (Verifiëren: payload-formaat/grootte ≤4 KB en of HA's push-component eisen stelt aan de respons.)
- Fallback/aanvulling: `mobile_app/push_notification_channel` over WebSocket zolang de app open is (realtime, geen rate limits), en/of een periodieke `LightWork`-job die met `support_confirm: true` opgespaarde notificaties leegtrekt.

### Fase 5 — Multi-instance + afwerking
- Tweede server (werk): eigen token, eigen registratie, eigen dashboard; switcher in de topbar.
- Foutafhandeling (offline, 410 re-registratie, token ingetrokken), UI-polish naar Light-stijl.
- Open source maken en indienen bij de Tool Library (vetting start volgens Light aug–sep 2026 — mooie timing).

## Risico's / open vragen

1. ~~Library-allowlist~~ **Opgelost**: OkHttp, ktor, kotlinx-serialization, Room, DataStore en androidx.work staan op de allowlist (geverifieerd in `LightSdkPlugin.kt`). Retrofit niet → OkHttp + kotlinx-serialization.
2. ~~Notificaties in achtergrond~~ **Waarschijnlijk opgelost via UnifiedPush** (zie fase 4); payload-formaat en HA-compatibiliteit nog praktisch te verifiëren. Fallback (WebSocket + periodieke job) blijft beschikbaar.
3. **Lovelace-config uitlezen**: het exacte WebSocket-commando is niet formeel gedocumenteerd (wel stabiel — de frontend gebruikt het zelf). Verifiëren in fase 0; plan B is het label-mechanisme.
4. **Achtergrondlocatie**: `ACCESS_BACKGROUND_LOCATION` staat níét op de permission-allowlist. Locatie bij app-gebruik werkt zeker; hoe LightOS omgaat met locatie in periodieke jobs moeten we in de praktijk testen.
5. **Strategy/sections-dashboards**: nieuwe HA-dashboards gebruiken "sections" en soms auto-genererende strategieën; onze parser moet daar tolerant voor zijn (of we documenteren: maak een klassiek handmatig dashboard voor de LP3).
6. **Strenge sandbox**: geen `Context`/`Intent`/reflectie in tool-code; alles moet via Light-primitieven. Verwacht her en der creatieve omwegen (bijv. geen mDNS-discovery).
7. **SDK is jong**: repo zegt letterlijk "things are going to change fast". Build-service en Tool Library-mechanics zijn nog in beweging.
