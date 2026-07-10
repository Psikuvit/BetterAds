# BetterAds

BetterAds is a video ad platform. Advertisers upload video ads, which are
moderated and processed asynchronously, then served to end users through a
generated embed link. Views are tracked for fraud detection and billing.

This document describes the architecture and how the pieces integrate. It is
not a setup guide.

## Architecture at a glance

BetterAds is a modular monolith: one Spring Boot codebase, organized into
packages by responsibility, with a separate async worker path for the
processing pipeline. There are two `@SpringBootApplication` entrypoints,
`BetterAdsApplication` and `WorkerApplication` — both currently scan the same
full package tree, so in practice they boot an identical context; the split
exists to allow the ad-processing consumer to eventually be deployed as an
isolated process without touching the web-facing API.

Stack: Spring Boot, MySQL (via Flyway-managed schema), Redis, RabbitMQ, AWS
S3 (SDK v1), Spring Security with JWT, Stripe, Server-Sent Events for
real-time status.

## The core pipeline

1. **Presign.** An advertiser calls `POST /api/upload/presign` with a storage
   key (namespaced under `ads/{advertiserId}/...`) and a content type from an
   allowed video MIME list. `StorageService` returns an S3 presigned PUT URL
   with the content type bound into the signature, so the client's upload
   must match what was requested.
2. **Client uploads directly to S3.**
3. **Confirm.** `POST /api/upload/confirm` looks up the target campaign,
   verifies the caller owns it, verifies the uploaded object actually exists
   in S3 (size and content type checked against policy), then creates an
   `Ad` row with status `pending` and enqueues the ad ID onto the
   `ad-processing` RabbitMQ queue via `ProcessingQueueService`.
4. **Worker consumes the job.** `WorkerConsumer` (a `@RabbitListener` on
   `ad-processing`) drives the ad through its lifecycle inside a single
   transaction:
   - `pending -> validating`: calls `ValidationService`, which delegates to
     a `ModerationService` implementation (see "AI provider abstraction"
     below) and gets back `APPROVED`, `FLAGGED`, or `REJECTED`.
   - On `APPROVED`, `AdLifecycleService.moveToLive()` takes over:
     `validating -> processing` (runs `FeatureProcessingService`, which
     translates the source video and evaluates speech quality, then
     persists an `AdVersion` — a locale-specific rendition) `-> live`, then
     generates an embed link via `EmbedService`.
   - On `FLAGGED`, status becomes `flagged` and the ad waits for a human
     decision.
   - On `REJECTED`, `AdLifecycleService.reject()` sets status `rejected`.
   - Any exception anywhere in this flow sets status `failed`.
   - Every transition is published as an SSE event (see "Real-time status").
5. **Human review.** A `flagged` ad has no automatic path forward. An admin
   calls `PATCH /api/ads/{id}/review` with `approve` or `reject`; approve
   routes through the same `AdLifecycleService.moveToLive()` used by the
   automatic path, so a manually approved ad goes through feature
   processing and embed-link generation identically to the happy path.
6. **Serving.** `GET /embed/{token}` (public) resolves the token to an ad ID
   and returns a small HTML page with an inline `<video>` and a script that
   fetches `GET /api/ads/{id}?locale=`. That endpoint resolves the best
   locale-matched `AdVersion`, runs the fraud check, records the view via
   `BillingService`, and returns the storage key(s) for playback.

## Module responsibilities

