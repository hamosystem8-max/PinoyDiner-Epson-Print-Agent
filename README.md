# Pinoy Diner Epson Print Agent
Version 1.2.0 — 24 Aug 2026

Purpose: local Android bridge between the Pinoy Diner AppDeploy order dashboard and an Epson TM receipt printer on the restaurant LAN.

## Printer path
Android tablet -> Epson printer IP -> TCP 9100 -> raw ESC/POS -> print -> auto cut.

No Epson printer username, API key, token, or cloud printer service is used.

## Setup
1. Open this project in Android Studio.
2. Let Gradle sync.
3. Build and install the app on the restaurant Android tablet.
4. Put tablet and Epson TM printer on the same LAN/Wi-Fi.
5. Enter the printer IP and port 9100 at the top of the app.
6. Tap SAVE + TEST. A test receipt should print and cut.
7. The AppDeploy dashboard loads below. Sign in once to the existing staff dashboard.
8. Leave the app running. Queued orders are handed to the native bridge and printed via raw TCP.

## Receipt format
- 80 mm receipt layout
- PINOY DINER header
- large pickup time
- customer name/mobile/date
- wrapped order lines
- REPRINT warning when applicable
- automatic paper cut

## Important
The Android app stores only the local printer IP/port in private SharedPreferences. It does not store an Epson password or printer token.
