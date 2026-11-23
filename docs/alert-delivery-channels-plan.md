# Plan: alert delivery channels (Web Push PWA, Telegram, Discord, webhooks)

Status: **built** (2026-11-23). PRs 0–5 merged as #69–#75; PR 6 (installable app) #76, PR 7 (Web Push) #77 and PR 8
(chat summaries and quiet hours) followed. Deployment settings: `docs/security-deployment.md`, section 4. Summaries
are offered for Telegram and Discord only: a webhook receiver gets every alert as its own request.

Paths are relative to `src/main/java/org/example/stockwatch247/` unless they start with `src/` or `docs/`.
"Assumption" marks anything not confirmed in the code (mostly provider behaviour, checked against public docs at
implementation time).

## How this plan was chosen

Four planners each wrote a full plan for both features from the same brief. They worked alone and read-only. An
independent judge, who wrote none of them, checked their claims against the code and the provider docs. It then
scored the plans anonymously (A–D):

| Criterion (weight) | A | B | C (this plan) | D |
|---|---|---|---|---|
| Correctness against the codebase (25%) | 7 | 7 | 8.5 | 8.5 |
| Security (20%) | 7.5 | 8.5 | 9 | 8.5 |
| Reliability: retries, missed runs, several instances (20%) | 8 | 8.5 | 8.5 | 7.5 |
| User experience (15%) | 7.5 | 8.5 | 8 | 8 |
| Effort and risk (10%) | 6.5 | 8 | 7.5 | 7.5 |
| Fit with the project (10%) | 8 | 8 | 9 | 8.5 |
| **Weighted total** | **7.4** | **8.0** | **8.5** | **8.1** |

This document is plan C, with the judge's corrections and borrowings from the other plans worked in. Changed or
added passages carry a tag: **[J]** for a judge correction, **[from A]**, **[from B]** or **[from D]** for a part
taken from another plan.

Decisions the user already made:
1. A public HTTPS domain is planned but not live. Everything must also run and be testable on `localhost`.
2. Telegram, Discord and webhook messages carry the same detail as the email (pattern, entry, stop, target, link).
3. VAPID keys are generated once and supplied as secret-manager environment variables, like
   `MFA_ENCRYPTION_KEY`. Production refuses to enable push without them.
4. The order of delivery was left to the judge (section 9).

## 0. Summary

- One new layer sits beside the email outbox. Each alert sender in `AlertNotificationService` builds a
  channel-neutral **`AlertNotice`** next to its email. **`ChannelFanout`** writes one encrypted row per matching
  channel into a new durable **channel outbox** (`alert_channel_deliveries`), in the same transaction as the event
  and its email. Per-type **senders** (Telegram, Discord, webhook, Web Push) drain the outbox with the same
  lease/fence/backoff pattern as `EmailOutboxService`.
- Email stays as it is, apart from the PR 0 bug fix. Summaries, same-point batching and the pause switch keep their current behaviour for
  email. Channels get their own equivalents, described in section 1.4.
- Recommended order **[J]**: fix the custom-alert pause bug → core (dark) → hook-in → settings tab → **Discord** →
  **Telegram** → **webhooks** → PWA shell → **Web Push** → chat summaries and quiet hours. Chat channels go
  first because they work end to end on `localhost` and on real phones today. Push to phones, and iOS in
  particular, needs the public HTTPS domain, which is not live yet.

## 1. Architecture

### 1.1 What exists (grounding)

| Piece | Where | What matters for channels |
|---|---|---|
| Email composition | `service/AlertNotificationService.java` | Every alert email is built as English subject + body text, then `localize()` adds links and translates line by line (`:66-88`). Choke points: `queueInitial` `:106-114`, `queueFollowUp` `:210-221`, `queueLifecycle` `:223-226`, developing Elliott `:558-570`, outlook `:1731-1751`, chart `:1757-1771`, custom `:1777-1788`, congress `:1583-1640`, insider `:1642-1707`. |
| Email-only gates | same file | `emailEnabled` (SMTP configured) and the email preferences gate every sender before anything is queued, e.g. `:470`, `:554`, `:666`, `:853`, `:1625-1627`, `:1691-1693`, `:1733`, `:1759`, `:1778`. Channels must not depend on these. |
| Durable outbox | `service/EmailOutboxService.java` | Encrypted payload with AAD context `:72-81`; `on conflict (deduplication_key) do nothing` `:78`; claim with `for update skip locked` + lease + owner fence `:299-311`, `:348-352`; backoff `:422-428`; hourly expiry/purge, 30 days `:393-405`; pause applied at send time `:324-331`. |
| Same-point batch | `service/SamePointEmailBatch.java`, `AlertNotificationService.java:117-204`, opened in `ScheduledAlertService.java:447` | A thread-local batch holds new-signal emails for 5 minutes (`SAME_POINT_HOLD`), then combines per `SamePointSignals.Point` (`service/SamePointSignals.java:87-96`). |
| Summary (digest) | `EmailOutboxService.java:188-290`, `V94__alert_email_digests.sql` | Holds signal, lifecycle and outlook emails until the account's checks have been quiet for 10 minutes, at most 1 hour (`:217-231`). |
| Pause switch | `users.notification_emails_paused` (`V88`), `NotificationEmailLinks.java:62`, settings `templates/settings.html:81-87` | Email only, set by the RFC 8058 one-click link. |
| Event + email atomicity | `ScheduledAlertService.java:1677-1686` (`publishEvent`) | The event and its outbox row share one transaction. The channel rows join it. |
| Watchlist filter | `service/WatchlistEmailPolicy.java:18-31` | Mixes two questions: does a list follow this signal, and is the list's **email** switch on (`p.email_enabled`, `:29`). |
| Email preferences | `service/AnalysisPreferencesService.java:166-220`, `:517-570` | Per family, outcome, direction and kind. These are email choices (UI labels say "email"). |
| Workers, crypto | `service/BackgroundJobDispatcher.java:19-21`; `service/SecurityCryptoService.java:54` (AES-GCM + AAD), `:82` (HMAC) | Reused as they are. |

### 1.2 New components (package `service.channel`)

| Component | Responsibility |
|---|---|
| `AlertKind` enum | `SIGNAL_CANDLESTICK, SIGNAL_ELLIOTT, SIGNAL_HARMONIC, OUTCOME_CONFIRMED, OUTCOME_INVALIDATED, OUTCOME_EXPIRED, OUTLOOK, CHART, CUSTOM, INSIDER, CONGRESS`. These mirror the email switches in `analysis-settings.html:26-38`. |
| `AlertNotice` record | `userId, kind, direction?, symbol?, signalType/interval (for the watchlist follow check), subjectEn, bodyEn, viewPath, deduplicationKey, alertEventId?, outlookNotificationId?, groupKey?, facts` (structured: pattern, family, interval, entry, stop, target, score, signal period, outlook from/to, chart label/level, custom alert name, insider/congress actor and activity). |
| `ChannelFanout` | `@Transactional publish(AlertNotice)`. It loads the user's `ACTIVE` channels that accept the kind and direction, applies the watchlist follow check, localizes once per language, encrypts one payload per channel and inserts the rows `on conflict do nothing`. It also exposes `releaseSamePoint(eventIds)`. |
| `ChannelStore` | JDBC CRUD for channels; lookup hashes; status transitions; quotas. |
| `ChannelDeliveryWorker` | Two scheduled loops on new dispatcher queues: `channels` (Telegram, Discord, push) and `webhooks` (user endpoints, so slow targets cannot hold up the others). Claim, group, send, record the result, hourly cleanup. |
| `ChannelSender` SPI | `SendResult send(ChannelConfig, List<RenderedNotice>)`, implemented by `TelegramSender`, `DiscordSender`, `WebhookSender`, `WebPushSender`. `SendResult` is one of `DELIVERED`, `RETRY(after)`, `RATE_LIMITED(after, scope)`, `CHANNEL_GONE(reason)`, `MESSAGE_REJECTED(reason)`, `CONFIG_ERROR(reason)`. |
| Formatters | `TelegramFormatter` (HTML), `DiscordFormatter` (embeds), `WebhookPayload` (JSON v1), `PushPayload` (short). |
| `GuardedHttpClient` + `OutboundTargetPolicy` (`security/`) | One outbound HTTP stack with SSRF protection (section 5.4). It is used for every provider, user-supplied or not. |
| Controllers | `AlertDeliverySettingsController` (forms), `PushSubscriptionController` (JSON), `PwaController` (manifest, `sw.js`, offline page). |

**Why a sibling outbox and not more rows in `email_outbox`:** that table is SMTP-shaped: a `from`/`to` payload
(`EmailOutboxService.java:430`), MIME building, receipts in `alert_events` (`:407-420`) and one SMTP backoff
(`:34`). Channels need per-channel pacing, provider blocks, auto-disable and expiry. A separate table leaves the
email path unchanged and still copies its proven claim/fence SQL.

