# Pinoy Diner Epson Print Agent v1.5.0

The Android app intentionally uses the same live Pinoy Diner Order Print Manager UI as the Web App.

## Android UI

- Full-screen live dashboard
- Active / Done order views
- Print / Reprint
- Done / Make Active
- Clear
- Setup
- Shared Printer IP and Port controls
- Kitchen PIN access

There is no duplicate native Android order screen. The Android layer only provides the local printer bridge that a normal browser cannot provide.

## Printing path

Web dashboard -> queued print request -> Android bridge -> raw TCP -> Epson IP:9100 -> ESC/POS receipt + cut.

The printer requires no token or login.
