# Secure deployment checklist

A public deployment must use the `prod` profile and a trusted TLS reverse proxy. The production checks run under every profile except `dev` and `test`, so starting without a profile fails closed: the app refuses to start when HTTPS, Secure session cookies, the public URL, trusted-proxy handling, required SMTP security or a strong `MFA_ENCRYPTION_KEY` is missing. Local development uses the `dev` profile, which only makes sense on `http://localhost`.

## 1. Secrets and database roles

- Keep secrets in the hosting platform's secret manager. Never commit `.env` or real credentials.
- Use separate random credentials for the runtime and migration database roles.
- The migration role may change the application schema. Supply it only to the one-shot migration process.
- The runtime role needs `SELECT`, `INSERT`, `UPDATE`, and `DELETE` on application tables plus sequence usage. It must not own the schema or have `CREATE`, `ALTER`, or `DROP`.
- Keep PostgreSQL private. The development Compose file binds it to loopback; production should remove `ports` and use a private network.
- Encrypt backups and restrict database/log access. The database contains identity data, password hashes, alert settings, and alert history.
- Signed-in sessions are stored in the `spring_session` and `spring_session_attributes` tables (V96), so restarts and deployments keep people signed in and several instances share them. The runtime role needs the same privileges on them as on the other tables. A session row signs its holder in, so treat the tables like the rest of the database. Ordinary sessions still end after `SESSION_TIMEOUT` idle (30 minutes) and `SESSION_ABSOLUTE_TIMEOUT_SECONDS` (12 hours); "Keep me signed in" sessions last 30 days. Signing out, a password change and "sign out everywhere" end them either way.

Build once, then migrate before starting the web process:

```powershell
.\mvnw.cmd --batch-mode clean package
java -Dloader.main=org.example.stockwatch247.migration.DatabaseMigrationMain `
  -cp target/StockWatch24-7-0.0.1-SNAPSHOT.jar `
  org.springframework.boot.loader.launch.PropertiesLauncher
```

Set `DB_URL`, `DB_MIGRATION_USERNAME`, and `DB_MIGRATION_PASSWORD` only for that migration command. Start the web process with `FLYWAY_ENABLED=false` (the production default) and without either migration credential.

## 2. Required production settings

At minimum, set:

```text
SPRING_PROFILES_ACTIVE=prod
PUBLIC_BASE_URL=https://your-domain.example
PUBLIC_HTTPS_PORT=443
TRUSTED_PROXY_IP_REGEX=<exact proxy IP or subnet regex>
SERVER_BIND_ADDRESS=127.0.0.1
FLYWAY_ENABLED=false
DB_URL=<private PostgreSQL JDBC URL>
DB_USERNAME=<restricted runtime role>
DB_PASSWORD=<runtime secret>
ALERTS_EMAIL_ENABLED=true
ALERTS_EMAIL_FROM=no-reply@your-domain.example
SMTP_HOST=<provider host>
SMTP_PORT=587
SMTP_USERNAME=<secret>
SMTP_PASSWORD=<secret>
SMTP_AUTH=true
SMTP_STARTTLS=true
SMTP_STARTTLS_REQUIRED=true
MFA_ENCRYPTION_KEY=<unique random secret, at least 32 characters>
```

The production profile itself fixes `REQUIRE_HTTPS=true`, `EMAIL_VERIFICATION_REQUIRED=true`, and mandatory SMTP STARTTLS. Configure DNS SPF, DKIM, and DMARC for the sender domain.

## 3. Trusted proxy and network boundary

The app honors forwarded protocol and source addresses only from `TRUSTED_PROXY_IP_REGEX`. That is still safe only when the backend is unreachable from the public internet.

- Permit port 8080 only from the reverse proxy/load balancer security group or local host.
- Keep `SERVER_BIND_ADDRESS=127.0.0.1` for a same-host proxy. Set it to `0.0.0.0` only inside a private container/VPC network with an enforced firewall or security group.
- Strip incoming `Forwarded` and `X-Forwarded-*` values and generate authoritative replacements.
- Terminate TLS 1.2 or newer, cap bodies at 64 KiB, apply connection/time limits, and keep edge rate limits enabled.
- Use [the supplied Nginx example](../deploy/nginx/stockwatch.conf.example) for a same-host proxy. Install `stockwatch-proxy.conf` at `/etc/nginx/snippets/stockwatch-proxy.conf`, then adapt the domain, certificate paths, and proxy location.
- If the platform proxy is on another subnet, narrowly anchor/escape its address regex. Never use `.*` or another all-address pattern.