### 1.3 Fan-out of one alert

```
check thread ─ publishEvent tx ─┬─ alert_events row (exists)
                                ├─ email: queueInitial → email_outbox / email_digest_items (unchanged)
                                └─ channels: ChannelFanout.publish(notice)
                                      for each ACTIVE channel of the user that accepts kind + direction:
                                        alert_channel_deliveries(channel, dedupe key, encrypted localized payload,
                                                                 available_at, expires_at, group_key)
channels worker ─ claim due row (+ same channel/group siblings) → render per type → send → record result
```

The email and the channel rows share the deduplication key (`initial-alert:{eventId}`,
`follow-up:{eventId}:{transition}`, `outlook:{id}`, `chart-alert:{id}`, `custom-alert:{alertId}:{lastEventId}`,
plus new `congress:{deliveryId}` and `insider:{deliveryId}`). Uniqueness is per `(channel_id, deduplication_key)`.

### 1.4 How channels coexist with the email features

| Feature | Email | Channels |
|---|---|---|
| Pause all alert emails | Unchanged: email only. Its unsubscribe link legally covers email. | Each channel has Pause/Resume, plus "Pause all channels" on the new page. The note on the analysis tab changes to say the pause covers email only (`settings.alertEmails.onNote`/`pausedNote`). |
| Summary per check run | Unchanged | PR8: `delivery_mode = SUMMARY` for Telegram, Discord and webhooks. **[from D]** Rows are written with `held = true` and `group_key = 'summary:<channel id>:<first delivery id>'` and released by the `flushDigests` timing rules (10 minutes quiet, at most 1 hour) for the same kinds (signal, lifecycle, outlook). The message is compact: at most 40 lines of subject plus link. This reuses the same-point grouping below instead of a second mechanism. Push never summarizes. It coalesces instead (section 6). |
| Floods after a recovered run | n/a | **[from B]** Roll-up guard: when more than 8 deliveries are due at once for one Telegram or Discord channel, the worker sends one list message (up to 20 linked subjects, then "and N more") and marks them all delivered. A recovered monthly run can otherwise produce dozens of messages. |
| Same-point batching | Unchanged | New signals get `group_key = sha256(Point)` and are held while the check's batch is open (`available_at = now + SAME_POINT_HOLD`); `sendSamePoint` also calls `channels.releaseSamePoint(eventIds)`. The worker claims due siblings with the same `(channel_id, group_key)` and sends one message: one Discord embed per signal (≤10), one Telegram message (split at 4096), one push. Webhooks get one POST per alert with `group {key, size, index}`. Grouping at send time also works when the check dies. |
| Email category switches (`AnalysisPreferencesService.EmailPreferences`) | Unchanged | Not used. Each channel has its own kinds and directions, defaulting to a copy of the current email choices when the channel is created. |
| Watchlist settings | `allows()` = list follows the signal **and** the list's email switch is on | New `WatchlistEmailPolicy.follows(...)`: the same SQL without `p.email_enabled`. Channels respect what lists follow, not the list's email switch (decision 2). |
| Dashboard notifications | Unchanged | Not affected. Clicking a channel link opens `/alerts/signals/{id}` etc., which marks the alert read as today. |
| SMTP not configured | No alert emails | Channels still work. `emailEnabled` gates only the email branch. |

## 2. Data model and Flyway migrations

### V104__alert_channels.sql (PR1)

```sql
-- Where alerts go besides email and the website. Endpoints, chat ids, keys and signing secrets are encrypted
-- (SecurityCryptoService, context "alert-channel:<public_id>"); lookup_hash is a keyed HMAC for uniqueness
-- and inbound lookups (Telegram /stop, push re-subscription), never the raw value.
create table alert_channels (
    id bigint generated by default as identity primary key,
    public_id uuid not null unique,
    user_id bigint not null references users(id) on delete cascade,
    channel_type varchar(16) not null,
    label varchar(80) not null,
    display_hint varchar(120),                -- non-secret: "discord.com · …/1234", "hooks.example.com", "Chrome on Android"
    provider_scope varchar(80),               -- 'telegram', 'discord', 'push:fcm.googleapis.com'; null for webhooks
    status varchar(16) not null default 'ACTIVE',
    alert_kinds text[] not null,
    directions text[] not null default array['BUY','SELL'],
    delivery_mode varchar(8) not null default 'EACH',
    language varchar(8),                      -- null = the account's language
    config_ciphertext text,
    config_iv text,
    lookup_hash varchar(64),
    available_at timestamptz not null default current_timestamp,  -- pacing / Retry-After; one sender per channel
    consecutive_failures integer not null default 0,
    first_failure_at timestamptz,
    last_success_at timestamptz,
    last_failure_at timestamptz,
    last_failure_code varchar(40),
    disabled_reason varchar(40),
    disabled_at timestamptz,
    notice_queued_at timestamptz,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    constraint ck_alert_channel_type check (channel_type in ('PUSH','TELEGRAM','DISCORD','WEBHOOK')),
    constraint ck_alert_channel_status check (status in ('PENDING','ACTIVE','PAUSED','FAILING','REVOKED')),
    constraint ck_alert_channel_mode check (delivery_mode in ('EACH','SUMMARY')),
    constraint ck_alert_channel_language check (language is null or language in ('en','pl')),
    constraint ck_alert_channel_kinds check (alert_kinds <@ array['SIGNAL_CANDLESTICK','SIGNAL_ELLIOTT',
        'SIGNAL_HARMONIC','OUTCOME_CONFIRMED','OUTCOME_INVALIDATED','OUTCOME_EXPIRED','OUTLOOK','CHART','CUSTOM',
        'INSIDER','CONGRESS']::text[]),
    constraint ck_alert_channel_directions check (directions <@ array['BUY','SELL']::text[]),
    constraint ck_alert_channel_secret check (status = 'REVOKED' or config_ciphertext is not null)
);
create index alert_channels_active on alert_channels(user_id) where status = 'ACTIVE';
create unique index ux_alert_channels_target on alert_channels(user_id, channel_type, lookup_hash)
    where status <> 'REVOKED';
-- A browser's push endpoint belongs to one account: subscribing from another account moves it.
create unique index ux_alert_channels_push on alert_channels(lookup_hash)
    where channel_type = 'PUSH' and status <> 'REVOKED';
```

Encrypted `config` JSON per type: PUSH `endpoint, p256dh, auth, expirationTime`; TELEGRAM `chatId` (treated as
personal data), `username?`; DISCORD `webhookUrl` (a bearer credential); WEBHOOK `url, secret, previousSecret?,
previousSecretUntil?`.

### V105__alert_channel_deliveries.sql (PR1)

```sql
create table alert_channel_deliveries (
    id uuid primary key,                      -- also the webhook-id / push tag, stable across retries
    channel_id bigint not null references alert_channels(id) on delete cascade,
    deduplication_key text not null,
    alert_kind varchar(24) not null,
    group_key varchar(80),
    alert_event_id bigint references alert_events(id) on delete cascade,
    outlook_notification_id bigint references technical_outlook_notifications(id) on delete cascade,
    -- [from B] every source row gets a foreign key, so deleting it removes its pending channel rows
    chart_alert_event_id bigint references chart_alert_events(id) on delete cascade,
    custom_alert_event_id bigint references custom_alert_events(id) on delete cascade,
    insider_delivery_id bigint references insider_trade_deliveries(id) on delete cascade,
    congressional_delivery_id bigint references congressional_trade_deliveries(id) on delete cascade,
    ciphertext text,                          -- localized notice JSON, context "channel-delivery:<id>"; erased when done
    iv text,
    created_at timestamptz not null default current_timestamp,
    available_at timestamptz not null default current_timestamp,
    expires_at timestamptz not null,
    held boolean not null default false,      -- [from D] waiting for a summary (PR8)
    owner uuid,
    lease_until timestamptz,
    attempts integer not null default 0,
    parts_sent smallint not null default 0,   -- [from A] committed after each Telegram message / Discord embed batch
    provider_message_id varchar(64),          -- [from B] Telegram message_id, so follow-ups reply to the signal
    delivered_at timestamptz,
    expired_at timestamptz,
    outcome varchar(24),                      -- DELIVERED, EXPIRED, REJECTED, CHANNEL_GONE, PAUSED, FILTERED
    constraint ux_alert_channel_delivery unique (channel_id, deduplication_key),
    constraint ck_alert_channel_delivery_single check (num_nonnulls(alert_event_id, outlook_notification_id,
        chart_alert_event_id, custom_alert_event_id, insider_delivery_id, congressional_delivery_id) <= 1)
);
create index alert_channel_deliveries_pending on alert_channel_deliveries(available_at)
    where delivered_at is null and expired_at is null;
create index alert_channel_deliveries_group on alert_channel_deliveries(channel_id, group_key)
    where delivered_at is null and expired_at is null;
create index alert_channel_deliveries_event on alert_channel_deliveries(alert_event_id)
    where alert_event_id is not null and delivered_at is null and expired_at is null;
create index alert_channel_deliveries_created on alert_channel_deliveries(created_at);

-- A provider-wide block shared by all instances (Telegram flood control, Discord global 429, a push service outage).
create table alert_channel_provider_state (
    scope varchar(80) primary key,
    blocked_until timestamptz not null,
    reason varchar(40),
    updated_at timestamptz not null default current_timestamp
);
```

