# Listen

Minimal Android app that syncs a Limitless Pendant to a webhook.

## What it does

1. Connects to the Limitless Pendant via BLE (MAC: `FD:04:D0:EB:84:88`)
2. Drains flash-stored audio using the Limitless flash drain protocol
3. Writes Opus frames to `.bin` files in app-private external storage
4. Uploads completed files to `https://pendant.enzoduit.com/v2/sync-local-files`
5. Runs as a foreground service for background operation

## Architecture

- **PendantBleManager** — GATT wrapper (connect, notify, write)
- **PendantBleForegroundService** — Connection lifecycle, reconnect logic
- **LimitlessProtocol** — BLE packet codec (protobuf-over-BLE)
- **LimitlessFlashDrainEngine** — Flash drain state machine (IDLE → AWAITING_STATUS → DRAINING)
- **LimitlessBatchAudioWriter** — Writes Opus frames to pendant-timestamped `.bin` files
- **HttpUploader** — POST multipart to webhook, delete on success
- **MainActivity** — Single-screen UI: BLE status, upload count, log

## BLE Critical Note

The pendant MAC `FD:04:D0:EB:84:88` is a **random BLE address** (bit pattern of first byte).
On Android API 34+, `adapter.getRemoteLeDevice(addr, BluetoothDevice.ADDRESS_TYPE_RANDOM)` is used.

## Build

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Config

| Parameter | Value |
|---|---|
| Target MAC | `FD:04:D0:EB:84:88` |
| Service UUID | `632de001-604c-446b-a80f-7963e950f3fb` |
| TX Char UUID | `632de002-604c-446b-a80f-7963e950f3fb` |
| RX Char UUID | `632de003-604c-446b-a80f-7963e950f3fb` |
| Upload endpoint | `https://pendant.enzoduit.com/v2/sync-local-files` |
| API key header | `X-API-Key: pendant-ed-2026` |

## Based on

Omi open-source project — adapted LimitlessFlashDrainEngine, LimitlessProtocol,
LimitlessBatchAudioWriter, and BaseBatchAudioWriter. All Flutter/Pigeon/Firebase
dependencies removed. Pure Android Kotlin.
