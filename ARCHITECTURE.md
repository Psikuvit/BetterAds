# BetterAds — Architecture

This document describes the full system architecture with diagrams showing
how data moves from client to server and back.

## System Overview

BetterAds is a modular monolith built on Spring Boot. A single JVM serves
both the REST API and the RabbitMQ consumer. Code is organized into packages
by responsibility (auth, billing, fraud, AI, etc.), and an async worker
pipeline handles ad processing via RabbitMQ.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          BetterAds Application                              │
│                            (Spring Boot 4.1)                                │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │                     Security Filter Chain                          │    │
│  │  RateLimitFilter → SecurityHeadersFilter → JwtAuthFilter           │    │
│  └──────────────────────────────┬──────────────────────────────────────┘    │
│                                 │                                           │
│  ┌──────────────────────────────┴──────────────────────────────────────┐    │
│  │                         REST API Layer                              │    │
│  │                                                                     │    │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐     │    │
│  │  │  Auth   │ │ Upload  │ │Campaign │ │   Ad    │ │Analytics│     │    │
│  │  │/auth/** │ │/upload/ │ │/campaign│ │/ads/**  │ │/analytics│     │    │
│  │  │         │ │presign  │ │  /**    │ │         │ │/**      │     │    │
│  │  │         │ │confirm  │ │         │ │         │ │         │     │    │
│  │  └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘     │    │
│  │       │           │           │           │           │            │    │
│  │  ┌────┴────┐ ┌────┴────┐ ┌───┴────┐ ┌────┴────┐ ┌────┴────┐     │    │
│  │  │  Embed  │ │ Payment │ │  Link  │ │  SSE    │ │Validation│     │    │
│  │  │/embed/  │ │/payments│ │/links/ │ │/events  │ │/validation│    │    │
│  │  │{token}  │ │/webhook │ │{adId}  │ │         │ │/review  │     │    │
│  │  └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘     │    │
│  │       │                                                            │    │
│  │  ┌────┴──────────────────────┐  Phase 1 of iframe → SDK migration,│    │
│  │  │      Placements           │  runs parallel to /embed/** above  │    │
│  │  │ /api/v1/placements/**     │  (not a replacement yet):          │    │
│  │  │ POST {siteKey}/session    │  SiteService, SessionService,      │    │
│  │  │ POST session/{tok}/events │  SessionTokenService               │    │
│  │  │ /api/sites (registration) │                                    │    │
│  │  └────┬───────────────────────┘                                   │    │
│  └───────┼───────────┼───────────┼───────────┼───────────┼──────┬─────┘    │
│          │           │           │           │           │      │           │
│  ┌───────┴───────────┴───────────┴───────────┴───────────┴──────┴──────┐    │
│  │                     Business Services Layer                        │    │
│  │                                                                     │    │
│  │  StorageService    EmbedService     FraudService    BillingService  │    │
│  │  AdCleanupService  LinkService      ViewTokenService                │    │
│  │  AdLifecycleService AuthService     CurrentUserService              │    │
│  │                         PaymentRateLimiter                          │    │
│  │  SiteService        SessionService       SessionTokenService        │    │
│  │  AdVariantResolver (shared locale-resolution, also used by the      │    │
│  │                      legacy AdController)                           │    │
│  └──────────────────────────────┬──────────────────────────────────────┘    │
│                                 │                                           │
│  ┌──────────────────────────────┴──────────────────────────────────────┐    │
│  │                  Async Processing (RabbitMQ)                       │    │
│  │                                                                     │    │
│  │  ProcessingQueueService → RabbitMQ → WorkerConsumer                 │    │
│  │                                          │                          │    │
│  │                     ┌────────────────────┤                          │    │
│  │                     │                    │                          │    │
│  │              ValidationService    FeatureProcessingService          │    │
│  │              (Moderation)          (Translation + Speech)           │    │
│  │                     │                    │                          │    │
│  │              AdStatusEventPublisher (SSE)                           │    │
│  └──────────────────────────────┬──────────────────────────────────────┘    │
│                                 │                                           │
│  ┌──────────────────────────────┴──────────────────────────────────────┐    │
│  │                    AI Provider Abstraction                          │    │
│  │                                                                     │    │
│  │  ModerationService  TranslationService  SpeechEvaluationService    │    │
│  │     │                    │                      │                   │    │
│  │  ┌──┴──┐            ┌────┴───┐            ┌────┴───┐              │    │
│  │  │Mock │            │Mock    │            │Mock    │              │    │
│  │  │Local│            │Local   │            │Local   │              │    │
│  │  │HF   │            │HF      │            │(N/A)   │              │    │
│  │  └─────┘            └────────┘            └────────┘              │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                             │
│  ┌───────────────────────┐  ┌──────────────────────┐                       │
│  │ Security & Config     │  │ Common/Exceptions    │                       │
│  │ JWT · CORS · Headers  │  │ GlobalExceptionHandler│                       │
│  └───────────────────────┘  └──────────────────────┘                       │
└─────────────────────────────────────────────────────────────────────────────┘
            │                       │                    │
            ▼                       ▼                    ▼
   ┌─────────────┐       ┌──────────────┐       ┌─────────────┐
   │   MySQL     │       │    Redis     │       │   AWS S3    │
   │ (TiDB Cloud)│       │  (Upstash)   │       │             │
   │             │       │              │       │ video blobs │
   │ users       │       │ fraud:*      │       │             │
   │ campaigns   │       │ view-token-* │       │ presigned   │
   │ ads         │       │ link-cache   │       │ PUT / GET   │
   │ ad_versions │       │ rate-limit   │       │             │
   │ views       │       │ fund-limit   │       └─────────────┘
   │ ad_links    │       │ placement:   │
   │ payments    │       │  event:*     │
   │ stripe_events│      └──────────────┘
   │ refresh_tokens│
   │ password_reset│
   │ sites       │
   │ ad_sessions │
   │ session_events│
   └─────────────┘
```

## Data Flow 1: Ad Upload Pipeline

This is the primary flow — from advertiser upload to live ad.

```
Advertiser                BetterAds                    S3              RabbitMQ
    │                         │                          │                  │
    │  1. POST /api/upload/   │                          │                  │
    │     presign             │                          │                  │
    │  {key, contentType}     │                          │                  │
    ├────────────────────────>│                          │                  │
    │                         │  2. Generate presigned   │                  │
    │                         │     PUT URL              │                  │
    │                         ├─────────────────────────>│                  │
    │                         │<── presigned URL ────────┤                  │
    │  {url: presignedUrl}    │                          │                  │
    │<────────────────────────┤                          │                  │
    │                         │                          │                  │
    │  3. PUT video bytes     │                          │                  │
    │     directly to S3      │                          │                  │
    ├─────────────────────────────────────────────────────>│                │
    │                         │                          │                  │
    │  4. POST /api/upload/   │                          │                  │
    │     confirm             │                          │                  │
    │  {campaignId, title,    │                          │                  │
    │   storageKey, locale}   │                          │                  │
    ├────────────────────────>│                          │                  │
    │                         │  5. Verify S3 object     │                  │
    │                         │     (HEAD metadata)      │                  │
    │                         ├─────────────────────────>│                  │
    │                         │<── size, content-type ───┤                  │
    │                         │                          │                  │
    │                         │  6. Create Ad entity     │                  │
    │                         │     (status = PENDING)   │                  │
    │                         ├─────────────────────────>│                  │
    │                         │                          │  7. Enqueue      │
    │                         │                          │     adId         │
    │                         │                          ├─────────────────>│
    │  {status: "accepted",   │                          │                  │
    │   adId: N}              │                          │                  │
    │<────────────────────────┤                          │                  │
    │                         │                          │                  │
    │  ─ ─ ─ SSE: status=PENDING ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┤
```

## Data Flow 2: Async Processing Pipeline

Once enqueued, the worker drives the ad through its lifecycle.

```
RabbitMQ          WorkerConsumer       ValidationService    AI Provider (moderation)
    │                   │                      │                     │
    │  1. Consume adId  │                      │                     │
    ├──────────────────>│                      │                     │
    │                   │  2. status = VALIDATING                   │
    │                   ├──────────────────>  DB                    │
    │                   │  ─ ─ SSE: VALIDATING ─ ─ ─ ─ ─ ─ ─ ─ ─ > │
    │                   │                      │                     │
    │                   │  3. validate()        │                     │
    │                   ├─────────────────────>│                     │
    │                   │                      │  4. moderate()      │
    │                   │                      ├────────────────────>│
    │                   │                      │  APPROVED/FLAGGED/  │
    │                   │                      │  REJECTED           │
    │                   │<─────────────────────┤                     │
    │                   │                      │                     │
    │  ┌────────────────┤                      │                     │
    │  │ APPROVED?      │                      │                     │
    │  │                │                      │                     │
    │  │  5a. status = AWAITING_FEATURES       │                     │
    │  │  ─ ─ SSE: AWAITING_FEATURES ─ ─ ─ ─ > │                    │
    │  │                                      │                     │
    │  │  6a. POST /features → 202 Accepted   │                     │
    │  │  (async: CompletableFuture.runAsync)  │                     │
    │  │  ─ ─ SSE: PROCESSING ─ ─ ─ ─ ─ ─ ─ > │                    │
    │  │                                      │                     │
    │  │  ┌───────────────────────────────────┤                     │
    │  │  │                                   │                     │
    │  │  │  FeatureProcessingService         │                     │
    │  │  │  For each locale:                 │                     │
    │  │  │  ┌────────────────────────────┐   │                     │
    │  │  │  │ translate(storageKey, locale)──>│ AI Provider        │
    │  │  │  │   → translatedKey          │   │ (translation)      │
    │  │  │  │                            │   │                    │
    │  │  │  │ evaluate(translatedKey)    │   │ AI Provider        │
    │  │  │  │   → score                  │   │ (speech eval)     │
    │  │  │  │                            │   │                    │
    │  │  │  │ Store translated variant   │   │                    │
    │  │  │  │ → S3                       │   │                    │
    │  │  │  │                            │   │                    │
    │  │  │  │ Persist AdVersion          │   │                    │
    │  │  │  │ → DB                       │   │                    │
    │  │  │  └────────────────────────────┘   │                     │
    │  │  │                                   │                     │
    │  │  │  7a. status = LIVE                │                     │
    │  │  │  ─ ─ SSE: LIVE ─ ─ ─ ─ ─ ─ ─ ─ >│                     │
    │  │  │                                   │                     │
    │  │  │  8a. Generate embed link          │                     │
    │  │  │  → AdLink (UUID token)            │                     │
    │  │  │  → DB                             │                     │
    │  │  └───────────────────────────────────┤                     │
    │  │                                      │                     │
    │  ├──────────────────────────────────────┤                     │
    │  │                                      │                     │
    │  │ SKIP TRANSLATION (locales=[])        │                     │
    │  │ 6b. Create AdVersion for original    │                     │
    │  │     video → status = LIVE            │                     │
    │  │  ─ ─ SSE: LIVE ─ ─ ─ ─ ─ ─ ─ ─ ─ >│                     │
    │  │                                      │                     │
    │  │ FLAGGED?                             │                     │
    │  │ 5b. status = FLAGGED                 │                     │
    │  │ ─ ─ SSE: FLAGGED ─ ─ > (waits for admin review)          │
    │  │                                      │                     │
    │  │ REJECTED?                            │                     │
    │  │ 5c. status = REJECTED                │                     │
    │  │ ─ ─ SSE: REJECTED ─ ─ >            │                     │
    │  │                                      │                     │
    │  │ EXCEPTION?                           │                     │
    │  │ 5d. status = FAILED                  │                     │
    │  │ ─ ─ SSE: FAILED ─ ─ >             │                     │
    │  └──────────────────────────────────────┤                     │
```

### Feature Selection (POST /api/ads/{id}/features)

The `/features` endpoint is **async** — it returns `202 Accepted` immediately
and dispatches translation to a background thread via
`CompletableFuture.runAsync()`. This avoids Render's 30-second proxy timeout
during the HuggingFace translation call (~18s+).

- `locales=[]` (skip translation): Creates an `AdVersion` pointing to the
  original video, sets status LIVE, returns 200 immediately.
- `locales=["fr"]`: Sets status PROCESSING, returns 202, processes in
  background. SSE events notify the frontend of status changes in real-time.

## Data Flow 3: Ad Serving (Viewer → Widget → API → S3)

The embed widget is served at campaign level (`GET /api/campaigns/{id}/embed`
returns the embed URL for the campaign's first LIVE ad). The widget cycles
through all LIVE ads in the campaign endlessly using a two-video swap for
seamless transitions. The widget reports its natural video dimensions to the
parent iframe via `postMessage` for dynamic sizing.

```
Viewer               Publisher Website      BetterAds              S3       Redis
    │                      │                   │                     │        │
    │  1. Visit page       │                   │                     │        │
    │  with embedded ad    │                   │                     │        │
    ├─────────────────────>│                   │                     │        │
    │                      │                   │                     │        │
    │  2. Browser loads    │                   │                     │        │
    │  <iframe>            │                   │                     │        │
    │                      ├──────────────────>│                     │        │
    │                      │  GET /embed/{token}                     │        │
    │                      │                   │                     │        │
    │                      │                   │  3. Resolve token   │        │
    │                      │                   │     → AdLink → adId │        │
    │                      │                   ├──────────────────> DB       │
    │                      │                   │<── adId ────────────┤       │
    │                      │                   │                     │        │
    │                      │                   │  4. Issue view      │        │
    │                      │                   │     token (HMAC,    │        │
    │                      │                   │     2min, nonce)    │        │
    │                      │                   ├───────────────────────────> │
    │                      │                   │<── stored ──────────────────┤
    │                      │                   │                     │        │
    │  <html> with         │                   │                     │        │
    │  <video> + <script>  │                   │                     │        │
    │<─────────────────────┤                   │                     │        │
    │                      │                   │                     │        │
    │  5. Script detects   │                   │                     │        │
    │  browser locale,     │                   │                     │        │
    │  fetches playlist:   │                   │                     │        │
    │  GET /api/ads/{id}/  │                   │                     │        │
    │  playlist?locale=&vt=│                   │                     │        │
    ├─────────────────────────────────────────>│                     │        │
    │                      │                   │                     │        │
    │                      │                   │  6. Validate view   │        │
    │                      │                   │     token           │        │
    │                      │                   ├───────────────────────────> │
    │                      │                   │<── valid, mark used ───────┤
    │                      │                   │                     │        │
    │                      │                   │  7. Fraud check     │        │
    │                      │                   │     (skip if token  │        │
    │                      │                   │     was valid)      │        │
    │                      │                   ├───────────────────────────> │
    │                      │                   │<── OK / BLOCKED ───────────┤
    │                      │                   │                     │        │
    │                      │                   │  8. Record view     │        │
    │                      │                   │     for EACH ad     │        │
    │                      │                   │     in campaign     │        │
    │                      │                   ├──────────────────> DB       │
    │                      │                   │                     │        │
    │                      │                   │  9. Presign GET    │        │
    │                      │                   │     URLs for each   │        │
    │                      │                   │     ad's version    │        │
    │                      │                   ├─────────────────────>│       │
    │                      │                   │<── presigned URLs ──┤       │
    │                      │                   │                     │        │
    │  {ads: [{adId, url,  │                   │                     │        │
    │    locale, vt}...]}  │                   │                     │        │
    │<─────────────────────────────────────────┤                     │        │
    │                      │                   │                     │        │
    │  10. Two-video swap: │                   │                     │        │
    │  video A plays,      │                   │                     │        │
    │  video B preloads    │                   │                     │        │
    │  next. On ended:     │                   │                     │        │
    │  swap z-index, play  │                   │                     │        │
    │  B, preload A.       │                   │                     │        │
    │  Loop endlessly.     │                   │                     │        │
    │                      │                   │                     │        │
    │  11. postMessage     │                   │                     │        │
    │  {type:"ad-resize",  │                   │                     │        │
    │   width, height}     │                   │                     │        │
    │  → parent resizes    │                   │                     │        │
    │    iframe            │                   │                     │        │
```

### Campaign-Level Embed

The embed is served from the campaign dashboard (`GET /api/campaigns/{id}/embed`),
not from individual ad pages. The endpoint returns the embed URL/snippet for the
campaign's first LIVE ad, or `{available: false}` if no live ads exist. The
individual ad detail page (`/ads/[id]`) still exists for per-ad status/
lifecycle actions and its own untracked preview (see "Ad/Campaign Preview"
below) — it just isn't where the real embed code comes from.

### Ad/Campaign Preview (dashboard-only, untracked)

Distinct from Data Flow 3 above (which is the real, public, tracked path a
*publisher's* page hits): `GET /api/ads/{id}/preview` (single ad, `/ads/[id]`)
and `GET /api/campaigns/{id}/preview` (every LIVE ad in the campaign, i.e. a
playlist, `/campaigns/[id]`) let an *advertiser* watch their own ad(s) inside
the dashboard. Both are `ADVERTISER`/`ADMIN`-authenticated, ownership-checked,
and resolve a presigned video URL via the shared `AdPreviewService` —
crucially, neither calls `BillingService.recordView`, `FraudService`, or
`ViewTokenService`, and neither needs a `Site`/site key at all. Opening your
own ad or campaign in the dashboard is therefore never counted as a served
impression. The campaign variant is a thin wrapper: for each of the
campaign's LIVE ads, it calls the same `AdPreviewService.resolve()` the
single-ad endpoint uses and returns the list.

## Data Flow 4: Authentication

```
Client                    BetterAds                MySQL       Redis
  │                          │                        │          │
  │  POST /auth/register     │                        │          │
  │  {email, password, role} │                        │          │
  ├─────────────────────────>│                        │          │
  │                          │  1. BCrypt hash pwd    │          │
  │                          ├───────────────────────>│          │
  │                          │  2. Save User          │          │
  │                          ├───────────────────────>│          │
  │                          │  3. Generate JWT       │          │
  │                          │     (HS256, 15min)     │          │
  │                          │  4. Generate refresh   │          │
  │                          │     token (32 bytes)   │          │
  │                          │  5. Hash + store       │          │
  │                          ├───────────────────────>│          │
  │  {token, refreshToken,   │                        │          │
  │   email, role}           │                        │          │
  │<─────────────────────────┤                        │          │
  │                          │                        │          │
  │  POST /auth/login        │                        │          │
  │  {email, password}       │                        │          │
  ├─────────────────────────>│                        │          │
  │                          │  6. Find user by email │          │
  │                          ├───────────────────────>│          │
  │                          │  7. Verify BCrypt      │          │
  │  {token, refreshToken,   │                        │          │
  │   email, role}           │                        │          │
  │<─────────────────────────┤                        │          │
  │                          │                        │          │
  │  POST /auth/refresh      │                        │          │
  │  {refreshToken}          │                        │          │
  ├─────────────────────────>│                        │          │
  │                          │  8. Hash token, lookup │          │
  │                          ├───────────────────────>│          │
  │                          │  9. Verify not revoked,│          │
  │                          │     not expired        │          │
  │                          │  10. Revoke current    │          │
  │                          │      (set revokedAt)   │          │
  │                          ├───────────────────────>│          │
  │                          │  11. Issue new pair    │          │
  │  {token, refreshToken}   │                        │          │
  │<─────────────────────────┤                        │          │

  Every authenticated request:
  ┌────────────────────────────────────────────────────────────┐
  │ Request → JwtAuthFilter                                    │
  │   → Extract "Bearer xxx" from Authorization header         │
  │   → JwtTokenProvider.validateToken() (signature + expiry)  │
  │   → Extract email (subject) + role (claim)                 │
  │   → Set SecurityContextHolder with ROLE_{role}             │
  │   → Controller uses CurrentUserService.resolve()           │
  │     → DB lookup by email → User entity                     │
  └────────────────────────────────────────────────────────────┘
```

## Data Flow 5: Campaign Funding (Stripe)

```
Advertiser              BetterAds              Stripe              MySQL      Redis
    │                      │                     │                   │          │
    │  POST /api/campaigns │                     │                   │          │
    │  /{id}/fund          │                     │                   │          │
    │  {amount: 50.00}     │                     │                   │          │
    ├─────────────────────>│                     │                   │          │
    │                      │                     │                   │          │
    │                      │  1. Verify campaign │                   │          │
    │                      │     exists + owned   │                   │          │
    │                      ├─────────────────────────────────────────>│         │
    │                      │                     │                   │          │
    │                      │  2. Rate limit      │                   │          │
    │                      │     (5/hour/user)   │                   │          │
    │                      ├───────────────────────────────────────────────── >│
    │                      │<── OK ───────────────────────────────────────────┤
    │                      │                     │                   │          │
    │                      │  3. Create Stripe   │                   │          │
    │                      │     PaymentIntent   │                   │          │
    │                      ├────────────────────>│                   │          │
    │                      │<── clientSecret ────┤                   │          │
    │                      │                     │                   │          │
    │                      │  4. Persist Payment │                   │          │
    │                      │     (status=PENDING)│                   │          │
    │                      ├─────────────────────────────────────────>│         │
    │                      │                     │                   │          │
    │  {clientSecret,      │                     │                   │          │
    │   paymentIntentId}   │                     │                   │          │
    │<─────────────────────┤                     │                   │          │
    │                      │                     │                   │          │
    │  ┌─────────────────────────────────────────┤                   │          │
    │  │ 5. Stripe.js (client-side)              │                   │          │
    │  │ confirmCardPayment(clientSecret)        │                   │          │
    │  └─────────────────────────────────────────>│                   │          │
    │                      │                     │                   │          │
    │                      │  ┌──────────────────────────────────┐   │          │
    │                      │  │ 6. Stripe Webhook (async)        │   │          │
    │                      │  │ POST /api/payments/webhook       │   │          │
    │                      │  │ (Stripe → BetterAds)             │   │          │
    │                      │  ├──────────────────────────────────┤   │          │
    │                      │  │                                  │   │          │
    │                      │  │ 7. Verify signature              │   │          │
    │                      │  │                                  │   │          │
    │                      │  │ 8. Dedup check                   │   │          │
    │                      │  │    (INSERT stripe_events,        │   │          │
    │                      │  │     unique constraint)           │   │          │
    │                      │  │                                  │   │          │
    │                      │  │ 9. On payment_intent.succeeded:  │   │          │
    │                      │  │    Mark Payment = SUCCEEDED      │   │          │
    │                      │  │                                  │   │          │
    │                      │  │ 10. Campaign.budget += amount    │   │          │
    │                      │  │     (SELECT FOR UPDATE lock)     │   │          │
    │                      │  └──────────────────────────────────┤   │          │
    │                      │                     │                   │          │
```

## Data Flow 6: Real-Time Status (SSE)

```
Advertiser Dashboard          BetterAds                    Worker
        │                        │                           │
        │  GET /api/ads/{id}/    │                           │
        │  events                │                           │
        ├───────────────────────>│                           │
        │                        │  1. Subscribe adId        │
        │                        │     Create SseEmitter     │
        │                        │     Store in              │
        │                        │     ConcurrentHashMap     │
        │<── SSE stream ─────────┤                           │
        │    (connection kept    │                           │
        │     open, 5min timeout)│                           │
        │                        │                           │
        │    :ping (every 15s)   │                           │
        │<───────────────────────┤                           │
        │                        │                           │
        │                        │  ... worker processes ... │
        │                        │<──────────────────────────┤
        │                        │                           │
        │  event: status         │  2. publish(adId,         │
        │  data: {adId,          │     "VALIDATING")         │
        │   status:"VALIDATING"} │                           │
        │<───────────────────────┤                           │
        │                        │                           │
        │  event: status         │  3. publish(adId,         │
        │  data: {adId,          │     "PROCESSING")         │
        │   status:"PROCESSING"} │                           │
        │<───────────────────────┤                           │
        │                        │                           │
        │  event: status         │  4. publish(adId, "LIVE") │
        │  data: {adId,          │                           │
        │   status:"LIVE"}       │                           │
        │<───────────────────────┤                           │
```

## Data Flow 7: Admin Review (Flagged Ad)

```
Admin                   BetterAds               AI Provider
  │                        │                        │
  │  PATCH /api/ads/{id}/  │                        │
  │  review                │                        │
  │  {action: "approve"}   │                        │
  ├───────────────────────>│                        │
  │                        │  1. Verify ADMIN role  │
  │                        │  2. Check status=FLAGGED│
  │                        │                        │
  │                        │  3. status=AWAITING_FEATURES
  │                        │  ─ ─ SSE: AWAITING_FEATURES ─ > │
  │                        │                        │
  │  Advertiser then calls │                        │
  │  POST /features → 202  │                        │
  │  (async processing)    │                        │
  │                        │                        │
  │                        │  4. FeatureProcessing  │
  │                        │     translate()        │
  │                        ├───────────────────────>│
  │                        │  evaluate()            │
  │                        ├───────────────────────>│
  │                        │  persist AdVersion     │
  │                        ├──────────────────> DB  │
  │                        │                        │
  │                        │  5. status = LIVE      │
  │                        │  ─ ─ SSE: LIVE ─ ─ ─ >│
  │                        │                        │
  │                        │  6. Generate embed link│
  │                        ├──────────────────> DB  │
  │                        │                        │
  │  {status: "live"}      │                        │
  │<───────────────────────┤                        │
  │                        │                        │
  │  REJECT:               │                        │
  │  PATCH /review         │                        │
  │  {action: "reject"}    │                        │
  │  → status = REJECTED   │                        │
  │  ─ ─ SSE: REJECTED ─ >│                        │
```

### Ad Deletion

Both admins and advertisers can delete ads:

- **Admins**: Can delete any ad via `DELETE /api/ads/{id}`.
- **Advertisers**: Can delete ads belonging to their own campaigns.
  The endpoint checks ownership via `CurrentUserService` and
  `CampaignRepository`.

Deletion cascades through S3 and DB:
`AdCleanupService.deleteAd()` removes S3 files, views, ad versions,
ad links, and the ad entity itself.

## Data Flow 8: Placement Session + Playback Events (SDK migration, Phase 1)

Runs entirely in parallel with Data Flow 3 above — `/embed/{token}` is
untouched. This is the trust model the future iframe-free SDK will use.

```
SDK/Client            BetterAds                 FraudService   BillingService   Redis/DB
    │                     │                          │              │              │
    │  1. POST /api/v1/   │                          │              │              │
    │  placements/{siteKey}/session                   │              │              │
    │  {adId, locale}     │                          │              │              │
    ├────────────────────>│                          │              │              │
    │                     │  2. Look up Site by key, │              │              │
    │                     │     check ACTIVE         │              │              │
    │                     │  3. Validate Origin/     │              │              │
    │                     │     Referer or bundleId  │              │              │
    │                     │     against registration │              │              │
    │                     │  4. isLikelyFraud(ip)    │              │              │
    │                     │     — ALWAYS checked,    │              │              │
    │                     │     no token bypass      │              │              │
    │                     ├─────────────────────────>│              │              │
    │                     │<── OK / 429 ─────────────┤              │              │
    │                     │  5. isCampaignOverVelocity              │              │
    │                     ├─────────────────────────>│              │              │
    │                     │<── OK / 429 ─────────────┤              │              │
    │                     │  6. Resolve best AdVersion              │              │
    │                     │     for locale (AdVariantResolver)      │              │
    │                     │  7. Create AdSession row,               │              │
    │                     │     issue signed sessionToken           │              │
    │                     ├────────────────────────────────────────────────────────>│
    │  {sessionToken,     │                          │              │              │
    │   adId, adVersionId,│                          │              │              │
    │   videoUrl, locale, │                          │              │              │
    │   durationSeconds}  │                          │              │              │
    │<────────────────────┤                          │              │              │
    │                     │                          │              │              │
    │  8. Play video,     │                          │              │              │
    │  wait 2s+ visible   │                          │              │              │
    │  (IAB viewability)  │                          │              │              │
    │                     │                          │              │              │
    │  9. POST .../session/{token}/events            │              │              │
    │  {eventType:        │                          │              │              │
    │   "impression_start"}                          │              │              │
    ├────────────────────>│                          │              │              │
    │                     │  10. Verify signature+expiry            │              │
    │                     │  11. Redis dedup + state-machine        │              │
    │                     │      order check (reject dup/           │              │
    │                     │      out-of-order → 409)                │              │
    │                     ├────────────────────────────────────────────────────────>│
    │                     │  12. Reject if <2000ms since             │              │
    │                     │      session issuance (viewability      │              │
    │                     │      floor) → 409                       │              │
    │                     │  13. recordView(adVersionId, ip, ...)   │              │
    │                     ├─────────────────────────────────────────────────────>│  │
    │                     │<── billed: true/false ──────────────────────────────┤  │
    │  {accepted: true,   │                          │              │              │
    │   billed: true}     │                          │              │              │
    │<────────────────────┤                          │              │              │
    │                     │                          │              │              │
    │  14. POST quartile_25/50/75, complete events    │              │              │
    │  (same session, strictly ordered, each          │              │              │
    │  single-use, persisted to session_events        │              │              │
    │  for the durable audit trail) ─ ─ ─ ─ ─ ─ ─ ─ ─>│              │              │
```

Key differences from Data Flow 3's legacy model: the per-IP fraud check runs
unconditionally (no valid-token bypass), billing fires exactly once per
session gated on a real elapsed-time viewability floor rather than "an API
call happened," and every session/event is durably persisted instead of
relying solely on an ephemeral Redis nonce. See
`docs/phase1-fraud-comparison.md` for the full write-up.

## Security Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    Request Flow Through Security                │
│                                                                 │
│  Incoming Request                                               │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────────────┐                                        │
│  │  RateLimitFilter    │  Bucket4j token bucket                 │
│  │  (Before auth)      │  Key: userId or IP                     │
│  │                     │  Exceeds → 429                         │
│  └──────────┬──────────┘                                        │
│             │ pass                                               │
│             ▼                                                    │
│  ┌─────────────────────┐                                        │
│  │  SecurityHeaders    │  X-Content-Type-Options                │
│  │  Filter             │  Referrer-Policy                       │
│  │                     │  X-Frame-Options                       │
│  └──────────┬──────────┘                                        │
│             │ pass                                               │
│             ▼                                                    │
│  ┌─────────────────────┐                                        │
│  │  JwtAuthentication  │  Extract "Bearer xxx"                  │
│  │  Filter             │  Validate JWT (HS256 + expiry)         │
│  │                     │  Set SecurityContext (email + role)     │
│  └──────────┬──────────┘                                        │
│             │ pass                                               │
│             ▼                                                    │
│  ┌─────────────────────┐                                        │
│  │  Spring Security    │  @PreAuthorize role checks             │
│  │  Authorization      │  Ownership checks in controllers       │
│  └─────────────────────┘                                        │
└─────────────────────────────────────────────────────────────────┘

Token Strategy:
┌─────────────────────────────────────────────────────────────────┐
│  Access Token:  HS256 JWT, 15min expiry, stateless              │
│  Refresh Token: 32-byte opaque, SHA-256 hashed in DB,           │
│                 single-use with rotation, 7-day expiry           │
│  Password Reset: Same as refresh but 30min expiry, one-time use  │
│  View Token:    HMAC-SHA256 signed, 2min expiry, one-time use   │
│                 nonce tracked in Redis                           │
│  Session Token: HMAC-SHA256 signed (own dedicated secret,       │
│                 not shared with JWT), configurable TTL           │
│                 (default 15min), looked up by exact match in    │
│                 ad_sessions — Placements API only                │
└─────────────────────────────────────────────────────────────────┘

Two Filter Chains:
┌─────────────────────────────────────────────────────────────────┐
│  Chain 1 (Order 1): /embed/**                                   │
│    → Permits all                                                 │
│    → Disables frame options (allows iframe embedding)            │
│                                                                 │
│  Chain 2 (Order 2): Everything else                             │
│    → CORS enabled                                                │
│    → Stateless sessions                                          │
│    → JWT filter active                                           │
│    → Role-based authorization                                    │
└─────────────────────────────────────────────────────────────────┘
```

## Fraud Detection Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                  Three-Layer Fraud Detection                     │
│                                                                 │
│  Layer 1: IP Sliding Window                                     │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  Redis sorted set: fraud:ip:{ip}                        │    │
│  │  Window: 60 seconds                                     │    │
│  │  Limit: 30 requests/IP/minute                           │    │
│  │  Distributed, survives restarts                         │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                 │
│  Layer 2: Campaign Velocity Cap                                 │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  Redis counter: fraud:campaign:{campaignId}             │    │
│  │  Window: 60 seconds                                     │    │
│  │  Limit: 200 views/campaign/minute                       │    │
│  │  Catches botnet/proxy rotation attacks                  │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                 │
│  Layer 3: Signed View Tokens                                    │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  Issued by EmbedService on widget render                │    │
│  │  HMAC-SHA256 signed, 2-minute TTL                       │    │
│  │  One-time use (nonce tracked in Redis)                  │    │
│  │  Valid token → skip IP check (proves genuine widget)    │    │
│  │  Invalid/missing → fall back to IP sliding window       │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                 │
│  Payment Rate Limiting:                                         │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  Redis counter: fraud:fund:{advertiserId}               │    │
│  │  Window: 1 hour                                         │    │
│  │  Limit: 5 funding attempts/hour/advertiser              │    │
│  │  Guards against card-testing abuse                      │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘

Placements API (parallel model, Data Flow 8): reuses Layers 1 and 2 above via
the same FraudService, but always evaluates them — no signed-token bypass.
Replaces Layer 3's "valid token → skip IP check" with a session+event model:
billing fires once per session, gated on a real elapsed-time viewability
floor (2s+ since session issuance) rather than trusting that an API call
happened, and every session/event persists durably to MySQL
(session_events, UNIQUE(session_id, event_type)) instead of relying solely
on an ephemeral Redis nonce. See docs/phase1-fraud-comparison.md.
```

## Billing Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    Per-View Billing Flow                         │
│                                                                 │
│  GET /api/ads/{id} (legacy)         → BillingService            │
│  POST .../events {impression_start} → .recordView()             │
│  (Placements API, once per session — return value is boolean,  │
│   checked by the caller: budget-exhausted → billed:false)       │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────────────────┐                                    │
│  │ Load AdVersion → Ad →   │                                    │
│  │ Campaign                │                                    │
│  │ (SELECT FOR UPDATE on   │                                    │
│  │  campaign row)          │                                    │
│  └──────────┬──────────────┘                                    │
│             │                                                    │
│             ▼                                                    │
│  ┌─────────────────────────┐                                    │
│  │ Calculate cost:         │                                    │
│  │ base_rate(locale)       │                                    │
│  │ + surcharge(feature)    │                                    │
│  └──────────┬──────────────┘                                    │
│             │                                                    │
│       ┌─────┴─────┐                                             │
│       │           │                                             │
│       ▼           ▼                                             │
│  ┌─────────┐ ┌─────────────┐                                    │
│  │ spent + │ │ spent +     │                                    │
│  │ cost <= │ │ cost >      │                                    │
│  │ budget  │ │ budget      │                                    │
│  └────┬────┘ └──────┬──────┘                                    │
│       │              │                                           │
│       ▼              ▼                                           │
│  ┌──────────┐  ┌──────────────────┐                              │
│  │ Create   │  │ Campaign =       │                              │
│  │ View row │  │ COMPLETED        │                              │
│  │ Update   │  │ Delete ALL ads   │                              │
│  │ campaign │  │ (S3 + DB cascade │                              │
│  │ .spent   │  │ via AdCleanup    │                              │
│  └──────────┘  │ Service)         │                              │
│                └──────────────────┘                               │
│                                                                 │
│  Locale Rate Table:                                             │
│  ┌────────┬───────────────┐                                     │
│  │ US     │ $0.0015/view  │                                     │
│  │ GB     │ $0.0014/view  │                                     │
│  │ DE     │ $0.0013/view  │                                     │
│  │ Other  │ $0.0010/view  │                                     │
│  │ +translation surcharge │ +$0.001/view                        │
│  └────────┴───────────────┘                                     │
└─────────────────────────────────────────────────────────────────┘
```

## Database Schema

```
┌──────────────┐       ┌──────────────┐       ┌──────────────┐
│    users     │       │  campaigns   │       │     ads      │
├──────────────┤       ├──────────────┤       ├──────────────┤
│ id (PK)      │◄──┐   │ id (PK)      │◄──┐   │ id (PK)      │◄──┐
│ email (UQ)   │   │   │ advertiser_id│───┘   │ campaign_id  │───┘
│ password_hash│   │   │ name         │       │ title        │
│ role         │   │   │ budget       │       │ storage_key  │
│ created_at   │   │   │ spent        │       │ status       │
└──────────────┘   │   │ status       │       │ target_locale│
       │           │   │ created_at   │       │ created_at   │
       │           │   └──────────────┘       └──────┬───────┘
       │           │                                  │
       │           │   ┌──────────────┐       ┌──────┴───────┐
       │           │   │ ad_versions  │       │  ad_links    │
       │           │   ├──────────────┤       ├──────────────┤
       │           │   │ id (PK)      │◄──┐   │ id (PK)      │
       │           │   │ ad_id        │───┘   │ ad_id (UQ)   │
       │           │   │ locale       │       │ token (UQ)   │
       │           │   │ storage_key  │       │ created_at   │
       │           │   │ feature      │       └──────────────┘
       │           │   │ created_at   │
       │           │   └──────┬───────┘
       │           │          │
       │           │   ┌──────┴───────┐
       │           │   │    views     │
       │           │   ├──────────────┤
       │           │   │ id (PK)      │
       │           │   │ ad_version_id│
       │           │   │ viewed_at    │
       │           │   │ viewer_ip    │
       │           │   │ device_info  │
       │           │   └──────────────┘
       │           │
       │           │   ┌──────────────────┐
       │           │   │ refresh_tokens   │
       │           │   ├──────────────────┤
       │           │   │ id (PK)          │
       │           ├───│ user_id          │
       │           │   │ token_hash (UQ)  │
       │           │   │ expires_at       │
       │           │   │ revoked_at       │
       │           │   │ created_at       │
       │           │   └──────────────────┘
       │           │
       │           │   ┌──────────────────────┐
       │           │   │password_reset_tokens │
       │           │   ├──────────────────────┤
       │           ├───│ id (PK)              │
       │           │   │ user_id              │
       │           │   │ token_hash (UQ)      │
       │           │   │ expires_at           │
       │           │   │ used_at              │
       │           │   │ created_at           │
       │           │   └──────────────────────┘
       │           │
       │           │   ┌──────────────┐       ┌──────────────┐
       │           │   │  payments    │       │stripe_events │
       │           │   ├──────────────┤       ├──────────────┤
       │           ├───│ advertiser_id│       │ id (PK)      │
       │           │   │ id (PK)      │       │stripe_event_ │
       │           └───│ campaign_id  │       │  id (UQ)     │
       │               │ stripe_pi_id │       │ event_type   │
       │               │ (UQ)         │       │ processed_at │
       │               │ idempotency_ │       └──────────────┘
       │               │  key (UQ)    │
       │               │ amount       │
       │               │ currency     │
       │               │ status       │
       │               │ created_at   │
       │               └──────────────┘
       │
       │   ┌──────────────┐       ┌──────────────────┐       ┌──────────────────┐
       │   │    sites     │       │   ad_sessions     │       │  session_events  │
       │   ├──────────────┤       ├──────────────────┤       ├──────────────────┤
       └───│ id (PK)      │◄──┐   │ id (PK)          │◄──┐   │ id (PK)          │
           │ publisher_id │   │   │ site_id          │   │   │ session_id       │
           │ name         │   └───│ ad_id            │   └───│ event_type       │
           │ site_key (UQ)│       │ ad_version_id    │       │ recorded_at      │
           │ allowed_     │       │ campaign_id      │       │ UNIQUE(session_id│
           │  origin      │       │ session_token(UQ)│       │  , event_type)   │
           │ bundle_id    │       │ viewer_ip        │       └──────────────────┘
           │ status       │       │ device_info      │
           │ created_at   │       │ issued_at        │
           └──────────────┘       │ expires_at       │
                                  │ status           │
                                  └──────────────────┘
```

## Deployment Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Docker Compose (4 services)                  │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │  app (BetterAds)                                        │    │
│  │  Port: 8080                                              │    │
│  │  Multi-stage: Maven build → JRE 22 runtime              │    │
│  │  Healthcheck: /actuator/health/ping                      │    │
│  │  Depends on: mysql, redis, rabbitmq (all healthy)        │    │
│  │  Config: .env file                                       │    │
│  └─────────┬───────────────┬───────────────┬───────────────┘    │
│            │               │               │                    │
│            ▼               ▼               ▼                    │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │
│  │   mysql      │ │   redis      │ │  rabbitmq    │            │
│  │  MySQL 8.0   │ │  Redis 7     │ │  RabbitMQ 3  │            │
│  │  Port: 3306  │ │  Port: 6379  │ │  5672/15672  │            │
│  │  Persistent  │ │              │ │  Persistent  │            │
│  │  volume      │ │              │ │  volume      │            │
│  └──────────────┘ └──────────────┘ └──────────────┘            │
│                                                                 │
│  Production:                                                    │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │
│  │ TiDB Cloud   │ │ Upstash      │ │ Cloud-hosted │            │
│  │ (AWS)        │ │ (TLS)        │ │ RabbitMQ     │            │
│  └──────────────┘ └──────────────┘ └──────────────┘            │
└─────────────────────────────────────────────────────────────────┘
```

## API Endpoint Summary

### Auth (`/auth/**` — all public)
| Method | Path | Description |
|--------|------|-------------|
| POST | /auth/register | Register new user |
| POST | /auth/login | Login |
| GET | /auth/me | Current user info |
| POST | /auth/refresh | Rotate refresh token |
| POST | /auth/logout | Revoke refresh token |
| POST | /auth/forgot-password | Request password reset |
| POST | /auth/reset-password | Reset password |

### Upload & Management
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | /api/upload/presign | ADVERTISER | Get S3 presigned PUT URL |
| POST | /api/upload/confirm | ADVERTISER | Confirm upload, create Ad |
| POST | /api/campaigns | ADVERTISER | Create campaign |
| GET | /api/campaigns | ADVERTISER/ADMIN | List campaigns |
| GET | /api/campaigns/{id} | ADVERTISER/ADMIN | Get campaign |
| PATCH | /api/campaigns/{id} | ADVERTISER/ADMIN | Update campaign |
| PATCH | /api/campaigns/{id}/status | ADVERTISER/ADMIN | Update status |
| GET | /api/campaigns/{id}/ads | ADVERTISER/ADMIN | List ads in campaign |
| GET | /api/campaigns/{id}/analytics | ADVERTISER/ADMIN | Campaign analytics |
| GET | /api/campaigns/{id}/analytics/timeseries | ADVERTISER/ADMIN | Daily view counts |
| GET | /api/campaigns/{id}/ads/analytics | ADVERTISER/ADMIN | Per-ad breakdown |
| POST | /api/campaigns/{id}/fund | ADVERTISER | Stripe campaign funding |
| GET | /api/campaigns/{id}/preview | ADVERTISER/ADMIN | Untracked playlist preview of every LIVE ad, no site key |

### Ad Serving & Management
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | /api/ads/{id} | **PUBLIC** | Serve ad (fraud + billing) |
| GET | /api/ads/{id}/playlist | **PUBLIC** | Serve all LIVE ads in campaign |
| GET | /api/ads/{id}/link | ADVERTISER/ADMIN | Get embed URL/snippet |
| GET | /api/ads/{id}/preview | ADVERTISER/ADMIN | Untracked single-ad preview, no site key |
| GET | /api/ads/{id}/validation | ADVERTISER/ADMIN | Poll processing status |
| GET | /api/ads/{id}/events | ADVERTISER/ADMIN | SSE status stream |
| POST | /api/ads/{id}/features | ADVERTISER/ADMIN | Select locales or skip (empty = go LIVE immediately) |
| PATCH | /api/ads/{id}/review | **ADMIN** | Approve/reject flagged ad |
| DELETE | /api/ads/{id} | **ADMIN** | Fully delete ad (S3 + DB cascade) |

### Placements API (Phase 1 of iframe → SDK migration)
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | /api/v1/placements/{siteKey}/session | **PUBLIC** | Create a signed session + ad manifest (site key + origin validated) |
| POST | /api/v1/placements/session/{sessionToken}/events | **PUBLIC** | Record a playback event (ordered, single-use, viewability-gated) |
| POST | /api/sites | ADVERTISER/ADMIN | Register a site/app, get its non-secret site key |

### Other Public
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | /embed/{token} | **PUBLIC** | Serve embed widget HTML |
| GET | /api/links/{adId} | ADVERTISER | Redis-cached variant lookup |
| GET | /api/analytics/advertiser | ADVERTISER | Cross-campaign dashboard |
| POST | /api/payments/webhook | **PUBLIC** (Stripe sig) | Stripe webhook |
| GET | /actuator/health | **PUBLIC** | Health check |