### V106__telegram_linking.sql (PR4)

```sql
create table telegram_link_requests (
    token_hash char(64) primary key,          -- SHA-256 of the one-time start token; the token is never stored
    user_id bigint not null references users(id) on delete cascade,
    created_at timestamptz not null default current_timestamp,
    expires_at timestamptz not null,
    used_at timestamptz,
    channel_id bigint references alert_channels(id) on delete cascade
);
create index telegram_link_requests_user on telegram_link_requests(user_id, created_at);

-- One instance at a time long-polls getUpdates; the offset survives restarts.
create table telegram_bot_state (
    id smallint primary key default 1 check (id = 1),
    next_update_id bigint not null default 0,
    lease_owner uuid,
    lease_until timestamptz
);
insert into telegram_bot_state(id) values (1);
```

No other migrations are needed. Push, webhooks and summaries use the V104 columns. If PR order changes, the
numbers follow merge order, because Flyway needs them to increase.

### Retention and cleanup (hourly, `ChannelDeliveryWorker.cleanup`, same shape as `EmailOutboxService.java:393-405`)

- A delivered or expired row loses its ciphertext at once and is deleted 30 days later.
- Pausing or removing a channel expires its pending rows (`outcome = PAUSED`), so resuming sends no burst of stale
  alerts.
- A `REVOKED` channel's config is erased at once; the row (label, reason) stays 30 days so the page can explain.
- Telegram link requests and provider-state rows are deleted 1 day after they expire.
- Account deletion cascades from `users` (`AccountDeletionService.java:106`). While a deletion is pending, fan-out
  and the worker skip the account.

## 3. Settings UI

### 3.1 Placement

- A new tab, `/settings/alert-delivery`, labelled "Alert delivery" / "Dostarczanie alertów". It goes in the
  "Analysis and alerts" nav group right after "Analysis & Alerts" (`templates/settings.html:19-26`). It is added to
  the mapping list and tab switch in `controller/SettingsController.java:71-83` (`settingsTab = "delivery"`).
  Form POSTs live in a new `AlertDeliverySettingsController` and redirect back with flash messages, which
  `settings.html:33` translates through `ServerMessages`.
- The email card (`settings.html:81-98`) stays where it is, because `NotificationEmailLinks.MANAGE_PATH` points at
  that tab. It gains a line: "Get alerts in Telegram, Discord, a webhook or on this device → Alert delivery". The
  new tab's first row is a read-only **Email** summary (On/Paused, one-by-one/summary) linking back.
- Public ids (UUID) appear in URLs. Every action checks ownership and answers 404 for a channel the user does not own.

### 3.2 Cards and flows

