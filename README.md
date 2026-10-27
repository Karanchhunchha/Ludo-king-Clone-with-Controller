# 🎲 Ludo King by Karan — with BLE Controller
              (JUST FOR FUN)
Play Ludo on one phone and **control the dice from another phone over Bluetooth LE** — no server, no root, no extra hardware. Tap a dice number on the controller, tap the dice in the game, and it rolls exactly that number.

**Created and developed by [Karan Chhunchha](https://github.com/Karanchhunchha)** — game modifications, BLE integration, controller app, rules and UX.

| | |
|---|---|
| **Game** | `Ludo King by Karan` — a full Ludo board game with a built-in **BLE GATT server + advertiser** |
| **Controller** | `Controller by Karan` — a standalone Android app that scans, connects to any BLE device, and sends dice commands |

## ✨ Features

**Game (`game/`)**
- Full offline Ludo: 2–4 players, bots, safe squares, captures, token customisation
- Built-in Bluetooth LE **host**: advertises itself and runs a GATT server while a match is on the board
- Dice command bridge — an incoming `COLOR:N` write queues dice value `N` for the **player's own dice tap** (no bot-like auto-roll)
- Real Ludo rule: **three consecutive sixes forfeit the turn**
- Silent controller mode — no popups on the game screen; connection events go to logcat (`LudoBLE` tag)

**Controller (`controller/`)**
- Scans **all nearby BLE devices** (any brand), sorted by signal, with a green **LUDO** badge on game hosts
- Tap-to-connect device picker, live RSSI signal strength, quick-reconnect to the last device
- Dice pad 1–6 with player colours (Blue, Red, Green, Yellow), send history with ACK status
- Robust connection handling: stale-GATT cleanup, scan/connect race protection, 15 s connect timeout

## 📦 Install

Grab both APKs from [`apks/`](apks/):

| APK | Install on |
|---|---|
| [`LudoKing-by-Karan-game.apk`](apks/LudoKing-by-Karan-game.apk) | The phone that runs the game |
| [`Controller-by-Karan-v1.1.apk`](apks/Controller-by-Karan-v1.1.apk) | The phone that controls the dice |

```bash
adb install -r apks/LudoKing-by-Karan-game.apk
adb install -r apks/Controller-by-Karan-v1.1.apk
```

## 🎮 How to play

1. Enable Bluetooth on both phones.
2. Game phone: open **Ludo King by Karan** and **start a match** — the BLE host only runs on the board screen. Accept the *Nearby devices* permission prompt.
3. Controller phone: open **Controller by Karan** → **Scan devices** → tap the row with the green **LUDO** badge.
4. Tap a dice number (1–6) and a colour — the value is queued silently.
5. On the game phone, **tap the dice yourself** — it rolls exactly the queued number, with the normal animation.

## 📡 BLE protocol

| Item | Value |
|---|---|
| Service | `12345678-1234-1234-1234-123456789abc` |
| Dice command characteristic (write) | `12345678-1234-1234-1234-123456789001` |
| ACK characteristic (notify) | `12345678-1234-1234-1234-123456789002` |
| Command format | `BLUE:3` … `YELLOW:6` (colour prefix is accepted; the roll applies to the current player) |
| ACK | `OK:<command>` notification once the write is handled |

The game side requests `BLUETOOTH_ADVERTISE` + `BLUETOOTH_CONNECT` at runtime (Android 12+); the controller requests `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`.

## 🔨 Build from source

Both projects are standard Gradle Android projects and open directly in Android Studio.

```bash
cd game && ./gradlew assembleDebug       # → app/build/outputs/apk/debug/
cd controller && ./gradlew assembleDebug # → app/build/outputs/apk/debug/
```

- Game: Java · `com.karanchhunchha.ludoking` · minSdk 21 / targetSdk 33 · AGP 8.7.3 / Gradle 8.10.2
- Controller: Kotlin · `com.bletestclient` · minSdk 26 / targetSdk 35 · AGP 8.7.3 / Gradle 8.10.2
- JDK 17+ required (built and tested with JDK 23). Set `local.properties` → `sdk.dir` or let Android Studio do it.

## 📁 Repo layout

```
├── game/        # Ludo King by Karan (board game + BLE GATT host)
├── controller/  # Controller by Karan (BLE scanner + dice pad)
└── apks/        # Ready-to-install debug builds of both apps
```

---

<p align="center"><b>Made by Karan Chhunchha</b></p>
