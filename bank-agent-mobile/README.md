# Bank Agent Mobile

Cross-platform (iOS + Android + web) client for the `bank-agent` Spring AI backend,
built with **Expo / React Native + TypeScript**. One codebase, all features the web UI
has: chat, conversation history shared with web, uploads, voice input/output, live
agent status and trace.

## Quick start

```bash
npm install
# Start the backend first (bank-agent/): MCP server on 8096, app on 8095.
# For a physical device, set a reachable URL (see below), then:
npm start          # scan the QR with Expo Go, or press i / a for simulator
```

Set the backend URL either:
- in **Settings → Backend** in the app (persisted locally), or
- with an environment variable at build time: `EXPO_PUBLIC_API_BASE_URL=https://…`

### Backend URL rules
- Default (no setting/env): `http://localhost:8095` on iOS/web, `http://10.0.2.2:8095` on Android emulator
- iOS Simulator: `http://localhost:8095`
- Android Emulator: `http://10.0.2.2:8095`
- Physical phone on same Wi‑Fi: `http://<your-mac-lan-ip>:8095` (allow incoming
  connections in macOS firewall; backend must bind to `0.0.0.0`)
- Anywhere: an HTTPS tunnel, e.g. `ngrok http 8095` → `https://…`

`localhost` on a physical phone points at the phone, not your Mac — don't use it there.

## Features

| Feature | Implementation |
|---|---|
| Chat | `POST /api/chat` + polling `GET /api/runs/{runId}` (primary) |
| Live streaming | `GET /api/runs/{runId}/stream` (SSE, optional, falls back to polling) |
| Live status / trace | `active` / `activeRunId` flags + run events/tool calls |
| Conversation list / create / delete | `/api/conversations` endpoints |
| **Web ↔ mobile continuity** | same backend + same `userId` → identical history |
| Markdown answers incl. tables | local parser in `src/lib/markdown.ts` (no WebView) |
| Files / photos / camera | `POST /api/documents`, HEIC→JPEG on iOS, `chat`/`user` scope |
| Voice input | on-device STT (`expo-speech-recognition`) with server `/api/chat/voice` fallback |
| Voice output | on-device TTS (`expo-speech`) + optional server MP3 `/api/runs/{id}/audio` |
| Offline cache | recent conversations cached with AsyncStorage |
| Notifications | local notification when a run completes + remote push via backend `/api/notifications/register` |
| Privacy | biometric app lock (`expo-local-authentication`) |
| Deep links | `bankagent:///conversations/<id>` |
| Accessibility | labels/roles on interactive elements, no fixed font scaling cap |
| Error handling | error boundary + global JS handler → local error log viewable in Connection & tools |
| Auth | token sign-in when backend has `BANK_AUTH_ENABLED=true`; token in Expo SecureStore; server derives `userId` |
| Extra platform | web export (`npm run export:web`) for browsers / other devices |

### Backend auth / push configuration

```bash
# bank-agent/
BANK_AUTH_ENABLED=true BANK_AUTH_TOKENS='alice:tokenA,bob:tokenB,carol:tokenC' \
BANK_PUSH_ENABLED=true BANK_PUSH_URL='https://exp.host/--/api/v2/push/send' \
./run-bank-app.sh
```

With auth enabled, every `/api/**` request needs `Authorization: Bearer <token>` and the
**server** decides the `userId` — a client-supplied `userId` is ignored. The app switches
from the demo picker to a token login screen automatically.

Remote push tokens come from Expo and require an EAS project id (`extra.eas.projectId`
in `app.json`, created by `eas init` with an Expo account). Without it, the app keeps
using local notifications. Store push registrations are in-memory on the backend and
should be persisted for production.

The web demo and all `localhost` workflows still work with auth disabled (the default).

## Scripts

```bash
npm test          # Jest unit tests (41 active; 4 live tests skipped by default)
npm run typecheck # tsc --noEmit
npm run export:ios     # bundle check for iOS
npm run export:android # bundle check for Android
npm run export:web     # bundle check for web

LIVE_API=1 npx jest --runInBand --forceExit   # also runs 4 tests against a live backend
```

## Security note

By default the backend remains in **demo mode** (customer picker, no auth). This is fine
for local development only. Production deployments should set `BANK_AUTH_ENABLED=true`
with real tokens (or replace the token map with OIDC/JWT validation in
`auth/AuthInterceptor.java`) and use HTTPS. Public store release additionally requires
Apple/Google developer accounts and a real EAS project for push delivery.

## Project layout

```
src/app/            expo-router screens (login, conversations, chat, settings, debug)
src/components/     MarkdownView, ChatMessage, ErrorBoundary
src/lib/            api client, config, types, storage, markdown, speech, attachments, notifications
src/state/          AppContext (identity, conversations, settings, toasts)
__tests__/          unit tests for pure logic and the API client
```