| Card | Content and flow |
|---|---|
| **01 This device** (push; shown when `push.enabled`) | JS shows one state: *Not supported*; *iOS, not installed* ("add StockWatch to your Home Screen (Share → Add to Home Screen), open it from there and sign in, then turn notifications on"); *Ask* → **Turn on notifications on this device** (the permission prompt only ever follows this click); *Denied* → how to allow them again in site settings; *On* → **Send test** / **Turn off on this device**; *Ended* → "Your browser ended notifications for this device. Turn them on again." An **Install app** button appears if `beforeinstallprompt` fired. |
| **02 Your channels** | One row per channel: inline SVG icon, label (editable), type, status pill (*Active*, *Paused*, *Waiting for confirmation*, *Not working*, *Stopped by Discord/Telegram*), and last activity ("Last sent 2 h ago", or "Last try 10:42: Discord says this webhook no longer exists"). 30-day counts: delivered / waiting / failed. Actions: **Send test**, **Pause/Resume**, **Choose alerts** (native `<details>` with the 11 kinds + Buy / Sell/Short + language select + "Same as my email choices" preset), **Remove** (confirm `<dialog>`, reusing `.settings-dialog`). Bulk: **Pause all channels** / **Resume all**. |
| **03 Add a channel** | Three options, each a small form or button. |
| — Telegram | **Link Telegram** → POST creates a link request → the page shows **Open Telegram** (`https://t.me/<bot>?start=<token>`, `rel="noopener noreferrer"`) and a QR code of the same link for desktop-to-phone (reuses `TotpService.qrDataUri`, `service/TotpService.java:39`; `img-src data:` is allowed). "Waiting for you to press Start in Telegram (link valid for 15 minutes)". JS polls `GET /api/alert-delivery/telegram/link-status` every 4 s, stops when the tab is hidden, gives up after 15 min. When the bot has the chat: "Telegram chat @name wants StockWatch alerts. **This is me** / **Not me**". The channel becomes ACTIVE only after this site-side confirmation (section 5.3). Without JS: a **Check again** button reloads. |
| — Discord | Label + webhook URL input (`inputmode="url"`, `autocomplete="off"`), plus help: "Server Settings → Integrations → Webhooks → New Webhook → Copy Webhook URL. Everyone in that Discord channel will see your alerts." Submit → host/path validation → `GET` on the webhook (expects 200 and returns the webhook's name) → ACTIVE, with the label defaulting to the webhook name. |
| — Webhook | Label + HTTPS URL → created as `PENDING`. The next page shows the **signing secret once** (copy button, "You won't see it again"), payload docs and **Send test**. A 2xx response activates the webhook. **Rotate secret** shows a new one once; the old one keeps signing for 24 h. |
| Recent deliveries **[from B]** | Each channel row expands to its last 10 deliveries: time, kind, symbol, outcome ("Delivered", "Waiting: Discord asked us to slow down", "Not sent: the alert was removed"). It reads the delivery rows kept for 30 days and shows no message content. |

**One-time values are never flashed [from A].** The webhook signing secret and the Telegram deep link with its
token are rendered directly in the POST response, with `Cache-Control: no-store`. They are never put in a flash
attribute: since V96, flash attributes are written to the JDBC session table. Reloading the page shows the
channel without the secret.

**Every new channel is recorded [from A].** Adding or confirming a channel writes a `NOTIFICATION_CHANNEL_ADDED`
security event, shown under Sessions and activity. It also queues a security notice email ("Telegram chat @name
now receives your StockWatch alerts. Not you? Remove it."). The call is wrapped in try/catch, as at
`AccountSecurityService.java:324`, because `sendSecurityNotice` throws when email is not configured
(`AlertNotificationService.java:297-298`). This gives an after-the-fact signal if a link was hijacked.

**Empty state:** "Alerts reach you by email and on the website. Add Telegram, Discord, a webhook or this device to
get them elsewhere too."

**Error copy (examples, EN+PL):** "That isn't a Discord webhook URL. It starts with
https://discord.com/api/webhooks/."; "Discord doesn't know this webhook. Copy it again."; "The address points to a
private network, which StockWatch can't reach."; "The webhook redirected. Use the final address."; quota and
too-many-tests messages.

**Revoked/failing rows** always offer a fix: *Link again*, *Replace URL*, *Retry now* (sets ACTIVE and sends a
test), *Remove*.

### 3.3 Phone layout and weak GPUs

- Cards stack, as on the existing settings page. A channel row is a 3-column grid (icon | text | actions) that
  wraps to 2 rows at ≤600 px with actions below. Buttons are at least 44 px high and full width at ≤480 px. Hints
  and URLs use `overflow-wrap: anywhere`.
- No blur, shadows-on-scroll, transitions or spinners beyond a static "Waiting…" text. `<details>` opens natively.
- No inline styles: `style-src-attr 'none'` applies to `/settings` (`CspNonceFilter.java:26-30`). The new CSS goes
  in `static/css/app.css` with existing tokens.
- New JS: `static/js/alert-delivery-settings.js` (deferred). Strings come from `js.delivery.*` keys through
  `window.StockWatchI18n` (`i18n/I18nScriptController.java:31,60`). The CSRF token comes from a data attribute, as
  in `templates/fragments/navbar.html:48-49`.

## 4. Pipeline hook-in

### 4.1 Pattern applied to every sender

Today each sender returns early when the email gate fails (e.g. `AlertNotificationService.java:470-474`). Each
sender changes to:

```java
boolean email = <existing email gate>;                         // unchanged expression
boolean channel = channels != null && channels.wants(notice);  // kind, direction, watchlist follow; no SMTP
if (!email && !channel) return false;
String subject = ..., body = ...;                              // English, captured before localize() mutates the message
if (email) { /* existing message + queue* call, unchanged */ }
if (channel) channels.publish(notice(subject, body, viewPath, key, event));
return email;                                                  // callers' email-receipt logic stays as it is
```

`channels` is `@Autowired(required = false) ChannelFanout`, the same style as `outbox` and `links`
(`:42-55`). `notice(...)` builds the facts from `AlertEvent`: trade entry, stop and target, score, signal period
via `SignalPeriodFormatter`, `patternEnd()`. A golden test pins the email text so the refactor provably changes
nothing for email.

### 4.2 Per alert kind

| Sender (file:line) | Called from | Channel kind | Same-point? | Notes |
|---|---|---|---|---|
| `sendSignalEmail` `AlertNotificationService.java:379-483` → `queueInitial` | `ScheduledAlertService.java:1568`, `:1668` | `SIGNAL_CANDLESTICK` / `SIGNAL_ELLIOTT` | Yes | `group_key` from `SamePointSignals.Point.of(event)`. |
| `sendHarmonicSignalEmail` `:688-762` | via `sendSignalEmail` `:380-382` | `SIGNAL_HARMONIC` | Yes | |
| `sendDevelopingElliottEmail` `:493-571` | `ScheduledAlertService.java:1037`, `:1332` | `SIGNAL_ELLIOTT`; invalidation → `OUTCOME_INVALIDATED` | Stage updates yes, invalidations no (mirrors `:567-569`) | Key as at `:564`. |
| `sendElliottTradePlanOutcomeEmail` `:606-680` → `queueFollowUp` | `ElliottTradePlanService.java:334` | `TARGET_REACHED` → `OUTCOME_CONFIRMED`, else `OUTCOME_INVALIDATED` (mapping `:663-665`) | No | |
| `sendHarmonicStopOutcomeEmail` `:815-869` | `HarmonicStopPlanService.java:192,214,240,313` | same mapping as `:855` | No | |
| `sendSignalLifecycleEmail` `:1031…` → `queueLifecycle` (6 call sites `:1182,1210,1301,1403,1433,1579`) | `CandlestickSignalLifecycleService.java:434` | DETECTED/CONFIRMED → `OUTCOME_CONFIRMED`; INVALIDATED → `OUTCOME_INVALIDATED`; REJECTED/EXPIRED → `OUTCOME_EXPIRED` (mapping `AnalysisPreferencesService.java:183-189`) | No | The hook goes once inside `queueFollowUp` (`:210`), which all of these reach, with the kind passed down. `quiet` and projection suppression (`CandlestickSignalLifecycleService.java:430-434`) keep applying because they act before the sender. |
| `sendTechnicalOutlookChangeEmail` → `sendOutlookEmail` `:1731-1751` | `TechnicalOutlookTrackingService.java:372` | `OUTLOOK` | No | FK `outlook_notification_id`. The watchlist follow check uses type `OUTLOOK`, direction `ANY` (as at `:1720`). |
| `sendChartAlertEmail` `:1757-1771` | `ChartAlertService.java:515` | `CHART` | No | `ChartAlertService.email` (`:500-521`) already catches failures. The caller marks `email_queued_at` only when email was queued, so the method returns `email`. |
| `sendCustomAlertEmail` `:1777-1788` | `CustomAlertEngine.java:657` (`sendDigest` `:634-673`) | `CUSTOM` | No (already one notice per alert per check) | **[J]** The alert's own switch becomes **"Notify me"** (email and channels) and is relabelled in the builder. Today events are inserted as `NONE` when the switch is off (`CustomAlertEngine.java:444`), and the sweeper reads only `PENDING` rows (`:619`, `:640`). So keeping the switch email-only would mean channels never receive those alerts. With "Notify me", `allowed` at `:647-648` splits: the alert's switch gates both branches, and `allowsCustomAlertEmail` gates email only. `email_status` becomes `QUEUED` when either branch queued. |
| `sendCongressionalTradeEmail` `:1583-1640` | `CongressionalActivityPollingService.java:181-207` | `CONGRESS` | No | The worker returns early without SMTP (`:182`). Change it to run when email **or** channels are enabled. Publish the channel notice (`congress:{deliveryId}`) before the direct SMTP send, so an SMTP failure retry (`markDeliveryFailed`) is deduplicated for channels. |
| `sendInsiderTradeEmail` `:1642-1707` | `InsiderActivityService.java:394-426` | `INSIDER` | No | Same: the SMTP gate at `:412` is split; the channel publish (`insider:{deliveryId}`) happens first. |

Same-point plumbing:
- `openSamePointBatch` (`:124-126`) also opens when `channels != null`.
- `queueForSamePoint` (`:129-135`) is unchanged for email. **[J]** `SamePointEmailBatch.current()` and `hold()`
  are package-private in `service`, and `ChannelFanout` lives in `service.channel`. Make both public, or add a
  small bridge class in `service`. `ChannelFanout.publish` then calls `current().hold(event, key)` with the shared
  `initial-alert:` key and holds its own row. The batch flushes only when `held` is non-empty
  (`SamePointEmailBatch.java:46-50`). Holding through the batch therefore also makes `sendSamePoint` run for
  accounts whose email is off.
- `sendSamePoint` (`:141-169`) calls `channels.releaseSamePoint(allHeldEventIds)` in a `finally`.

Account and security emails (`:279-325`) never go to channels.

### 4.3 Send-time re-checks (like `EmailOutboxService.java:317-321`)

At claim, the worker drops a row (`outcome = FILTERED`) when:
- the channel is no longer ACTIVE, or no longer accepts the kind;
- the event was deleted (FK cascade, or `deleted_at` for soft-deleted kinds);
- the account has a pending deletion;
- the list no longer follows the signal (`WatchlistEmailPolicy.follows`).

### 4.4 Missed runs, retries and restarts

- **Recovered schedule runs** (`AlertScheduleRecoveryService`, `docs/security-deployment.md:67`) create events
  through the same `publishEvent`, so channels fan out exactly as email does. **Retried checks**
  (`ScheduledAlertService.java:401`) reuse the same keys. Event uniqueness (`V1__initial_schema.sql:70`) plus
  `(channel_id, deduplication_key)` prevent duplicates in both cases.
- **App or provider down:** rows stay pending and any instance resumes once leases (2 min) lapse. Rows past
  `expires_at` are expired, not sent. A message delivered more than 1 h after `created_at` gets the footer
  "Delivered late (queued 14:02)"; push sends it with `Urgency: low`.

| Channel | Default lifetime | Reason |
|---|---|---|
| Push | 24 h (also sent as the `TTL` header) | A day-old "entry at next open" notification misleads. |
| Telegram / Discord | 48 h | |
| Webhook | 72 h | Receivers may be automation that wants catch-up. |

## 5. Security

### 5.1 CSP, service worker, manifest

- **No CSP change is needed** (`security/CspNonceFilter.java:31-35`). Service-worker scripts fall back from
  `worker-src` to `script-src`, which allows same-origin `/sw.js`. `manifest-src` falls back to `default-src
  'self'`. Icons are same-origin. `pushManager.subscribe()` is not governed by `connect-src`: the browser talks to
  its push service itself. Page and worker fetches are same-origin only.
- `/sw.js` is served by `PwaController` at the root (scope `/`, so no `Service-Worker-Allowed` header) with
  `Cache-Control: no-cache`. With `pwa.enabled=false` it serves a **kill-switch worker** that unregisters itself
  and deletes its caches.
- The worker caches no authenticated pages: they hold financial data, and devices may be shared. It precaches only
  `/offline` and the icons. It handles `push`, `notificationclick` (same-origin paths starting with `/` only) and
  `pushsubscriptionchange`.
- **Fetch handler [J].** Chrome installs without a fetch handler only from its menu. The automatic install prompt
  (`beforeinstallprompt`, which drives the **Install app** button) still needs a real one. The handler: navigation
  requests go to the network, and only a network error falls back to the precached `/offline`. No other request is
  intercepted and no response is cached. The PWA PR verifies that `beforeinstallprompt` fires in Edge and Chrome.
- `SecurityConfig.java:62-67` permits `/manifest.webmanifest`, `/sw.js`, `/icons/**` and `/offline`. Manifest
  fetches carry no credentials, so the manifest must be public. Permissions-Policy (`:98-99`) is unchanged:
  notifications are not a policy-controlled feature.

### 5.2 Push subscription endpoint and CSRF

- `POST /api/push/subscriptions` (upsert), `/api/push/subscriptions/remove` and `/api/push/test` are
  authenticated and use ordinary Spring CSRF through the `X-CSRF-TOKEN` header. There is no exemption, unlike
  `/email/unsubscribe`. JSON bodies are capped by `RequestBodySizeLimitFilter`.
- **Validation:** `endpoint` is HTTPS, ≤ 2048 characters, and its host is on the allowlist
  `push.allowed-endpoint-hosts` (`fcm.googleapis.com`, `updates.push.services.mozilla.com`, `*.push.apple.com`,
  `*.notify.windows.com`; assumption: verify at implementation). The allowlist is also the SSRF defence for this
  user-supplied URL. **[from D]** Push endpoints also pass `OutboundTargetPolicy` (section 5.4) on every send, in
  addition to the allowlist. `p256dh` must decode to 65 bytes and be a valid P-256 point; `auth` must decode to 16
  bytes.
- **Re-sync without a CSRF exemption:** `static/js/push-sync.js` runs on app pages for signed-in users. When
  permission is granted and a subscription exists, it re-POSTs it at most once per 24 h (`localStorage`, in
  try/catch). This covers endpoint rotation and `pushsubscriptionchange`. **[J]** The page also renders a
  `data-push-revision` value: the time the server last removed push devices from this account. `push-sync.js` keeps
  the revision it last synced with in `localStorage`. When the two differ (for example after a password change
  removed this device), it re-posts at once and the 24 h throttle does not apply. A browser cannot check a keyed
  lookup hash itself, so a revision is used instead of a per-device "known" flag. The logout form first unsubscribes the device, best effort.
- **Password change, MFA disable and "sign out other sessions"** (`SettingsController.java:361-462`) delete the
  account's push channels **except the current device [J, from A]**: the form carries the current endpoint's hash.
  A signed-out stolen device cannot subscribe again (decision 12).

### 5.3 Telegram account linking

- The token is 32 random bytes as base64url: 43 characters, inside Telegram's 64-character `[A-Za-z0-9_-]` start
  parameter. Only its SHA-256 hash is stored. It expires after 15 min and is single use. An account may have at
  most 3 open requests and create 5 per hour.
- Inbound updates arrive only through outgoing `getUpdates` long-polling (timeout 25 s) by **one instance at a
  time**, holding the `telegram_bot_state` lease (60 s, renewed each poll). The stored offset means a restart or
  failover neither loses nor replays updates. With no inbound endpoint it works on `localhost`.
  **[J]** The 25 s long poll runs on its own daemon thread under the lease. It must not hold one of the 4 shared
  `BackgroundJobDispatcher` threads. **[from A]** The Bot API base URL is configurable only for tests (the fake
  server). The production validator requires `https://api.telegram.org`. A Telegram webhook
  with `secret_token` (header `X-Telegram-Bot-Api-Secret-Token`) is a later option once the domain is live.
- `/start <token>` is accepted only from a `private` chat where `from.id == chat.id`, at most 10 attempts per chat
  per hour. A valid token creates a `PENDING` channel labelled with the chat's `@username`. The bot replies
  "Almost done: confirm on the StockWatch page", and the site asks "This is me / Not me". That confirmation
  closes the **forwarded-link attack**: someone else pressing Start first would otherwise receive the user's
  alerts. An invalid or expired token gets a generic reply, so there is no oracle.
- `/stop`, or `my_chat_member` with status `kicked` (the user blocked the bot), revokes every channel with that
  chat's lookup hash. `/help` explains.
- **No leaks:** tokens are never logged (the poller logs update ids). The deep link opens with `noreferrer`, and
  `Referrer-Policy: same-origin` already applies (`SecurityConfig.java:95-97`). The **bot token sits in the Bot
  API URL path**, so `GuardedHttpClient` logs only host + path template and scrubs `/bot<token>` from exceptions,
  verified by a log test. The bot runs with privacy mode on and groups disabled (decision 6).

### 5.4 SSRF protection for user-supplied URLs

`OutboundTargetPolicy` and `GuardedHttpClient` (Apache HttpClient 5; assumption: version managed by Spring Boot 4.1,
a new dependency):

- **URL rules:**
  - `https` only (`http` only with `alert-channels.webhook.allow-http=true`: dev default, rejected in prod). No
    userinfo or fragment, length ≤ 2048, a DNS host name; IP literals only in dev; port 443 or 8443 in prod (decision 10).
  - Hosts are normalized first (lower-case, trailing dot stripped, IDN → punycode). Backslashes are rejected.
    Decimal, octal and hex IPv4 spellings (`2130706433`, `0x7f.1`) count as IP literals.
  - Discord URLs must match `https://(discord.com|discordapp.com|ptb.discord.com|canary.discord.com)/api/webhooks/{snowflake}/{token}`.
    Telegram uses the fixed `api.telegram.org`. Push uses its allowlist.
- **DNS rebinding:** a custom `DnsResolver` resolves A and AAAA, rejects the request if **any** address is blocked,
  and returns only the validated addresses. The connection uses exactly those, while TLS still verifies the host
  name. Validation repeats on every send, so a webhook whose DNS later points inward fails with `TARGET_BLOCKED`.
- **Blocked ranges:**
  - IPv4: `0/8, 10/8, 100.64/10, 127/8, 169.254/16` (cloud metadata), `172.16/12, 192.0.0/24, 192.0.2/24,
    192.88.99/24, 192.168/16, 198.18/15, 198.51.100/24, 203.0.113/24, 224/4, 240/4`.
  - IPv6: `::/128, ::1, fe80::/10, fc00::/7, ff00::/8, 2001:db8::/32, 100::/64`, plus the IPv4 embedded in
    `::ffff:0:0/96`, `64:ff9b::/96` and `2002::/16`.
  - The host of `PUBLIC_BASE_URL` itself (no loops).
  - `alert-channels.allow-private-targets=true` exists for dev and test (local receivers and fake servers). Prod
    validation rejects it.
- **Requests:** redirects disabled (a 3xx is a failure with a clear message); no cookies, auth caching or system
  proxy; connect timeout 3 s, response 10 s, total 15 s; at most 2 connections per route and 1 request in flight
  per channel (section 6). Response bodies are read up to 64 KiB (providers) or 4 KiB (webhooks), then discarded;
  only the status and a category are stored.
- **Defence in depth (ops):** an egress firewall denies RFC 1918 and metadata addresses from the app host.

### 5.5 Webhook signing (Standard Webhooks style)

- Headers: `webhook-id: <delivery uuid>` (stable across retries so receivers can deduplicate), `webhook-timestamp:
  <unix seconds>`, `webhook-signature: v1,<base64 HMAC-SHA256(secret, id + "." + timestamp + "." + body)>`.
- During a rotation both signatures are sent, space-separated, for 24 h.
- The secret is `whsec_` + base64 of 32 random bytes, generated by the server, shown once, and stored encrypted
  (HMAC needs the plaintext). It is never re-shown, exported or logged.
- `User-Agent: StockWatch-Webhooks/1`. Receivers are told to reject timestamps older than 5 minutes.

### 5.6 Secret storage

Everything goes through `SecurityCryptoService.encrypt`. The AAD context binds each ciphertext to its row
(`alert-channel:<public_id>`, `channel-delivery:<id>`). Lookups use `crypto.sign(value, "channel-lookup:<type>")`.
The VAPID private key and the Telegram bot token come from the secret manager as environment variables, never from
the database. Risk: rotating `MFA_ENCRYPTION_KEY` invalidates channel secrets as it does MFA seeds; document it.

### 5.7 What data leaves the site

| Channel | Leaves to | Content |
|---|---|---|
| Email (today) | SMTP provider | Full detail |
| Telegram | Telegram servers (cloud chat history) | Full detail, chat id |
| Discord | Discord servers, visible to every member of that channel (warned in the UI) | Full detail |
| Webhook | The user's endpoint | Full detail + structured JSON |
| Web Push | The push service sees only endpoint, size, timing, `VAPID_SUBJECT`. The payload is end-to-end encrypted (RFC 8291). | Short title/body; the lock screen shows it (per-device "Hide details on lock screen" option, decision 11) |

The privacy policy and About page need updating: Telegram and Discord are third-party processors the user chooses.

### 5.8 Abuse limits

| Limit | Value |
|---|---|
| Channels per account | Push 10, Telegram 3, Discord 5, webhooks 5 |
| Test messages | 5 per 10 min per account (DB-backed `RequestRateLimiter`, multi-instance) |
| Channel creation | 10 per hour |
| Push API | 30 per hour |
| Telegram link requests | 5 per hour |

The limits go in `RateLimitFilter.limitFor` (`security/RateLimitFilter.java:134-170`) plus service-level quota
checks. Alert volume is already bounded by alert rules. The webhook worker paces each channel (section 6), so it
cannot be used to flood a third party.

### 5.9 Account deletion and data export

- **Deletion:** cascades (section 2). When deletion is scheduled, push subscriptions are deleted and Telegram
  chats get a final "This StockWatch account is being deleted; alerts stopped" message (best effort).
- **Export:** `AccountDataExportService.collect` (`service/AccountDataExportService.java:26-112`) adds
  `alertChannels` (type, label, display hint, status, kinds, directions, language, created, last success, disabled
  reason) and `alertChannelDeliveries` (kind, created, delivered/expired, outcome; no content). It never includes
  URLs, chat ids, keys or secrets. The class doc already promises that (`:12-16`). `SettingsController.export`
  (`:518-545`) gains nothing else.

## 6. Rate limits and retries

### 6.1 Provider limits and our pacing

| Provider | Published or observed limits (assumption: re-verify) | Our pacing |
|---|---|---|
| Telegram Bot API | ~1 msg/s per chat; ~30 msg/s per bot; 20 msg/min per group; 429 carries `parameters.retry_after`; text limit 4096 | `channel.available_at = now + 1.1 s` after each send; a DB limiter caps the bot at 25/s across instances; on 429 the channel waits `retry_after` + jitter, and if `retry_after > 30 s` the whole `telegram` scope is blocked |
| Discord webhooks | Per-webhook bucket ~5 req / 2 s (`X-RateLimit-Remaining`, `X-RateLimit-Reset-After`); ~30 msg/min per channel; global ~50 req/s **per server IP**; Cloudflare bans after 10,000 invalid requests per 10 min. **[J]** Discord counts 401, 403 and 429 as invalid, excluding 429s with `X-RateLimit-Scope: shared` | Honour the bucket headers per channel (min 2 s); global limiter 40/s; a 429 with `global: true` blocks the `discord` scope; a counter of 401, 403 and non-shared 429 responses pauses all Discord sending above 1,000 per 10 min (protects the shared IP); 404/401 revoke at once and never retry |
| Push services (FCM, Mozilla, Apple, WNS) | 201 created; 404/410 gone; 413 too large (~4 KB); 429/503 with `Retry-After`; 400/403 for bad VAPID JWTs | `Retry-After` → `push:<host>` scope block; payload kept < 3 KB; coalescing (below) |
| User webhooks | Unknown | 1 in flight and ≥1 s apart per channel; `Retry-After` on 429/503 honoured, capped at 1 h |

**Push coalescing:** at claim time, if a push channel has more than 3 due rows (or 20 sent in the last hour), the
worker sends one notification instead: "7 new StockWatch alerts" → `/signals`, tag `stockwatch-summary`. The rows
are marked delivered together.

### 6.2 Classification and backoff

| Result | Examples | Action |
|---|---|---|
| `DELIVERED` | Telegram `ok:true`; Discord 204/200; push 201; webhook 2xx | Mark delivered, erase ciphertext, `last_success_at`, reset failure counters |
| `RETRY` | Timeouts, connection reset, 5xx, 408 | Row `available_at = now + min(3600, 15·2^min(attempts,8)) + rand(0..5) s` (formula from `EmailOutboxService.java:425`); `consecutive_failures++`. **[from B]** Webhooks stop after at most 8 attempts. |
| Webhook `FAILED` **[from B]** | Webhook 3xx (redirects are never followed) or any 4xx other than 408, 410 and 429 | Expire this row with no retry, `consecutive_failures++`, show the status code on the channel row. The receiver rejected the message, so retrying for 72 h would only repeat the rejection. |
| `RATE_LIMITED` | 429 / 503 + `Retry-After` | Row and channel wait `after`; global scope if indicated; no failure counted |
| `CHANNEL_GONE` | Telegram 403 "bot was blocked by the user" / "user is deactivated", 400 "chat not found"; Discord 404 (code 10015) / 401; push 404/410; webhook 410 | Channel `REVOKED` (config erased) or `FAILING`; pending rows expired; user notice |
| `MESSAGE_REJECTED` | Telegram 400 "can't parse entities" (resend once as plain text, then reject); push 413; Discord 400 | Expire this row only; warning log (a formatting bug) |
| `CONFIG_ERROR` | Telegram 401 (bot token revoked); push 403 VAPID/BadJwtToken | Block the provider scope for 15 min, error log, health warning; never disable user channels for our own misconfiguration |

### 6.3 Auto-disable, notices, and what the user sees

- **FAILING** after ≥8 consecutive failures, the first more than 24 h old. **REVOKED** at once for `CHANNEL_GONE`.
- **User notice**, once (`notice_queued_at`), unless the user caused it (`/stop`, remove, turning push off):
  - an account email, "Your Discord alerts stopped", through `queueAccount` (`AlertNotificationService.java:46-49`)
    via a new `sendChannelStoppedNotice`. It goes out once per disable even while alert emails are paused, because
    it is an account notice about the user's own settings (decision 5). **[J]** `outbox.enqueue` throws without
    SMTP (`EmailOutboxService.java:69`), so the call is wrapped in try/catch and dev shows only the banner;
  - a banner on `/settings/alert-delivery` and `/home` from `controller/UserViewModelAdvice.java` (indexed query);
  - for push, only the in-app state (card 01).
- **The row status** always shows the last attempt time and a plain-language reason (section 3.2).

### 6.4 Multiple instances and restarts

- **Row claim:** `for update of d skip locked` + `lease_until` (2 min) + `owner` fence, as at
  `EmailOutboxService.java:299-311`, with the lease re-checked right before the HTTP call (`:348-352`).
- **One sender per channel:** the claim transaction also runs `update alert_channels set available_at = now() +
  pacing where id = ? and available_at <= now()`. Zero rows means another instance is sending to that channel, so
  the claim is released. That gives ordering and pacing across instances without advisory locks.
- **At-least-once:** a crash after the provider accepted but before `delivered_at` can repeat one message.
  Webhooks carry the stable `webhook-id`; push uses `tag = delivery id`, so the browser replaces the duplicate.
  Telegram and Discord webhooks have no idempotency key. **[from A]** A split message commits `parts_sent` after
  each Telegram message or Discord embed batch, so a crash mid-split resumes at the next part instead of resending
  the earlier ones. At most one part can repeat.
- **Cadence:** both loops run every 2 s, bounded to 50 rows / 10 s per pass, on new `BackgroundJobDispatcher`
  queues `channels` and `webhooks` (`:19-21`). **[J]** Inside each task, the per-channel sends of one pass run on
  virtual threads, at most 16 at once, instead of relying on the 4 shared platform threads. This is measured with
  `ScheduledCheckBenchmarkTest` before and after PR 2.

### 6.5 Web Push protocol details [J, from D]

- **Payload size:** the plaintext stays ≤ 3,993 bytes so it fits one RFC 8291 record of 4,096 bytes. The worker
  aims for < 3 KB and truncates the body first.
- **VAPID JWT:** ES256, signed with `SHA256withECDSAinP1363Format` (the raw `r||s` form JWS needs). One JWT per push
  service audience, re-signed every 30 minutes, with `exp` well under 24 h.
- **`Topic`:** if sent, it is at most 32 base64url characters (RFC 8030 §5.4), for example the first 24 bytes of
  the SHA-256 of the delivery UUID, base64url-encoded. A longer value makes push services answer 400 for every
  message. The notification `tag` stays the delivery id.
- **`TTL`** is always sent (it is required), equal to the remaining lifetime of the row. **`Urgency`** is `normal`,
  or `low` for late deliveries.

## 7. i18n (EN + PL)

- **UI:** new `settings.delivery.*` keys and `js.delivery.*` keys in `messages.properties` and
  `messages_pl.properties`. Flash messages are English strings with catalog entries in
  `src/main/resources/i18n/server-messages.txt`, enforced by `ServerMessagesCoverageTest`. Template and JS text are
  checked by `src/test/js/i18n-guard.test.cjs`. Terms follow `docs/i18n-glossary-pl.md`: gender-neutral forms,
  decimal comma.
- **Message bodies:**
  - Telegram, Discord and webhook `text` reuse the email's English subject and body. They are localized at enqueue
    with `ServerMessages.translate` / `translateLines`, exactly as `localize()` does (`AlertNotificationService.java:84-87`),
    so Polish coverage and number formatting are the email's.
  - Channel-only lines get bundle keys: "Open in StockWatch", "Manage alerts", "Delivered late", the same-point
    introduction, "and {0} more", test messages, the bot's /start, /stop and /help replies, and the
    channel-stopped notice.
- **Push titles and bodies** are short templates per kind from the bundle (`push.signal.title={0} · {1} · {2}`,
  `push.signal.body=Entry {0} · Stop {1} · Target {2}`, `push.outlook.title`, …). They are built from `facts` with
  `PolishFormats` for PL numbers. The fallback is the translated subject without the "StockWatch" prefix, truncated
  to 120 characters.
- **Language choice:** the channel's `language` if set, else `LanguagePreferenceService.localeFor(user)` (saved
  choice, then last browser seen; `i18n/LanguagePreferenceService.java:40-59`). A Discord server shared with
  English speakers can be set to English. Bot replies before linking use Telegram's `language_code` (pl → PL, else
  EN).