The application has a PostgreSQL-backed second layer of rate limits, provider cooldowns, durable alert jobs, and coordination locks. The edge remains necessary for volumetric traffic and connection exhaustion.

Alert schedule checkpoints are also stored in PostgreSQL. On every startup, and once per minute while running, the app compares those checkpoints with the configured daily, weekly, and monthly cron schedules. Missed runs are queued in their original order and evaluate only candles that were available at the original scheduled time. The default recovery batch is 100 schedule occurrences per pass, so an extended outage is drained incrementally without creating an unbounded startup burst. Completed signal events remain protected by their database uniqueness constraint, preventing a recovered run from recording the same rule/pattern/candle twice.

## 4. Alert channels (Telegram, Discord, webhooks, Web Push)

Alerts can also go to Telegram, Discord, the user's own webhook and browser push (`docs/alert-delivery-channels-plan.md`).
`ALERT_CHANNELS_ENABLED` (default `true`) switches them all off. Discord and webhooks need no setting.

```text
TELEGRAM_BOT_TOKEN=<from @BotFather; privacy on, groups off, commands start/stop/help>
TELEGRAM_BOT_USERNAME=<the bot's username, without @>
VAPID_PUBLIC_KEY=<from VapidKeyGenerator>
VAPID_PRIVATE_KEY=<from VapidKeyGenerator>
VAPID_SUBJECT=mailto:<a mailbox that reads push service notices>
PWA_ENABLED=true
```

- Telegram is offered only with a token and a username. The bot reads its updates by polling, so it needs no inbound
  URL; only one process may poll a bot, so development needs its own bot.
- Generate the VAPID pair once and keep it: every browser's push subscription is bound to the public key, and a new
  pair ends them all. Generate it with
  `java -Dloader.main=org.example.stockwatch247.service.channel.VapidKeyGenerator -cp target/StockWatch24-7-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher`.
  Push to phones, and installing on iOS, need the final public HTTPS origin.
- `PWA_ENABLED=false` is the kill switch for a faulty service worker: every device removes it on its next visit.
- The production checks refuse a malformed Telegram token, a token without a username, a mismatched VAPID pair or
  subject, generated VAPID keys, and any relaxed outgoing-request setting (`alert-channels.allow-private-targets`,
  `alert-channels.webhook.allow-http`, `alert-channels.allow-any-port`, provider base URLs other than the real ones).
- Egress: allow `api.telegram.org`, `discord.com`, the push services (`fcm.googleapis.com`,
  `updates.push.services.mozilla.com`, `*.push.apple.com`, `*.notify.windows.com`) and HTTPS on ports 443 and 8443
  for webhooks; deny RFC 1918 and cloud metadata addresses at the firewall as a second line behind the application's
  own checks.
- Telegram and Discord are processors the user chooses: name them, and the push services, in the privacy policy before
  enabling channels in production.

## 5. Release and external checks

Run locally or let the security workflow enforce:

```powershell
.\mvnw.cmd test
.\mvnw.cmd -Psecurity-scan -DskipTests verify
```

CI runs tests, OWASP Dependency-Check, Gitleaks, and CodeQL on pull requests and `main`, plus a weekly scheduled scan. Add an `NVD_API_KEY` repository secret to avoid vulnerability-feed throttling. If the repository is organization-owned, also add the Gitleaks `GITLEAKS_LICENSE` secret; personal repositories do not require it. Dependabot covers Maven, Docker, and GitHub Actions.

Before directing production traffic:

1. Confirm the migration job succeeded and the runtime role cannot create or alter tables.
2. From the public internet, verify HTTP redirects to HTTPS, HSTS is present, oversized requests return `413`, and spoofed forwarded headers do not affect the observed source/protocol.
3. Confirm port 8080 and PostgreSQL are unreachable externally.
4. Exercise signup, verification, resend cooldown, login throttling, password reset policy (when added), alert creation quotas, and email delivery on staging.
5. Enable alerts for repeated `401`, `403`, `413`, `429`, and `5xx` responses; protect and retain proxy/application audit logs.
6. Back up and restore-test PostgreSQL before release, then monitor Spring, Tomcat, pgJDBC, Jackson, PostgreSQL, and base-image advisories.
