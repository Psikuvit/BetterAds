# Phase 1 fraud/billing comparison: session+event API vs. the iframe/ViewTokenService model

This compares the trust model introduced by the placements session+event API
(`SessionService`, `SessionTokenService`, `Site`/`AdSession`/`SessionEvent`)
against the model it runs alongside (`ViewTokenService`, `FraudService`,
`EmbedService`, used by `/embed/{token}` and `/api/ads/**`), which is
untouched and still serving traffic in parallel.

## Old model, in one paragraph

A permanent, unsigned `AdLink.token` (random UUID) identifies an ad's embed
page. Loading `/embed/{token}` mints a fresh, HMAC-signed, 2-minute,
single-use `vt` token with no fraud/rate check at that layer. The client's
one subsequent call to `/api/ads/{id}/playlist?vt=...` validates+consumes
that token; **if it's valid, the per-IP fraud check is skipped entirely**.
The campaign velocity check always runs. Billing (`BillingService
.recordView`) fires once per live ad in the campaign, every time, and its
success/failure result is discarded — a budget-exhausted view still returns
200 with a playable URL.

## New model, in one paragraph

A non-secret `Site.siteKey` (validated against the registered origin/bundle
ID) plus a per-impression `AdSession` replace the per-ad token. Creating a
session **always** runs the per-IP and campaign-velocity fraud checks — there
is no token that bypasses them, because the session token itself is the
thing being requested, not an alternative to the check. Billing fires
**exactly once per session**, gated on a `SessionEvent(IMPRESSION_START)`
that the server rejects unless at least `app.placements.min-viewability-ms`
(default 2000ms, the IAB viewable-impression floor) has elapsed since session
issuance. Every event is durably recorded (`session_events`, unique per
`(session_id, event_type)`), and the billing call's return value is checked:
a budget-exhausted view is reported back to the caller (`billed: false`)
instead of silently succeeding.

## Improved

- **No more fraud-check bypass.** The old design let a valid `vt` token skip
  `FraudService.isLikelyFraud` entirely. The new design always evaluates it
  at session creation — nothing bypasses it.
- **Billing is gated on a verifiable minimum-viewability floor, not "an API
  call happened."** A genuine 2-second-visible impression cannot mathematically
  produce an `impression_start` faster than 2 seconds after session issuance;
  the server enforces that floor server-side rather than trusting the client's
  self-reported timing.
- **The billing result is no longer discarded.** `BillingService.recordView`
  changed from `void` to `boolean`; the caller now branches on it, marks the
  session `ERRORED`, and reports `billed: false` instead of returning a
  playable URL as if nothing went wrong.
- **One bill per impression, not one bill per live-ad-per-fetch.** The legacy
  `/playlist` endpoint bills every live ad in a campaign on every single
  fetch, regardless of what's actually watched. A session bills at most once,
  tied to the one ad it was issued for.
- **Durable audit trail.** The old model's single-use enforcement is
  Redis-only and ephemeral (a nonce with a 2-minute TTL) — a Redis data-loss
  event inside that window could allow replay, and there's no persisted
  record of rejected/fraudulent attempts beyond logs. The new model persists
  every session and every accepted event to MySQL, with a `UNIQUE(session_id,
  event_type)` constraint as a durable backstop behind the Redis fast-path
  dedup check.
- **Dedicated signing secret.** `ViewTokenService` defaults to reusing
  `app.auth.jwt-secret` if `app.fraud.view-token-secret` isn't set. The new
  `app.fraud.session-token-secret` has no such fallback — the app refuses to
  start without one, so rotating the JWT secret can no longer silently change
  the view-trust boundary too.
- **Ordering is enforced, not assumed.** Quartile/complete events must arrive
  in order and can each only be recorded once per session; out-of-order or
  duplicate submissions are rejected (409), which the old model had no
  equivalent concept for (it made exactly one playlist call and never
  revisited an ad's view state again).

## Unchanged

- The per-IP sliding-window check (`fraud:ip:{ip}`, >30 impressions/60s) and
  the per-campaign velocity cap (`fraud:campaign:{campaignId}`, configurable,
  default >200/60s) are reused as-is, same thresholds, same Redis mechanics —
  now just always evaluated instead of conditionally skipped.
- Presigned S3 URLs, 2-hour expiry, same `StorageService.presignGetUrl` /
  `extractStorageKey` convention.

## Still weak / explicitly out of scope

- **Quartile/complete timing is still self-reported by the client.** The
  server enforces a *minimum* elapsed time before `impression_start` and
  strict *ordering* for everything after it, but it has no independent way to
  confirm the video was actually rendered on-screen at 25%/50%/75% progress —
  there's no cryptographic proof of pixel-level visibility. A sophisticated
  client could still fabricate a plausible-looking, correctly-ordered,
  correctly-timed event sequence without a real viewer.
- **Mobile bundle-ID validation is a self-reported claim,** not real app
  attestation (Play Integrity / App Attest). This matches the brief's own
  "app-side attestation" framing and is explicitly deferred.
- **No device fingerprinting or cross-signal correlation.** Consistent with
  `FraudService`'s existing documented non-goals — this phase doesn't add any.
- **Origin validation is a header check** (`Origin`/`Referer`), which is not
  cryptographically unforgeable for non-browser clients; it's a meaningful
  bar for typical web publishers but not a hard guarantee.

## Net assessment

Fraud/billing correctness is equal or better than the current
`ViewTokenService` model on every dimension it changes, and identical on the
dimensions it doesn't touch (IP/campaign velocity checks). No regression
identified. The remaining weaknesses (self-reported quartile timing, weak
mobile attestation, no device fingerprinting) are pre-existing limitations of
the old model too, not new gaps introduced by this phase — and are
consistent with what the brief already marks out of scope for this
migration.
