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
│  └───────┼───────────┼───────────┼───────────┼───────────┼────────────┘    │
│          │           │           │           │           │                   │
│  ┌───────┴───────────┴───────────┴───────────┴───────────┴────────────┐    │
│  │                     Business Services Layer                        │    │
│  │                                                                     │    │
│  │  StorageService    EmbedService     FraudService    BillingService  │    │
│  │  LinkService       ViewTokenService AdLifecycleService             │    │
│  │  AuthService       CurrentUserService PaymentRateLimiter           │    │
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
   │ ad_links    │       └──────────────┘
   │ payments    │
   │ stripe_events│
   │ refresh_tokens│
   │ password_reset│
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
    │  │  6a. moveToLive() → status = PROCESSING                    │
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

## Data Flow 3: Ad Serving (Viewer → Widget → API → S3)

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
    │  fetches ad:         │                   │                     │        │
    │  GET /api/ads/{id}?  │                   │                     │        │
    │  locale=en&vt=xxx    │                   │                     │        │
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
    │                      │                   │     BillingService  │        │
    │                      │                   │     → View entity   │        │
    │                      │                   │     → campaign.spent│        │
    │                      │                   ├──────────────────> DB       │
    │                      │                   │                     │        │
    │                      │                   │  9. Resolve locale  │        │
    │                      │                   │     best AdVersion  │        │
    │                      │                   ├──────────────────> DB       │
    │                      │                   │                     │        │
    │                      │                   │  10. Presign GET    │        │
    │                      │                   │      URLs for each  │        │
    │                      │                   │      variant        │        │
    │                      │                   ├─────────────────────>│       │
    │                      │                   │<── presigned URLs ──┤       │
    │                      │                   │                     │        │
    │  {adId, variants:    │                   │                     │        │
    │   [presignedUrl...]} │                   │                     │        │
    │<─────────────────────────────────────────┤                     │        │
    │                      │                   │                     │        │
    │  11. Browser loads   │                   │                     │        │
    │  video from S3       │                   │                     │        │
    │  presigned URL       │                   │                     │        │
    ├────────────────────────────────────────────────────────────────>│       │
    │<── video bytes ────────────────────────────────────────────────┤       │
```

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
  │                        │  3. moveToLive()       │
  │                        │     status=PROCESSING  │
  │                        │  ─ ─ SSE: PROCESSING ─>│
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
```

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
```

## Billing Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    Per-View Billing Flow                         │
│                                                                 │
│  GET /api/ads/{id} → BillingService.recordView()                │
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
│  ┌──────────┐  ┌──────────┐                                     │
│  │ Create   │  │ Skip     │                                     │
│  │ View row │  │ (silent  │                                     │
│  │ Update   │  │  budget  │                                     │
│  │ campaign │  │  guard)  │                                     │
│  │ .spent   │  │          │                                     │
│  └──────────┘  └──────────┘                                     │
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

### Ad Serving & Management
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | /api/ads/{id} | **PUBLIC** | Serve ad (fraud + billing) |
| GET | /api/ads/{id}/link | ADVERTISER/ADMIN | Get embed URL/snippet |
| GET | /api/ads/{id}/validation | ADVERTISER/ADMIN | Poll processing status |
| GET | /api/ads/{id}/events | ADVERTISER/ADMIN | SSE status stream |
| POST | /api/ads/{id}/features | ADVERTISER/ADMIN | Select locales, trigger processing |
| PATCH | /api/ads/{id}/review | **ADMIN** | Approve/reject flagged ad |

### Other Public
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | /embed/{token} | **PUBLIC** | Serve embed widget HTML |
| GET | /api/links/{adId} | PUBLISHER/ADVERTISER | Redis-cached variant lookup |
| GET | /api/analytics/advertiser | ADVERTISER | Cross-campaign dashboard |
| POST | /api/payments/webhook | **PUBLIC** (Stripe sig) | Stripe webhook |
| GET | /actuator/health | **PUBLIC** | Health check |
