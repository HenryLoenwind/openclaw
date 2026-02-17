# OpenClaw Java Gateway Bridge (standalone project)

This folder is an **independent Gradle Java project** that can be copied into a larger standalone Java application.

## Features included

- WebSocket connection to OpenClaw Gateway (`ws://host:18789` by default)
- Authentication via **gateway token** or **gateway password**
- Initial pairing detection + helper to approve pairing requests
- Keep-alive watchdog based on `tick` events
- Automatic reconnect with connection status callback hook
- Example API call: `health`

## Run

```bash
cd java-gateway-client
OPENCLAW_GATEWAY_TOKEN="..." ./gradlew run
```

If you do not use a token, set `OPENCLAW_GATEWAY_PASSWORD` instead.

Optional:

- `OPENCLAW_GATEWAY_URL` (default: `ws://127.0.0.1:18789`)

## Important notes

- This is intentionally structured as an application project (not Maven-published library packaging).
- Pairing is only required once per client identity. If the server replies with `pairing required`, catch `PairingRequiredException` and call `approvePairing(requestId)`.