- **Telegram:** `parse_mode=HTML`, with every dynamic string escaped (`&`, `<`, `>`, `"` in `href`). Subject in
  `<b>`, body as plain lines, link as `<a href>`, link previews off. Bodies are cut at a line boundary to ≤3,900
  UTF-16 units with "Full details: <link>"; combined messages split between items.
- **Discord:** one embed per alert: title ≤256 (subject), `url` = view link, description ≤4,096 (body, with
  Markdown escaped: `\ * _ ~ \` | >` and a leading `#`; `- ` bullets kept). Inline fields Entry / Stop / Target,
  colour by direction, translated footer "StockWatch 24/7 · not financial advice". At most 6,000 characters and 10
  embeds per message. `allowed_mentions: {"parse": []}` neutralizes `@everyone`.
- **Webhook JSON v1:** `{type, version:1, id, createdAt, language, title, text, url, alert:{kind, symbol,
  interval, family, pattern, direction, entry, stop, target, score, signalPeriod, eventId}, group?}`. `title` and
  `text` are localized. Field names and enum values are stable English.

## 8. Tests

The project uses PostgreSQL with an isolated schema per test run (`src/test/java/.../support/IsolatedDatabaseContextCustomizerFactory.java`).
Schedulers are removed (`:43-48`) and workers are invoked explicitly. There is no Testcontainers in `pom.xml`;
keep the existing setup.

