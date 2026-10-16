# PocketRouter

PocketRouter is an open-source Android networking toolkit for local-network diagnostics and hotspot experiments. It targets Android 16 (API 36), uses Kotlin + Jetpack Compose + Material 3, and has a minimum SDK of Android 8.0 (API 26).

## v0.2.0 — ADB/Shizuku bridge edition

### Included features

- **Network overview:** active transport, internet validation, metered status, local addresses, DNS and routes.
- **TCP reachability:** test a user-specified IP/hostname and port, e.g. a PC's `192.168.x.x:8000` local server.
- **HTTP DNS-SD discovery:** discover services advertising `_http._tcp.` and attempt to resolve their endpoints.
- **Metered helper:** report PocketRouter's active network classification and open Wi-Fi settings to help users change the metered setting on the client device.
- **Local-only hotspot lab:** create a separate local-only hotspot through Android's public API. It does not share mobile internet.
- **Shizuku bridge:** checks whether Shizuku is running, requests user-granted Shizuku permission, and executes fixed, read-only network diagnostics through the shell identity.
- **Optional root diagnostics:** only runs after the user explicitly taps the button and approves the superuser prompt. Root diagnostics add route/rule/neighbor/ARP and firewall-rule inspection where those tools are available.

## Access model

PocketRouter does **not** need ADB/Shizuku or root for its ordinary network overview, TCP checks, service discovery or metered-settings helper. The **advanced diagnostics mode** requires either:

1. **Shizuku started using ADB/Wireless debugging** (recommended; no root), with PocketRouter permission explicitly approved in Shizuku; or
2. **Root access**, optional, for additional read-only diagnostics such as firewall and NAT rule inspection.

This split keeps basic tools usable even if Shizuku is stopped. If the intended product policy is to hard-lock every screen until Shizuku is connected, that is a product choice, not an Android technical requirement; the current build gates advanced diagnostics rather than basic LAN tools.

### Start Shizuku without root (Android 11+)

1. Install Shizuku from its official project/distribution page: https://shizuku.rikka.app/
2. On the POCO C75, enable Developer options (tap Build number repeatedly in About phone).
3. Enable **Wireless debugging** in Developer options.
4. Open Shizuku and choose **Start via Wireless debugging**.
5. Pair using the pairing notification/dialog and code as prompted. Keep Wireless debugging enabled while using Shizuku.
6. Open PocketRouter, tap **Connect Shizuku**, and approve its permission request.
7. Tap **Run Shizuku network diagnostics**. The output should include shell identity, Android version, routes, neighbor cache and tethering service diagnostics when the OS exposes them.

Shizuku's ADB service can stop after reboot or when Wireless debugging is disabled; restart it when needed. Menu wording can vary by Android/OEM build.

## Android permissions

- `INTERNET` — sockets and network checks.
- `ACCESS_NETWORK_STATE` — active network capabilities and link properties.
- `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` — Wi-Fi APIs/local-only hotspot.
- `NEARBY_WIFI_DEVICES` — runtime permission for Wi-Fi operations on Android 13+.
- `ACCESS_FINE_LOCATION` (max SDK 32) — legacy Wi-Fi permission path for older Android versions.
- `moe.shizuku.manager.permission.API_V23` — Shizuku API permission contract.

Shizuku permission is granted interactively by the user. PocketRouter does not embed an ADB server and cannot silently grant itself Shizuku or root access.

## Build in Android Studio

1. Install Android Studio, Android SDK Platform 36 and JDK 17.
2. Open this directory as an existing Gradle project.
3. Use a Gradle installation/wrapper compatible with AGP 8.10.1 (Gradle 8.11.1 is appropriate).
4. Let Gradle sync resolve dependencies, including `dev.rikka.shizuku:api:13.1.5` and `dev.rikka.shizuku:provider:13.1.5`.
5. Connect the POCO C75 with USB debugging enabled or use an emulator.
6. Run the `app` configuration. To create an APK, use **Build → Build APK(s)**.

The source was updated in this environment, but Android SDK/Gradle were unavailable here, so compilation and real-device behavior still need validation in Android Studio.

## Android restrictions

An ordinary Android app cannot freely change the built-in tethering hotspot's DHCP pool, gateway, NAT, firewall, or client-isolation settings. Shizuku grants a shell identity, not the phone's system/privileged UID; some commands may be denied even with Shizuku. Root may expose more diagnostics, but the current root feature is read-only and does not change firewall rules.

`NET_CAPABILITY_NOT_METERED` describes PocketRouter's own active network. It cannot force a different connected device to treat the hotspot as unmetered, and it cannot override app-specific restrictions. A local-only hotspot is a separate no-internet network and may conflict with the built-in tethering hotspot.

## Current limitations / roadmap

- **Not implemented:** changing system hotspot DHCP/gateway/client isolation; applying firewall/NAT/port-forwarding rules; reliably listing all tethered clients on all OEM builds.
- **Not implemented:** Wi-Fi Direct internet-sharing/proxy mode. This is a separate networking mode, not a simple setting toggle.
- **Not implemented:** automatically changing the metered status of a different client device. Use that device's settings or a separately authorized ADB workflow.
- **Next:** extract networking logic into repositories/services, add bounded opt-in LAN diagnostics, and prototype Wi-Fi Direct/proxy sharing with clear compatibility messaging.

## Quick test checklist

1. Open PocketRouter and refresh the network overview.
2. On a PC connected to the same hotspot, run `python -m http.server 8000 --bind 0.0.0.0`.
3. Enter the PC's hotspot-assigned IP and `8000` in Port Reachability. Allow the port through the PC firewall if needed.
4. Run HTTP service discovery; only DNS-SD-advertised services appear.
5. Start Shizuku and grant PocketRouter permission, then run the advanced diagnostics.
6. Test root diagnostics only if the device is already rooted and you want the additional read-only output.
7. Test Local-only Hotspot Lab only when ready to switch networks; it does not provide internet sharing.
