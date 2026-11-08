# StockWatch assistant (AI chat): design plan

Date: 2026-11-07

Status: built on the `ai-assistant` branch (November 2026), all three phases. [Implementation notes](#implementation-notes)
at the end list where the build differs from this plan.

## The idea in one paragraph

A small **Ask StockWatch** button sits in the corner of every signed-in page. It opens a chat
panel docked to the side, so the page stays usable while the user reads. The user says what they
want ("tell me when a stock is oversold and then bounces with a bullish candle"). The assistant
answers with **the exact selections to make in the builder**, step by step, using the builder's
real labels in the user's language. Phase 2 adds an **Open in builder** button that fills the
rule in for them. Behind the button is a fast hosted model (Gemini Flash on your personal key for
now). The model is called **only from our server**. It reads hand-written **knowledge files**
(one per page, plus a custom-alert reference) and **generated data** (the indicator catalog and
pattern lists). Any alert it proposes goes through **the same validator the Save button uses**, so
it cannot suggest an option that doesn't exist.

## Why this shape

- **The browser never talks to Google.** The CSP is `connect-src 'self'`
  ([CspNonceFilter.java:34](../src/main/java/org/example/stockwatch247/security/CspNonceFilter.java)),
  and the API key must not reach the page. The widget posts to `/api/assistant/chat` and the
  server calls Gemini. This also gives us one place for rate limits, budget and logging.
- **One include reaches every page.** All 20 signed-in templates render
  `fragments/navbar :: navbar`, and its `showAppNavigation` block already loads the shared scripts
  (`learning-mode.js`, `watchlist-picker.js`, …). The assistant fragment, CSS and script go in that
  block, so a new page gets the assistant automatically.
- **The rule is already plain JSON.** The builder edits `state.definition` (the stored JSON). It can
  already load a whole rule from a template (`useTemplate` in
  [custom-alert-builder.js:1469](../src/main/resources/static/js/custom-alert-builder.js)).
  `CustomAlertDefinition.parse(node, catalog, interval)` validates every field against the
  indicator catalog and the pattern lists. So a model-written rule can be checked by the real
  validator, and loaded the way a template is.
- **Labels already live in message keys.** The builder's visible words are `customAlerts.*` and 494
  `js.ca.*` keys in both languages, and the learning-mode texts (`js.learn.customAlerts.*`) already
  explain each option precisely. The knowledge files reference these keys instead of copying the
  words, so a renamed button can't leave the assistant out of date.

## What the user sees

### The button and panel

- A round **Ask StockWatch** button, fixed bottom-right on every signed-in page. On the builder it
  sits above the fixed Save/Cancel footer (`.ca-footer`), not over it.
- **Desktop and tablet:** a non-modal side panel (about 380px wide) docked right. The page stays
  clickable, so the user can follow the steps in the builder while reading them. **Phone:** a
  full-height sheet with a **Hide** button that keeps the conversation.
- Panel contents: title, **New chat**, close; a one-time notice (below); the messages; two or three
  **suggested questions for this page** (from the page's knowledge file); a text box (Enter sends,
  Shift+Enter adds a new line, 1,000-character limit); Send.
- The conversation is kept in `sessionStorage`, so it survives moving between pages (every
  navigation reloads the page here). It is cleared on **Sign out** and by **New chat**.
- Replies are shown with a tiny safe Markdown subset (paragraphs, bold, lists, `code`, links only to
  our own paths such as `/settings/candlestick-patterns`), built with DOM nodes, never `innerHTML`.
- Weak-GPU friendly: no `backdrop-filter`, no animated shadows, at most a short opacity fade, and
  none with `prefers-reduced-motion`. Light/dark through `tokens.css`. Keyboard: Esc closes, focus
  returns to the button, and new replies are announced through `aria-live="polite"`.
- Bilingual: UI strings are `js.assistant.*` in both properties files. The assistant answers in the
  account language unless the user writes in the other one.

### First-open notice

> Messages you type here are sent to Google's Gemini service to answer them. Don't type personal
> information. The assistant explains how to use StockWatch; it doesn't give investment advice.

The About page's privacy section gets the same text.

### A typical answer (phase 1)

> **Goal:** RSI oversold for a while, then recovering, with a bullish candle after it.
>
> 1. In **Check after every**, keep **daily close**.
> 2. Click **+ Add condition** → **Indicator** → **RSI**. Keep length **14**. Choose **is below**,
>    type **30**, and set **for at least 5 candles in a row**.
> 3. Click **+ Add condition** → **Indicator** → **RSI** again, choose **is above**, type **30**.
> 4. In the group's join switch choose **in order**. Set **within 3 candles**.
> 5. …

### With a draft (phase 2)

The same answer, plus a card underneath. The card shows the rule **in the app's own words**,
produced by the describers in `custom-alert-text.js`, the same sentences as the builder preview.
Its buttons are **Open in builder** (on the builder page itself: **Use this rule**, which asks
first if the form has unsaved changes) and **Test on AAPL**, which runs the existing preview. The
assistant never saves or applies an alert itself. The user always reviews the rule and presses
Save.

## Server design

New package `org.example.stockwatch247.assistant`:

| Class | Job |
| --- | --- |
| `AssistantController` | `POST /api/assistant/chat` (JSON). Requires sign-in. Returns `{reply, draft?, link?}`. Responds 429 with `Retry-After` when limited and 503 when disabled or the budget is used up. |
| `AssistantService` | Resolves the page, builds the prompt, calls the model, validates and repairs drafts (phase 2), and records usage. |
| `AssistantKnowledge` | Loads `classpath:assistant/**.md` at startup, parses front matter, fills placeholders per locale (EN, PL) and caches the result. |
| `AssistantModel` (interface) + `GeminiAssistantModel` | One `generate(system, contents, schema)` call. Uses `RestTemplate` with timeouts, like `ApiNinjasInsiderClient`. Switching to another provider later means one new class. |

**Request** (from the widget): `{page: location.pathname, messages: [{role, text}…], context?: {…}}`.

- The server maps `page` to a knowledge file through the `routes` in the files' front matter. The
  client can never name a file. An unknown path gets the shared files only.
- History comes from the client. The server keeps the last 8 turns, caps each message (user 1,000
  characters, assistant 4,000), and relies on the existing 64 KB JSON body limit
  (`MAX_JSON_BODY_BYTES`).
- `context` is optional page state (phase 2), and the server checks every field (allow-list,
  sizes). It never contains name, email or other account data.

**Gemini call.** `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`
with the `x-goog-api-key` header. The body has `systemInstruction`, `contents` and `generationConfig`
(low temperature, about 0.2; in phase 2 `responseMimeType: application/json` plus
`responseJsonSchema`). The model ID is **configuration only**. Gemini Flash names change often, so
choose the current Flash model in AI Studio and set `GEMINI_MODEL`. Keep "thinking" low for speed,
and raise it only if the evals show bad drafts.

**Prompt order (for caching).** Stable content goes first and the parts that change go last:
`system rules → site map → custom-alert reference → generated catalog → recipes → this page's file
→ page context → conversation`. Gemini caches a repeated prefix implicitly on supported models, so
most requests reuse the first ~10k tokens. Target size: **≤ 15k input tokens per request**, enforced
by a test.

**Configuration** (`application.properties` and `.env.example`, in the style of the other
providers):

```
ASSISTANT_ENABLED=false            # kill switch; the button isn't rendered when false
GEMINI_API_KEY=                    # never committed; gitleaks already scans
GEMINI_MODEL=                      # e.g. the current Flash model from AI Studio
ASSISTANT_TIMEOUT_SECONDS=20
ASSISTANT_PER_ACCOUNT_MINUTE_LIMIT=6
ASSISTANT_PER_ACCOUNT_DAILY_LIMIT=60
ASSISTANT_MINUTE_REQUEST_BUDGET=10  # whole site; keep below your key's RPM
ASSISTANT_DAILY_REQUEST_BUDGET=200  # whole site; keep below your key's RPD
```

`ProductionConfigurationValidator` refuses to start in `prod` with the assistant enabled and no key
or model set.

**Limits.**

- Per account: two new rules in `RateLimitFilter` for `/api/assistant/chat` (per minute and per
  day), next to the existing `stock-search-hour` rule.
- Whole site: reuse the `market_data_provider_usage` table (V53, `MINUTE` and `DAY` windows keyed by
  provider) with a `GEMINI` row, using the same atomic upsert as `MarketDataProviderRequestBudget`.
  No migration is needed, and the cap holds across restarts and instances. A repair retry (phase 2)
  counts too.
- When the budget is used up, the panel says "The assistant is resting until tomorrow" instead of
  showing an error.

**Logging.** Log the page id, the token counts from `usageMetadata` (including cached tokens),
latency, and the draft validation result. Message text is logged in `dev` only, to help tune the
knowledge files, and never in `prod`.

**Safety.** The assistant has no tools: it reads no data of other users and changes nothing. A
user who jailbreaks it only changes their own replies. Drafts are validated server-side, links are
limited to our own paths, and replies are rendered without HTML, so a bad answer cannot become an
action or a script.

## The knowledge files

They live in `src/main/resources/assistant/`, on the classpath, not under `static/`:

```
assistant/
  system.md                    rules for the model (below)
  site-map.md                  every page in one paragraph: path, what it's for, main controls
  custom-alerts/
    reference.md               every condition kind, field, operator, window, sequence option,
                               trigger mode, quiet period, outcome, targets, limits
    recipes.md                 15–20 goals → exact selections, each with its rule JSON
  pages/
    home.md                    /home
    watchlists.md              /watchlists, /watchlists/{id}
    custom-alerts.md           /custom-alerts
    custom-alert-builder.md    /custom-alerts/new, /custom-alerts/{id}/edit
    custom-alert-trigger.md    /custom-alerts/triggers/{id}
    followed-outlooks.md       /technical-watchlist
    technical-outlook.md       /stock/{s}/technical-outlook, /technical-outlook/changes/{id}
    signals.md                 /signals, /alerts/{id}
    signal-detail.md           /alerts/signals/{id}, /stock/{s}/candlestick-patterns/…,
                               /stock/{s}/elliott-waves/…, /stock/{s}/harmonic-formations/…
    ticker-alerts.md           /ticker-alerts, /activity-signals, /activity-signals/…
    stock.md                   /stock/{s} (chart, chart alerts, follow panel, custom alerts)
    demo-trading.md            /virtual-trades, /virtual-trades/{id}
    settings.md                /settings and its 8 sub-pages (one section each)
    about.md                   /about
```

Each page file follows the same outline, so the model always knows where to look:

```markdown
---
id: custom-alert-builder
routes: [/custom-alerts/new, /custom-alerts/{id}/edit]
templates: [custom-alert-builder]
suggestions: [customAlerts.assistant.q1, customAlerts.assistant.q2]
---
## What this page is for
## What's on it          (sections in screen order, every control with its exact label)
## How to…               (the common tasks as numbered steps)
## Things people get wrong
## Related pages
```

**Placeholders**, filled when the files are loaded, once per language:

| Placeholder | Becomes | Why |
| --- | --- | --- |
| `{{msg:customAlerts.builder.addCondition}}` | **+ Add condition** / the Polish label | Labels can't go stale, and Polish users get Polish labels |
| `{{learn:customAlerts.quiet}}` | the learning-mode explanation of that option | It reuses texts that are already reviewed |
| `{{catalog:indicators}}` | one line per alertable indicator: name, inputs with defaults, output lines | Built from `CustomAlertService.catalog()`, so ~100 indicators are never hand-copied |
| `{{catalog:patterns}}` | candlestick / Elliott / harmonic pattern names with their labels | Same source the builder uses |
| `{{limits}}` | 12 conditions, 4 groups, 8 indicators, 2–4 steps, 60-candle window, 120-candle span | The constants in `CustomAlertDefinition` |

**`system.md` rules** (summary):

- You help people use StockWatch 24/7. Answer only about the app and the market terms it uses.
- Use only options that appear in the documentation. Quote labels exactly, in bold. If something
  isn't documented, say you're not sure, and never invent a control.
- For a custom alert: restate the goal in one line, then give numbered steps in builder order
  (interval → conditions → join/groups → when it fires → stocks → email). Mention any choice that
  changes the meaning (Confirmed vs Detected, "in the last N candles", the join switch).
- If the goal is ambiguous, ask **one** short question first. Example: "around the same time, or
  strictly after?" (sequences plan, timing A vs B).
- If something can't be done (intraday candles, more than 12 conditions), say so and offer the
  closest thing that can.
- No buy/sell recommendations and no predictions. Explain what a setting does, not whether to
  trade.
- Answer in the user's language and keep it short.

## Making it accurate ("confidently and without mistakes")

These checks run with the normal test suite and fail the build when the app and the knowledge
files drift apart:

1. **Every placeholder resolves.** Each `{{msg:…}}` and `{{learn:…}}` key exists in
   `messages.properties` **and** `messages_pl.properties`.
2. **Every page is covered.** Each template that renders `fragments/navbar` is listed in exactly one
   knowledge file's `templates`, and every `routes` entry matches a real `@GetMapping`. A new page
   without a knowledge file fails the build.
3. **Every option is documented.** `reference.md` mentions every value of
   `CustomAlertDefinition.OPERATORS`, the condition kinds, window modes, group matches
   (ALL/ANY/THEN), pattern statuses, activity roles and chambers, and trigger modes.
4. **Every recipe is valid.** The rule JSON in each recipe passes `CustomAlertDefinition.parse`
   with the real catalog. The recipes are both the model's examples and test cases.
5. **Prompt size.** The assembled prompt for each page stays under the token budget (measured with a
   character-based estimate in the unit test).

Plus a **live eval set**, run on demand rather than in CI: `src/test/resources/assistant/evals.json`
holds about 30 goals in English and Polish, each with expectations (condition kinds, operators,
thresholds, interval, timing). A JUnit test tagged `live` (excluded by default; needs
`GEMINI_API_KEY`) sends each one and reports: did the draft validate, did it match the
expectations, did the answer use only real labels. Run it after editing the knowledge files or
changing `GEMINI_MODEL`. The bar before turning it on for users is **≥ 90% valid and matching**.

## Phase 2: drafts the builder can load

- **Structured reply.** `{reply: string, alertDraft: string|null, link: string|null}`, where
  `alertDraft` is the rule **as a JSON string** (`{name, interval, triggerMode, quietCandles,
  outcomeCandles, definition}`). A string avoids the limits of Gemini's JSON-schema support on the
  deeply nested condition union; the server parses it itself.
- **Validate, then repair once.** The server parses `definition` with `CustomAlertDefinition.parse`
  for the draft's interval. On a `UserInputException`, it sends the model one follow-up ("Your rule
  failed: An alert can use at most 8 different indicators. Fix it.") and validates again. If it
  still fails, the user gets the text answer without the card, and the failure is logged for the
  evals.
- **Into the builder.** The widget stores the validated draft in `sessionStorage` and opens
  `/custom-alerts/new?from=assistant`. `custom-alert-builder.js` gains a `loadDraft()` that uses
  the template path (`useTemplate` sets `state.interval`, `state.triggerMode` and
  `state.definition`) and leaves step 4 (stocks) to the user. On the builder page itself, **Use
  this rule** applies it in place after a confirm when the form is dirty.
- **Page context.** Pages can register `window.StockWatchAssistant.context = () => ({…})`:
  - Builder: the current rule, interval, trigger mode, the visible validation message, and the
    preview's "matches now / last trigger" line. This answers "why doesn't my alert trigger?" and
    "change it to weekly".
  - Stock page: the symbol and interval. Trigger page: the alert's rule and the trigger's
    checklist.
- **Cross-page answers.** If a question needs another page's details (asked on the dashboard: "how
  do I change Elliott colours?"), the model may return `needPage: "settings"`. The server adds that
  file and asks once more. One hop at most, and it counts toward the budget.

## Phase 3: polish

- A **Not helpful** button on a reply. After a consent line, it stores the question, page id and
  reply in an `assistant_feedback` table (a migration), so we can see which knowledge files need
  work. Nothing is stored without that click.
- **Ask about this** in learning-mode popovers opens the panel with the option's name pre-filled.
- Streaming replies (`streamGenerateContent` over SSE) if latency feels slow, for text-only answers.
- Before real users: move the key to a **paid tier**. On the free tier, Google may use prompts and
  replies to improve its products and human reviewers may read them. The paid tier doesn't. Google
  applies paid-tier data terms to EEA/UK/Swiss users even on free quota, but don't rely on that
  without checking the current Gemini API terms.

## Delivery

| Phase | Contents | Done when |
| --- | --- | --- |
| **1. Ask** | Server package, Gemini client, per-account limits and site budget, config and prod validation; widget fragment, JS and CSS on every signed-in page; `system.md`, `site-map.md`, custom-alert `reference.md` and `recipes.md`, all 14 page files; drift tests 1–5; first live eval run | The assistant answers on every signed-in page in both languages, the evals reach ≥ 90%, and the UI review (screenshots in both themes and on a phone, plus design critique) is clean |
| **2. Fill in** | Structured replies, draft validation and repair, builder `loadDraft()`, page context for builder, stock and trigger pages, cross-page hop | A draft from the chat opens in the builder and saves unchanged; the evals check drafts too |
| **3. Polish** | Feedback, learning-mode link, streaming, paid-tier switch | As needed |

Phase 1's largest item is writing the knowledge files, not the code: about 14 page files plus the
reference and the recipes. The code is a controller, four classes, one fragment, one script and
one stylesheet.

## Files touched (phase 1)

- New: `assistant/` package (4 classes + model interface), `templates/fragments/assistant.html`,
  `static/js/assistant.js`, `static/css/assistant.css`, `resources/assistant/**.md`, tests under
  `src/test/java/.../assistant/`, `src/test/resources/assistant/evals.json`.
- Changed: `fragments/navbar.html` (include, inside `showAppNavigation`), `UserViewModelAdvice`
  (`assistantEnabled`), `RateLimitFilter` (two limits), `ProductionConfigurationValidator`,
  `application.properties`, `.env.example`, `messages.properties`, `messages_pl.properties`
  (`js.assistant.*`, suggested questions), `about.html` (privacy note).

## Open questions

1. **Signed-out pages.** The plan covers the 20 signed-in pages. The landing page, login and signup
   are left out: they would let anyone spend your personal key's quota, and visitors there have no
   alerts to build. Add them later with a much smaller limit if wanted.
2. **Model.** Any current Gemini Flash works; it's one setting. The provider interface keeps a later
   move to another model to one class.

## Implementation notes

Where the build differs from the plan above:

- **Model.** `gemini-3.6-flash`, through `models/{model}:generateContent` with the key in the `x-goog-api-key`
  header. The build started on `gemini-3.8-flash`, the newest Flash model on 2026-11-07, but in the first evaluation
  `gemini-3.6-flash` answered every hard question correctly (sequences, groups, patterns, refusals) in 2–6 s, while
  `gemini-3.8-flash` answered only six simple ones, in up to 31 s, and often answered 503 "high demand" or timed out.
  The answers are grounded in the prompt and every rule is validated, so the older model is enough. Gemini 3.6 Flash
  and newer ignore temperature, so none is sent; thinking is set with `thinkingConfig.thinkingLevel` (`LOW` by
  default, `GEMINI_THINKING_LEVEL`).
- **Fallback model.** On the free tier Google allows each model only **20 requests a day per project** (reset at
  midnight Pacific time). So `GEMINI_FALLBACK_MODEL` (default `gemini-3.8-flash`, with its own 20-a-day quota) is
  asked when the main model is overloaded, unreachable or out of quota. A model out of quota rests for a minute or until the daily reset; when
  every model has used its day, the panel says the assistant is resting until tomorrow. Without a fallback, "high
  demand" is retried once after two seconds.
- **Switched on by default.** `ASSISTANT_ENABLED` defaults to true, and the panel appears only once `GEMINI_API_KEY`
  is set. In production a key also requires `GEMINI_MODEL` and forbids `ASSISTANT_LOG_MESSAGES`.
- **Limits.** Both the per-account and the whole-site limits use `RequestRateLimiter` (the `rate_limit_windows`
  table), not `market_data_provider_usage`: one mechanism, still shared by every instance. The site's day is 24 hours
  from its first question, not a calendar day. Settings: `ASSISTANT_PER_ACCOUNT_MINUTE_LIMIT` (6),
  `ASSISTANT_PER_ACCOUNT_DAILY_LIMIT` (60), `ASSISTANT_SITE_MINUTE_LIMIT` (10), `ASSISTANT_SITE_DAILY_LIMIT` (200).
- **Prompt size.** The budget is 64,000 characters (about 16,000 tokens) per page and language, not 15,000 tokens:
  Polish runs a little longer than English. The indicator list says what every indicator shares once (sources,
  timeframes, length ranges) so each of its ~100 lines stays short.
- **Repair.** When a rejected rule is still rejected after the one repair, the second answer's text is shown without
  the rule: it was written knowing why the first rule failed.
- **Page context.** The builder sends its rule, the preview's readings and its status line; the trigger page sends the
  checklist as shown. The stock page sends nothing: its ticker is in the path the assistant already receives.
- **Learning mode.** A pinned explanation adds an "Ask the assistant: …" question to the panel instead of a button
  inside the explanation, which is a tooltip and must not hold controls.
- **Not built.** Streaming: answers are JSON (reply, rule, link), which cannot be shown usefully while it arrives.
  The paid tier is the owner's decision; testing runs on a personal free key.
- **Evaluation.** `AssistantLiveEvalTest` asks the 30 questions in `src/test/resources/assistant/evals.json` (English
  and Polish) and checks the rules, labels, links and refusals:
  `./mvnw test -Dtest=AssistantLiveEvalTest -Dassistant.eval=true` (add `-Dassistant.eval.verbose=true` to print the
  answers, `-Dassistant.eval.only=id1,id2` to run some). Gemini being unavailable is counted apart from wrong answers.
  First runs (2026-11-08): 24 of 26 answered questions passed (92%); the free daily quota of both models ran out before
  the last four Polish questions (`pl-insider`, `pl-no-advice`, `pl-howto-language`, `pl-weekly`). Of the two
  failures, one was the test's fault (an "either … or" rule built as one group joined with **Any of these** is right
  too) and one led to the shared "Questions asked from any page" section of the site map (email troubleshooting).
  Measured: prompts of about 17,500 tokens (19,700 in Polish), of which `gemini-3.6-flash` read about 15,500 from
  Google's cache; answers in 2–6 s on `gemini-3.6-flash` and 4.5–31 s on `gemini-3.8-flash`.