| Layer | Tests |
|---|---|
| Unit | **RFC 8291 Appendix A** vector for `WebPushEncryption`; VAPID JWT (ES256, `aud`, `exp` ≤ 24 h, verifies with the public key) and key-pair mismatch detection; Telegram and Discord formatters (escaping, Polish, emoji, length splits, mentions off); webhook signer against a Standard Webhooks vector + rotation; `OutboundTargetPolicy` table tests (every blocked range, mapped/NAT64/6to4, odd IPv4 spellings, `discord.com@evil`, backslashes, IDN, trailing dots, ports); per-provider result classification; fan-out gating matrix (kind × direction × follow × status × pending deletion). |
| Email non-regression | Golden text tests for every sender before and after the refactor (extends `AlertNotificationServiceTest`, `SamePointEmailTest`, `AccountEmailLanguageTest`). With zero channels, no channel rows are written and outbox rows are identical. |
| Integration (Spring + PostgreSQL) | Fan-out rolls back with the event; a retried check gives one row per channel; same-point hold, release and grouping (also when the batch never closes); two workers on two threads send each row once, one in flight per channel; lease expiry, reclaim, restart, expiry; pause expires pending rows; auto-disable with one notice; congress/insider channels with SMTP off; deletion cascade; export has no secrets. **[J]** Also: a crash after part 2 of 3 resumes at part 3 (`parts_sent`); more than 8 due rows give one roll-up message; a custom alert with "Notify me" on and email off reaches a channel; a same-point batch with email off still releases channel rows; the channel-stopped notice with SMTP off logs and shows the banner only. |
| PR 0 regression **[J]** | A custom-alert email is dropped while "Pause all alert emails" is on and carries `List-Unsubscribe` headers. |
| Contract (fake servers) | JDK `HttpServer` on loopback (allowed by `allow-private-targets` in tests only) with configurable base URLs. **Telegram:** `getMe`, `getUpdates` (start/stop/my_chat_member), `sendMessage` 200/429/403/400. **Discord:** 204, rate-limit headers, 429 `retry_after` + `global`, 404 code 10015. **Push service:** checks `aes128gcm`, `TTL`, `Urgency` and the VAPID header, **decrypts** the body with a test key, returns 201/410/413/429. **Webhook receiver:** verifies the signature; 2xx/410/500/302 (not followed)/slow. |
| Migration | Flyway runs V104–V106 in the isolated schema; check constraints reject bad values; the delivery and push-endpoint uniqueness hold; cascade on user delete. |
| Security | `ProductionConfigurationValidatorTest`: push without valid, matching VAPID keys and a `mailto:`/`https:` subject, Telegram without a token or with a non-default base URL, `allow-private-targets`/`allow-http` in prod, and the PWA kill switch defaulting off all fail **[J]**. Manifest and sw are public; the push API needs auth + CSRF. A log test for the bot token. One-time secrets never appear in `spring_session_attributes` **[from A]**. The push `Topic` is ≤ 32 characters **[J]**. |
| Templates and JS | Every page head includes the PWA fragment; the delivery tab renders EN/PL with no inline styles and CSRF in every form. `node --test`: the settings state machine (unsupported, iOS not installed, default, denied, granted, ended), `push-sync.js`, and `sw.js` handlers with mocked `registration`/`clients`. |
| Browser (Playwright: Chromium/Edge + WebKit, since Firefox is blocked locally; one sign-in per engine) | Manifest content type and fields; SW scope `/`; no CSP console errors; delivery tab in dark/light at desktop and 375 px; add Discord against the fake; Telegram link polling with a stubbed status API; `grantPermissions(['notifications'])` up to `pushManager.subscribe` (assumption: real push may not reach headless Chromium; a manual Android Chrome + installed iOS check goes in the PR). The judged UI review loop runs on the new tab before handover. |