- `auth/` — login, registration, `/me`, refresh/logout/password-reset.
  `CurrentUserService` resolves the authenticated `User` entity from the JWT
  subject (a user's email, not a numeric ID) via a DB lookup; this is the
  one place ownership checks originate from.
- `security/` — `JwtTokenProvider` (issues/parses access tokens),
  `JwtAuthenticationFilter` (populates the Spring Security context from the
  bearer token), `SecurityConfig` (route authorization rules, CORS,
  password encoder), `TokenHasher` (SHA-256 hashing for opaque refresh/reset
  tokens).
- `storage/` — S3 presigning and object metadata (`StorageService`), all
  JPA entities and repositories, upload policy constants.
- `api/` — the main REST surface: campaigns, ads, uploads, validation/review,
  analytics.
- `queue/` — RabbitMQ queue definition and the producer side
  (`ProcessingQueueService`).
- `worker/` — the consumer side: `WorkerConsumer`, `AdLifecycleService`
  (shared status-transition logic), `AdStatusEventPublisher` (SSE fan-out).
- `validation/` — `ValidationService`, which wraps the pluggable
  `ModerationService`.
- `ai/` — interfaces for moderation, translation, and speech evaluation,
  each with a `mock` and a `local` implementation (see below).
- `features/` — `FeatureProcessingService`, which orchestrates translation
  and speech evaluation into a persisted `AdVersion`.
- `embed/` — public widget serving (`EmbedController`/`EmbedService`):
  generates and resolves embed tokens, renders the widget HTML, and issues
  a signed view token into that HTML on every render.
- `fraud/` — `FraudService` (Redis-backed sliding-window IP rate check) and
  `ViewTokenService` (signed, one-time-use tokens tying a view back to a
  genuine widget load).
- `billing/` — `BillingService` (per-view cost calculation and budget
  bookkeeping) and `billing/payment/` (Stripe integration: creating
  `PaymentIntent`s, verifying webhook signatures, reconciling budget on
  successful payment).
- `links/` — `LinkService`, a Redis-cached read path for resolving an ad's
  locale variants, used by the `PUBLISHER`-facing `/api/links/{adId}`
  endpoint (distinct from the advertiser-facing embed link).
- `config/` — CORS properties, rate-limit properties/service/filter.
- `common/exceptions/` — the single global exception handler and the
  shared `ErrorResponse` shape.

## Authentication and authorization

JWTs are issued on login/register and carry the user's email as the subject
and their role as a claim. Access tokens are short-lived (15 minutes) by
design, paired with a longer-lived opaque refresh token (7 days). Refresh
tokens are single-use: each call to `/auth/refresh` revokes the presented
token and issues a new access/refresh pair. Both refresh tokens and
password-reset tokens are stored only as a SHA-256 hash — never in
plaintext — and password-reset requests always return the same response
whether or not the email exists, to avoid leaking which emails are
registered.

Three roles exist: `ADVERTISER`, `PUBLISHER`, `ADMIN`. Route-level access is
enforced two ways: `SecurityConfig` permits a small public surface (`/auth/**`,
`/embed/**`, `GET /api/ads/{id}`, the Stripe webhook), and everything else
requires authentication plus a method-level `@PreAuthorize` role check.
Resource-level ownership (an advertiser can only see/edit their own
campaigns) is enforced in the controllers themselves via
`CurrentUserService`, not by Spring Security.

## Data model

Entities are flat — foreign keys are plain `Long` columns, not JPA
relationships — with the actual referential integrity enforced at the
database level via Flyway-managed schema:

- `User` — email, password hash, role.
- `Campaign` — belongs to an advertiser, has a budget, running spend, and a
  status (`draft`, `active`, `paused`, `completed`, `archived`).
- `Ad` — belongs to a campaign, has a lifecycle status and a source storage
  key.
- `AdVersion` — a locale-specific rendition of an ad, produced by feature
  processing.
- `View` — one row per recorded ad view, tied to an `AdVersion`.
- `AdLink` — the embed token for a live ad.
- `RefreshToken` / `PasswordResetToken` — hashed opaque tokens with expiry
  and revocation/use timestamps.
- `Payment` — one row per Stripe `PaymentIntent` created against a campaign.

## Real-time status

Rather than polling, a client can open `GET /api/ads/{id}/events`, which
returns a Server-Sent Events stream. `AdStatusEventPublisher` keeps an
in-process map of ad ID to open SSE emitters; every status transition in the
worker and review paths calls `publish(adId, status)`, which pushes an event
to any open subscribers for that ad and drops dead connections. This is
in-process state, not distributed — a subscriber is only notified by the
instance it connected to, which is consistent with this being a single-node
deployment today.

## Fraud detection and view tokens

Two layers work together on the view-recording path (`GET /api/ads/{id}`):

1. **IP sliding window.** `FraudService` keeps a Redis sorted set per IP,
   scored by request timestamp, pruning anything outside a 60-second window.
   More than 30 requests in that window from one IP is treated as fraud.
   This is distributed (Redis-backed, not per-instance memory) and survives
   restarts.
2. **Signed view tokens.** Every time `EmbedService` renders the widget HTML
   for `GET /embed/{token}`, it also issues a short-lived (2 minute),
   HMAC-signed, single-use token via `ViewTokenService` and embeds it into
   the widget's own fetch call. When `GET /api/ads/{id}` receives a valid
   token bound to that ad ID, it trusts the request as coming from a genuine
   widget load and skips the IP check; the token's nonce is marked used in
   Redis so it cannot be replayed. Requests without a valid token (e.g.
   direct API callers) fall back to the IP check.

This does not defend against sophisticated fraud (device fingerprinting,
bot detection, cross-signal correlation are all out of scope) — it closes
the specific gaps of the check being trivially bypassable by IP rotation and
not surviving a restart or working across instances.

## Billing and payments

`BillingService.recordView()` is called on every successfully-served ad
view. It looks up the view's `AdVersion` -> `Ad` -> `Campaign` chain, computes
a per-view cost from a locale-based rate table, and either records the view
and increments `campaign.spent`, or — if that would exceed the campaign's
budget — skips recording and blocks the view.

Budget itself is funded through Stripe: `POST /api/campaigns/{id}/fund`
creates a Stripe `PaymentIntent` for the requested amount and a `pending`
`Payment` row, returning a client secret for the frontend to confirm payment
client-side. `POST /api/payments/webhook` is the other half — a public,
signature-verified endpoint Stripe calls on payment events. On
`payment_intent.succeeded`, it marks the `Payment` row `succeeded` and
increases the campaign's `budget` by the funded amount, guarded so a
duplicate webhook delivery doesn't double-apply the funds.

## Error handling

A single `GlobalExceptionHandler` (`common/exceptions/`) maps every
exception type used across the codebase to a consistent JSON body:
`{error, status, path, timestamp}`. The one exception is the rate-limit
filter, which runs as a raw Servlet `Filter` ahead of Spring's own
exception-handling machinery and so cannot use `@ExceptionHandler`; it
hand-builds JSON in the same shape instead.

## Rate limiting and CORS

A `Bucket4j`-backed filter (`config/RateLimitFilter`) applies a per-client
token bucket ahead of authentication, keyed by user identity when
authenticated or by IP/`X-Forwarded-For` otherwise. Exceeding the limit
returns 429 in the same structured error shape as everything else. CORS
policy (allowed origins/methods/headers) is externalized to
`CorsProperties` and applied globally in `SecurityConfig`.

## AI provider abstraction

Moderation, translation, and speech evaluation are each defined as an
interface in `ai/`, with two implementations selected by the
`app.ai.provider` property: `mock` (deterministic, keyword-driven —
useful for exercising every branch of the pipeline without a real model)
and `local` (calls out to a locally-hosted service). This is the seam where
a real third-party AI provider would be plugged in without touching
`ValidationService` or `FeatureProcessingService`.
