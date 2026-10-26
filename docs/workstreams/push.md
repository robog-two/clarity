# Workstream `push`

Lane: `android/**` (plus a backend change only if the feed needs one, e.g. a cheaper
notification-only event stream; keep it additive).

Push without Google services or extra containers, per "Android app" in `ARCHITECTURE.md`: the app
keeps the SSE feed (`GET /api/v1/events`) open from a foreground service.

## Build

1. **`FeedService`**: a foreground service (type `remoteMessaging`; declare and justify it in the
   manifest) that owns the existing `FeedSync` connection while enabled, with a quiet, low-importance
   persistent notification ("connected to <instance>"), started on boot, app update and when the
   person turns push on; stopped when they turn it off or forget the server. Reconnect on network
   changes (`ConnectivityManager.NetworkCallback`) with the existing backoff and resume by `seq`.
2. **Battery**: ask once for the battery-optimization exemption with a clear explanation
   (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent), from Settings and the end of onboarding; show
   the state in Settings. Without the exemption, keep working and say pushes may be late.
3. **Notifications from events**: the backend stores a notify decision per feed item
   (`feed_item.notify`: push now / show in app / hold / evening batch). Expose it on the wire
   additively (an optional `notify` field on `FeedItem`, default show-in-app) and post a
   notification only for push-now items and urgent held actions (`HeldChanged` for a held item
   with `Urgency.URGENT`), with channels: "urgent" (high), "agent" (default), "evening summary"
   (low). Tapping opens the right screen (existing deep links). Don't notify for items the
   person is currently looking at.
4. **Phone actions without delay**: while the service runs, also run the phone connector's action
   long-poll (do-not-disturb) so actions arrive in seconds instead of the 15-minute WorkManager slot.
5. **Polish from app-screens**: tapping a Held row for an email draft opens draft review (with
   approve) instead of the amend view; amend stays available from there.
6. **Fallback**: with push off, keep the periodic WorkManager sync and sync on app open.

## Tests

JVM: start/stop decisions (setup state, setting, forget), notification decision mapping (each
notify value × urgency × screen in front), channel selection, reconnect policy on network
changes, action loop start/stop with the service. Backend test if you add the wire field.
`./gradlew assembleDebug testDebugUnitTest lint` in `android/` must pass.