## 9. Phases and PRs

The order is the judge's **[J]**: chat channels first, push last, once the final domain is fixed. The settings tab
is split from Discord, and an existing email bug gets its own first PR.

| # | PR | Ships | Risk | Size |
|---|---|---|---|---|
| 0 | **Custom-alert pause fix** | `Claim.notification()` (`EmailOutboxService.java:436-439`) also recognizes `custom-alert:` keys, so custom-alert emails respect "Pause all alert emails" and carry `List-Unsubscribe` headers. Correct the comment at `AlertNotificationService.java:1774-1776`. One line plus a regression test. | Low; independent of everything else | XS |
| 1 | **Channel core (dark)** | V104, V105 (with the source-row FKs, `held`, `parts_sent`, `provider_message_id`); `ChannelStore`, `AlertNotice`, `ChannelFanout`, `ChannelDeliveryWorker` (claim, fence, per-channel pacing, provider blocks, backoff, expiry, cleanup, `parts_sent` resume, virtual-thread sends), sender SPI with a test sender; `GuardedHttpClient` + `OutboundTargetPolicy`; dispatcher queues; export and deletion hooks; flags `alert-channels.enabled=false` | Low: nothing calls it yet. **SSRF policy review #1** is a checklist item in the PR description | L (~1,400 lines incl. tests) |
| 2 | **Hook every alert into channels** | Sender refactor (section 4); `WatchlistEmailPolicy.follows`; the same-point bridge; congress/insider workers no longer need SMTP for channels; custom alert "Notify me"; golden email tests | Medium-high: the riskiest change to the email path, isolated and pinned by byte-identical golden emails; channels are still empty | L (~1,000) |
| 3a | **Alert delivery tab** | Tab, controller, CSS/JS (no push), empty states, read-only email summary row, "Pause all channels", kinds/directions/language editor, recent deliveries list, notices + banner, EN/PL; channel types hidden, test sender only. Browser checks + the judged UI review loop | Low-medium: UI only, no provider | M (~700) |
| 3b | **Discord** | Discord validation, sender, formatter, test send, invalid-request breaker, roll-up guard, `NOTIFICATION_CHANNEL_ADDED` event and notice | Medium: first real channel; no inbound traffic, fixed hosts and no linking, so the cheapest proof of the core on localhost | M (~600) |
| 4 | **Telegram** | V106, bot config + prod validation (default base URL), lease-held poller on its own thread, link + site confirmation (deep link rendered with `no-store`, never flashed), `/stop` `/help`, `my_chat_member`, HTML sender, replies to the original `message_id`, QR deep link, ops docs | Medium: inbound processing, linking security | L (~1,100) |
| 5 | **Outgoing webhooks** | PENDING → test-verified activation, signing + rotation (secret rendered with `no-store`, never flashed), FAILED/RETRY classification with an 8-attempt cap, JSON v1 schema + docs section on the About/help page, 410 handling | High (SSRF/abuse): user URLs reach the policy for the first time. **SSRF policy review #2** is a checklist item | M (~700) |
| 6 | **Installable web app (PWA shell)** | Manifest (via `PwaController`), icons (rendered once from an SVG with the Playwright dev dependency; regular 192/512 + maskable 512, apple-touch 180, favicon), `sw.js` with kill switch and the network-first `/offline` fetch handler, `fragments/pwa :: head` in all 31 page heads + template test, permitAll entries, install button (checked in Edge and Chrome) | Low-medium: a service worker is sticky, hence the kill switch | M (~600 + images) |
| 7 | **Web Push** | VAPID properties + prod validation + `VapidKeyGenerator` main class (prints a key pair, like `migration.DatabaseMigrationMain`) + dev auto-generation into `target/dev-vapid.properties`; own RFC 8291/8292 implementation (JDK only, section 6.5); `WebPushSender` with coalescing; subscription API; SW `push`/`notificationclick`; "This device" card; `push-sync.js` with the push revision; logout unsubscribe; push cleanup on password change except the current device | Medium-high: crypto (mitigated by RFC vectors and a decrypting fake service), platform quirks. Needs the final HTTPS origin, because subscriptions are bound to it | L (~1,400) |
| 8 | **Chat summaries and quiet hours** | `delivery_mode = SUMMARY` for Telegram, Discord and webhooks with the `held` + `group_key` design [from D] (section 1.4); optional quiet hours (decision 4) | Low | M (~500) |

**Why chat channels first:**
1. They are testable today on `localhost` and real phones: the apps are already installed and our traffic is
   outbound only (Telegram via polling). Phone push needs the public HTTPS domain, and iOS an installed web app on
   it; neither exists yet.
2. Telegram already gives iPhone users native notifications with no Home Screen install, which push cannot do
   before the domain exists.
3. Decision 2 gives chat messages the email's detail, so they reuse the email text. That fixes "email-only" fastest.
4. Push carries the most new crypto and browser behaviour, so it lands on a pipeline already proven by three
   providers. It reuses the core, hook-in and settings tab unchanged.

**What could change the order:** PR 6 is independent and can land any time after PR 3a. If the domain goes live
early, PRs 6–7 can move ahead of PR 5, since they depend only on PRs 1–3a.

Docs: `docs/security-deployment.md` gains the new secrets, egress rules and validator rules in PRs 4, 5 and 7.

## 10. Open questions and risks

### 10.1 Decisions needed

The plan is written to follow each recommendation **[J]**. Changing an answer changes the sections named.

| # | Decision | Recommendation |
|---|---|---|
| 1 | Does a custom alert's own switch mean "Notify me" (all channels) or stay email-only? | "Notify me", relabelled (section 4.2). Email-only needs extra plumbing for a confusing result. |
| 2 | Does a watchlist's per-list "Email notifications" switch also gate chat and push? | No. Channels follow what the list follows (`follows`). Add a per-list "Other channels" switch only if asked. |
| 3 | Does "Pause all alert emails" stay email-only? | Yes (it is the RFC 8058 unsubscribe target), plus a separate "Pause all channels". |
| 4 | Quiet hours, given the daily check fires at 00:00 Brussels (Tue–Sat)? | Offer silent delivery 22:00–07:00, off by default, in PR 8. Do not hold alerts until morning. |
| 5 | Should the "channel stopped" email go out while alert emails are paused? | Yes, once per disable: it is an account notice about the user's own settings (section 6.3). |
| 6 | Telegram group chats in v1? | No: private chats only (consent, 20 messages a minute per group). |
| 7 | Telegram polling or webhook once the domain is live? | Polling; webhook only if latency matters. Use a separate dev bot. |
| 8 | Own RFC 8291/8292 code or a library (`nl.martijndwars:web-push`)? | Own code on JDK crypto, pinned by the RFC Appendix A vector and a decrypting fake. The library is old, pulls BouncyCastle + jose4j + an async client, and adds OWASP scan surface. |
| 9 | Add the Apache HttpClient 5 dependency? | Yes: it is needed for DNS pinning with TLS host-name checks. |
| 10 | Webhook ports and schemes in production? | HTTPS only, ports 443 and 8443. |
| 11 | Push detail on lock screens? | Short details on, with a per-device "Hide details" option. |
| 12 | Remove push devices on a password change, MFA disable or "sign out other sessions"? | Yes, except the current device (section 5.2). |
| 13 | Fix the final public origin before launching push? | Yes: subscriptions and installs are bound to the origin. |
| 14 | Quotas (section 5.8), lifetimes (section 4.4: push 24 h, chat 48 h, webhooks 72 h) and 30-day retention? | Accept as proposed. |
| 15 | Privacy and About text naming Telegram, Discord and the push services? | Required before enabling in production. |
| 16 | Discord OAuth "Add to server" (`webhook.incoming`) instead of pasting a URL? | Later. It needs a Discord app, a client secret and a redirect URL on the public domain. |

### 10.2 Operational needs

- **Telegram bot:** create it with @BotFather (name, username, picture, commands `start`/`stop`/`help`, privacy on,
  groups off). `TELEGRAM_BOT_TOKEN` and `TELEGRAM_BOT_USERNAME` go in the secret manager. Use a **separate dev
  bot** locally.
- **VAPID:** generate once with the provided command; store `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` and
  `VAPID_SUBJECT=mailto:<role mailbox>`. Don't rotate casually: every subscription is bound to the public key.
- **Production validation** in `ProductionConfigurationValidator.validate`
  (`config/ProductionConfigurationValidator.java:116-145`): `PUSH_ENABLED=true` without valid, matching keys and a
  `mailto:`/`https:` subject refuses to start, and so does `TELEGRAM_ENABLED=true` without a token matching
  `^\d+:[A-Za-z0-9_-]{30,}$` **[J]** (the token length is not documented, so an exact length could refuse a valid
  token). **[J]** The validator also rejects `alert-channels.allow-private-targets=true`,
  `alert-channels.webhook.allow-http=true` and a non-default Telegram base URL, and requires the PWA kill switch
  setting to default to on.
- **DNS and HTTPS:** the public domain is required for push on phones and for installing on iOS and Android.
  Desktop push can be tested on `localhost`, which counts as a secure context.
- **Egress, privacy, monitoring:** allow `api.telegram.org`, `discord.com`, the push hosts and 443/8443 for
  webhooks, and deny private ranges at the firewall. Update the privacy policy and About page and add a note on
  creating Discord webhooks. Alert on per-provider failure rates, provider blocks and `CONFIG_ERROR`.

### 10.3 Platform limits and risks

- **iOS/iPadOS:** Web Push works only for web apps added to the Home Screen (16.4+). Permission must be asked from
  a user gesture inside the installed app, and the installed app has **separate storage**, so the user signs in
  again there. Don't rely on notification actions or images. Apple briefly removed Home Screen web apps in the EU
  in early 2024 and reversed it: a residual policy risk (assumption).
- **Other browsers:** Chrome shows a "quiet" permission UI for sites with low acceptance, so we ask only after the
  button click. Firefox desktop pushes without install; so does Safari on macOS. Payloads are limited to ~4 KB.
- **Service-worker bugs persist** on users' devices. Mitigated by the kill switch, `no-cache` on `sw.js` and no
  page caching.
- **Discord limits are per server IP.** One bad webhook loop could get our IP throttled, hence the invalid-request
  breaker and immediate revocation on 401/404. Telegram and Discord delivery is at least once: rare duplicates
  after a crash mid-send.
- **Capacity and keys:** 4 dispatcher threads now serve 9 queues (`BackgroundJobDispatcher.java:22`). Sends run on virtual threads (section 6.4) and the Telegram poller has its own thread; measure
  under load and give channels a small pool if email latency rises. Rotating `MFA_ENCRYPTION_KEY` breaks stored
  channel secrets, as it does MFA seeds.

### 10.4 Side findings (existing code, outside this feature)

- **Custom alert emails ignore the pause switch.** `sendCustomAlertEmail` says "the outbox drops it if the account
  paused emails" (`AlertNotificationService.java:1774-1776`). But `Claim.notification()` only recognizes event,
  outlook, digest and `chart-alert:` keys (`EmailOutboxService.java:436-439`), and custom keys start with
  `custom-alert:` (`CustomAlertEngine.java:659`). So custom alert emails seem to ignore "Pause all alert emails" and
  carry no `List-Unsubscribe` headers. The judge confirmed this in the code; it is PR 0 (section 9).
- **Insider emails are never retried.** They are sent inline, and a failure only sets `FAILED`
  (`InsiderActivityService.java:412-421`). Channel delivery in PR2 does not depend on it.
