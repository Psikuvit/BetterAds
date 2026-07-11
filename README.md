# BetterAds

BetterAds is a video advertising platform. Advertisers upload video ads, which
are moderated and processed asynchronously (via a RabbitMQ-backed worker
pipeline), then served to end users through generated embed links. Views are
tracked for fraud detection and billing, with Stripe handling payment
processing.

This document describes the architecture and how the pieces integrate.

## Architecture at a Glance

BetterAds is a **modular monolith**: one Spring Boot codebase, organized into
packages by responsibility, with an async worker path for the processing
pipeline. Everything — the web API and the ad-processing RabbitMQ consumer —
runs inside a single entrypoint, `BetterAdsApplication`.

```
┌─────────────────────────────────────────────────────────────────────┐
│                        BetterAds (Spring Boot)                     │
│                                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│  │   Auth   │  │  Upload  │  │ Campaign │  │   Ad     │          │
│  │  Module  │  │  Module  │  │  Module  │  │  Module  │          │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘          │
│       │              │              │              │                │
│  ┌────┴──────────────┴──────────────┴──────────────┴────┐          │
│  │              Business Services Layer                  │          │
│  │  Storage · Fraud · Billing · Embed · Links · AI       │          │
│  └────────────────────┬─────────────────────────────────┘          │
│                       │                                             │
│  ┌────────────────────┴─────────────────────────────────┐          │
│  │              Async Processing (RabbitMQ)              │          │
│  │  WorkerConsumer → Validation → FeatureProcessing      │          │
│  └────────────────────┬─────────────────────────────────┘          │
│                       │                                             │
└───────────────────────┼─────────────────────────────────────────────┘
                        │
        ┌───────────────┼───────────────┐
        │               │               │
   ┌────┴────┐   ┌──────┴──────┐  ┌────┴────┐
   │  MySQL  │   │    Redis    │  │   S3    │
   │ (TiDB)  │   │ (Upstash)  │  │  (AWS)  │
   └─────────┘   └─────────────┘  └─────────┘
```

### Technology Stack

| Layer | Technology |
|-------|-----------|
| **Language** | Java 21 |
| **Framework** | Spring Boot 4.1.0 |
| **Build Tool** | Apache Maven 3.9.16 (Maven Wrapper) |
| **ORM** | Spring Data JPA / Hibernate (ddl-auto=none, Flyway-managed schema) |
| **Database** | MySQL 8.0 (dev: Docker; prod: TiDB Cloud on AWS) |
| **Migrations** | Flyway (9 migrations: V1 through V9) |
| **Message Queue** | RabbitMQ 3 (management Alpine) |
| **Cache / Fraud State** | Redis 7 (prod: Upstash with TLS) |
| **Object Storage** | AWS S3 (SDK v1 `1.12.548`) |
| **Authentication** | Spring Security + JWT (jjwt `0.12.3`, HS256, 15min access tokens) |
| **Payments** | Stripe Java SDK `29.1.0` (PaymentIntent + webhook) |
| **AI Integration** | Pluggable: `mock` (local), `local` (WebClient to localhost:9000), `huggingface` (Gradio HTTP API) |
| **Reactive HTTP** | Spring WebFlux / WebClient (for AI provider calls) |
| **Rate Limiting** | Bucket4j `7.6.0` (token bucket, per-user/IP) |
| **Code Generation** | Lombok (getters/setters/logs) |
| **Validation** | Jakarta Bean Validation (`spring-boot-starter-validation`) |
| **Monitoring** | Spring Boot Actuator (health, ping) |
| **Containerization** | Multi-stage Dockerfile (Maven build → JRE 22 runtime), docker-compose (4 services) |

## The Core Pipeline

1. **Presign.** An advertiser calls `POST /api/upload/presign` with a storage
   key (namespaced under `ads/{advertiserId}/...`) and a content type from an
   allowed video MIME list. `StorageService` returns an S3 presigned PUT URL
   with the content type bound into the signature, so the client's upload
   must match what was requested.

2. **Client uploads directly to S3.** The client uses the presigned URL to PUT
   the video bytes directly to S3, bypassing the server for the heavy transfer.

3. **Confirm.** `POST /api/upload/confirm` looks up the target campaign,
   verifies the caller owns it, verifies the uploaded object actually exists
   in S3 (size and content type checked against policy), then creates an
   `Ad` row with status `pending` and enqueues the ad ID onto the
   `ad-processing` RabbitMQ queue via `ProcessingQueueService`.

4. **Worker consumes the job.** `WorkerConsumer` (a `@RabbitListener` on
   `ad-processing`) drives the ad through its lifecycle:
   - `pending → validating`: calls `ValidationService`, which delegates to
     a `ModerationService` implementation and gets back `APPROVED`,
     `FLAGGED`, or `REJECTED`.
   - On `APPROVED`, the ad reaches `AWAITING_FEATURES`. The advertiser can
     select French translation (triggers async `moveToLive()` via
     `CompletableFuture.runAsync()` → returns 202 immediately) or skip
     translation (ad goes straight to `LIVE` with the original video).
     SSE events notify the frontend of status changes in real-time.
   - On `FLAGGED`, status becomes `flagged` and the ad waits for a human
     decision.
   - On `REJECTED`, `AdLifecycleService.reject()` sets status `rejected`.
   - Any exception anywhere in this flow sets status `failed`.
   - Every transition is published as an SSE event via `AdStatusEventPublisher`.

5. **Human review.** A `flagged` ad has no automatic path forward. An admin
   calls `PATCH /api/ads/{id}/review` with `approve` or `reject`; approve
   routes through the same `AdLifecycleService.moveToLive()` used by the
   automatic path. Admins can also reject ads in any pre-live status.

6. **Serving.** `GET /embed/{token}` (public) resolves the token to an ad ID
   and returns a small HTML page with two `<video>` elements (for seamless
   swapping) and a script that fetches `GET /api/ads/{id}/playlist?locale=&vt=`.
   That endpoint returns all LIVE ads in the campaign with presigned S3 URLs,
   and the widget cycles through them endlessly. The widget reports video
   dimensions to the parent iframe via `postMessage` for dynamic sizing.
   The embed is served at campaign level (`GET /api/campaigns/{id}/embed`).
   The individual ad detail page (`/ads/[id]`) has been removed — all ad
   management happens at the campaign level.

## Module Responsibilities

- **`auth/`** — Login, registration, `/me`, refresh/logout/password-reset.
  `CurrentUserService` resolves the authenticated `User` entity from the JWT
  subject (a user's email) via a DB lookup; this is the one place ownership
  checks originate from.

- **`security/`** — `JwtTokenProvider` (issues/parses access tokens),
  `JwtAuthenticationFilter` (populates the Spring Security context from the
  bearer token), `SecurityConfig` (route authorization rules, CORS,
  password encoder), `TokenHasher` (SHA-256 hashing for opaque refresh/reset
  tokens), `SecurityHeadersFilter` (X-Content-Type-Options, Referrer-Policy,
  X-Frame-Options), `ClientIpResolver` (extracts real client IP behind
  proxies).

- **`storage/`** — S3 presigning, object metadata, upload, and deletion
  (`StorageService`), all JPA entities and repositories, upload policy
  constants, and `AdCleanupService` (full ad deletion: S3 files + DB cascade).

- **`api/`** — The main REST surface: campaigns, ads, uploads, validation/review,
  analytics.

- **`queue/`** — RabbitMQ queue definition (`RabbitConfig`) and the producer
  side (`ProcessingQueueService`).

- **`worker/`** — The consumer side: `WorkerConsumer`, `AdLifecycleService`
  (shared status-transition logic), `AdStatusEventPublisher` (SSE fan-out).

- **`validation/`** — `ValidationService`, which wraps the pluggable
  `ModerationService`.

- **`ai/`** — Interfaces for moderation, translation, and speech evaluation,
  each with `mock`, `local`, and `huggingface` implementations.

- **`features/`** — `FeatureProcessingService`, which orchestrates translation
  and speech evaluation into a persisted `AdVersion`.

- **`embed/`** — Public widget serving (`EmbedController`/`EmbedService`):
  generates and resolves embed tokens, renders the widget HTML (two-video
  swap for seamless transitions, `postMessage` for dynamic sizing), and
  issues a signed view token into that HTML on every render. Campaign
  embed endpoint (`GET /api/campaigns/{id}/embed`) returns the embed
  URL for the campaign's first LIVE ad.

- **`fraud/`** — `FraudService` (Redis-backed sliding-window IP rate check),
  `ViewTokenService` (signed, one-time-use tokens tying a view back to a
  genuine widget load), `PaymentRateLimiter` (Redis-backed, guards
  against card-testing abuse).

- **`billing/`** — `BillingService` (per-view cost calculation and budget
  bookkeeping) and `billing/payment/` (Stripe integration: creating
  `PaymentIntent`s, verifying webhook signatures, reconciling budget on
  successful payment, deduplicating webhook events).

- **`links/`** — `LinkService`, a Redis-cached read path for resolving an ad's
  locale variants, used by the `PUBLISHER`-facing `/api/links/{adId}`
  endpoint.

- **`config/`** — CORS properties, rate-limit properties/service/filter.

- **`common/exceptions/`** — The single global exception handler and the
  shared `ErrorResponse` shape.

## Authentication and Authorization

JWTs are issued on login/register and carry the user's email as the subject
and their role as a claim. Access tokens are short-lived (15 minutes) by
design, paired with a longer-lived opaque refresh token (7 days). Refresh
tokens are single-use: each call to `/auth/refresh` revokes the presented
token and issues a new access/refresh pair. Both refresh tokens and
password-reset tokens are stored only as a SHA-256 hash — never in
plaintext — and password-reset requests always return the same response
whether or not the email exists, to avoid leaking which emails are
registered.

**Three roles exist:** `ADVERTISER`, `PUBLISHER`, `ADMIN`.

**Two security filter chains:**

1. **Order(1):** `/embed/**` — permits all, disables frame options (allows
   iframe embedding).
2. **Order(2):** Everything else — CORS, stateless sessions, JWT filter,
   role-based authorization.

**Route-level access** is enforced two ways: `SecurityConfig` permits a small
public surface (`/auth/**`, `/embed/**`, `GET /api/ads/{id}`,
`GET /api/ads/{id}/playlist`, the Stripe webhook), and everything else
requires authentication plus a method-level `@PreAuthorize` role check.
Resource-level ownership (an advertiser can only see/edit their own campaigns)
is enforced in the controllers themselves via `CurrentUserService`, not by
Spring Security. Both admins and advertisers can delete ads via
`DELETE /api/ads/{id}` — advertisers can only delete ads in their own
campaigns, admins can delete any ad.

### Filter Chain Order

```
Request
  → RateLimitFilter (Bucket4j, before auth)
  → SecurityHeadersFilter (X-Content-Type-Options, Referrer-Policy, X-Frame-Options)
  → JwtAuthenticationFilter (extracts Bearer token, populates SecurityContext)
  → Spring Security authorization checks
```

## Data Model

Entities are flat — foreign keys are plain `Long` columns, not JPA
relationships — with the actual referential integrity enforced at the
database level via Flyway-managed schema:

### Tables

| Table | Purpose |
|-------|---------|
| **users** | User accounts (id, email, password_hash, role, created_at) |
| **campaigns** | Ad campaigns (id, advertiser_id, name, budget, spent, status, created_at) |
| **ads** | Individual ads (id, campaign_id, title, storage_key, status, target_locale, created_at) |
| **ad_versions** | Locale-specific renditions (id, ad_id, locale, storage_key, feature, created_at) |
| **views** | Recorded ad impressions (id, ad_version_id, viewed_at, viewer_ip, device_info) |
| **ad_links** | Embed tokens for live ads (id, ad_id, token, created_at) |
| **refresh_tokens** | Single-use refresh tokens (id, user_id, token_hash, expires_at, revoked_at, created_at) |
| **password_reset_tokens** | Password reset tokens (id, user_id, token_hash, expires_at, used_at, created_at) |
| **payments** | Stripe PaymentIntents (id, campaign_id, advertiser_id, stripe_payment_intent_id, client_idempotency_key, amount, currency, status, created_at) |
| **stripe_events** | Webhook dedup (id, stripe_event_id, event_type, processed_at) |

### Entity Relationships

```
User 1───* Campaign 1───* Ad 1───* AdVersion 1───* View
                         Ad 1───1 AdLink
User 1───* RefreshToken
User 1───* PasswordResetToken
Campaign 1───* Payment
User 1───* Payment
```

## Real-Time Status (SSE)

Rather than polling, a client can open `GET /api/ads/{id}/events`, which
returns a Server-Sent Events stream. `AdStatusEventPublisher` keeps an
in-process map of ad ID to open SSE emitters; every status transition in the
worker and review paths calls `publish(adId, status)`, which pushes an event
to any open subscribers for that ad and drops dead connections. A background
heartbeat sends `:ping` comments every 15 seconds to prevent proxy timeouts.
This is in-process state, not distributed — a subscriber is only notified by
the instance it connected to, which is consistent with this being a
single-node deployment today.

## Fraud Detection and View Tokens

Three layers work together on the view-recording path (`GET /api/ads/{id}`):

1. **IP Sliding Window.** `FraudService` keeps a Redis sorted set per IP,
   scored by request timestamp, pruning anything outside a 60-second window.
   More than 30 requests in that window from one IP is treated as fraud.
   This is distributed (Redis-backed, not per-instance memory) and survives
   restarts.

2. **Campaign Velocity Cap.** `FraudService` also tracks a Redis counter per
   campaign — max 200 views per campaign per 60 seconds — catching
   botnet/proxy rotation attacks that spread across IPs.

3. **Signed View Tokens.** Every time `EmbedService` renders the widget HTML
   for `GET /embed/{token}`, it also issues a short-lived (2 minute),
   HMAC-signed, single-use token via `ViewTokenService` and embeds it into
   the widget's own fetch call. When `GET /api/ads/{id}` receives a valid
   token bound to that ad ID, it trusts the request as coming from a genuine
   widget load and skips the IP check; the token's nonce is marked used in
   Redis so it cannot be replayed. Requests without a valid token fall back
   to the IP check.

**Payment Rate Limiting:** `PaymentRateLimiter` enforces a Redis-backed
counter of max 5 funding attempts per hour per advertiser, guarding against
card-testing abuse.

## Billing and Payments

`BillingService.recordView()` is called on every successfully-served ad
view. It looks up the view's `AdVersion` → `Ad` → `Campaign` chain, computes
a per-view cost from a locale-based rate table, and either records the view
and increments `campaign.spent`, or — if that would exceed the campaign's
budget — marks the campaign `COMPLETED` and deletes all ads in the campaign
via `AdCleanupService` (S3 files + DB cascade).

### Locale Rate Table

| Locale | Base Rate (per view) |
|--------|---------------------|
| US | $0.0015 |
| GB | $0.0014 |
| DE | $0.0013 |
| default | $0.001 |

**Feature Surcharges:** `translation` adds +$0.001 per view.

**Budget Enforcement:** `BillingService.recordView()` uses `SELECT FOR UPDATE`
on the campaign row to prevent race conditions. Campaign funding (Stripe)
also uses pessimistic locking when crediting budget.

Budget itself is funded through Stripe: `POST /api/campaigns/{id}/fund`
creates a Stripe `PaymentIntent` for the requested amount and a `pending`
`Payment` row, returning a client secret for the frontend to confirm payment
client-side. `POST /api/payments/webhook` is the other half — a public,
signature-verified endpoint Stripe calls on payment events. On
`payment_intent.succeeded`, it marks the `Payment` row `succeeded` and
increases the campaign's `budget` by the funded amount, guarded by
`StripeEventDeduplicationService` (uses `REQUIRES_NEW` transaction with
unique constraint insertion) so duplicate webhook deliveries cannot
double-apply funds.

### Idempotent Payments

Double-submit protection at three levels:
- Client idempotency key (stored in `payments.client_idempotency_key`)
- Stripe's own idempotency key
- Database unique constraint on `stripe_payment_intent_id`

## Error Handling

A single `GlobalExceptionHandler` (`common/exceptions/`) maps every
exception type used across the codebase to a consistent JSON body:

```json
{
  "error": "message",
  "status": 401,
  "path": "/auth/login",
  "timestamp": "2026-07-11T..."
}
```

Handled exception types: `AuthenticationException` (401),
`UserAlreadyExistsException` (409), `NoSuchElementException` (404),
`AccessDeniedException` (403), `MethodArgumentNotValidException` (400),
`HttpMessageNotReadableException` (400), `IllegalArgumentException` (400),
`AsyncRequestNotUsableException` (silent disconnect), generic `Exception`
(500).

The rate-limit filter runs as a raw Servlet `Filter` ahead of Spring's own
exception-handling machinery and so cannot use `@ExceptionHandler`; it
hand-builds JSON in the same shape instead.

## Rate Limiting and CORS

A `Bucket4j`-backed filter (`config/RateLimitFilter`) applies a per-client
token bucket ahead of authentication, keyed by user identity when
authenticated or by IP/`X-Forwarded-For` otherwise. Exceeding the limit
returns 429 in the same structured error shape as everything else. CORS
policy (allowed origins/methods/headers) is externalized to
`CorsProperties` and applied globally in `SecurityConfig`.

## AI Provider Abstraction

Moderation, translation, and speech evaluation are each defined as an
interface in `ai/`, with implementations selected by the
`app.ai.provider` property:

| Interface | Mock (default) | Local | HuggingFace |
|-----------|----------------|-------|-------------|
| `ModerationService` | Keyword-based (reject/flag/approve by storage key name) | POST /moderate to localhost:9000 | Gradio 3-step: upload → submit → SSE await |
| `TranslationService` | Returns original key unchanged | POST /translate to localhost:9000 | Gradio EN→FR video dubbing only, uses VideoData format (input/output), downloads result to S3; failures propagate (revert to AWAITING_FEATURES) |
| `SpeechEvaluationService` | Hash-based deterministic 0.6–0.99 score | POST /speech/evaluate to localhost:9000 | Fake scoring (no HTTP call, returns random 0.6–0.99) |

This is the seam where a real third-party AI provider would be plugged in
without touching `ValidationService` or `FeatureProcessingService`.

## Configuration Management

All configuration is externalized via:
- `application.properties` (defaults)
- `.env` file (secrets, gitignored)
- Environment variables (override via Spring's relaxed binding)
- `docker-compose.yml` passes all env vars to the `app` container with
  `${VAR:-default}` pattern

Key configuration groups: `spring.datasource.*`, `spring.rabbitmq.*`,
`spring.data.redis.*`, `app.s3.*`, `app.auth.*`, `app.stripe.*`,
`app.fraud.*`, `app.ai.*`, `app.ratelimit.*`, `app.cors.*`,
`app.upload.*`, `app.base-url`.

## Build and Deployment

### Local Development

```bash
docker compose up -d          # Start MySQL, Redis, RabbitMQ
mvn spring-boot:run           # Start the app (reads .env)
```

### Docker Build (multi-stage)

```dockerfile
# Stage 1: Maven build
FROM maven:3.9-eclipse-temurin-22 AS build
COPY pom.xml . && mvn dependency:go-offline
COPY src ./src && mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:22-jre
COPY --from=build /app/target/*.jar app.jar
HEALTHCHECK curl http://localhost:8080/actuator/health/ping
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Docker Compose (4 services)

1. **mysql** — MySQL 8.0, port 3306, persistent volume
2. **redis** — Redis 7 Alpine, port 6379
3. **rabbitmq** — RabbitMQ 3 Management Alpine, ports 5672/15672, persistent volume
4. **app** — Built from Dockerfile, port 8080, depends on all three above with health checks

## Key Design Decisions

1. **Flat entities** — No JPA relationships; foreign keys are plain `Long`
   columns. Referential integrity enforced at DB level via Flyway migrations.
2. **Stateless sessions** — `SessionCreationPolicy.STATELESS`; every request
   must carry the JWT.
3. **Ownership in controllers, not security** — Resource-level access control
   (ownership checks) is done manually in each controller via
   `CurrentUserService`, not via Spring Security's `@PreAuthorize`.
4. **Idempotent payments** — Double-submit protection at three levels: client
   idempotency key, Stripe's own idempotency key, and database unique
   constraint.
5. **Webhook dedup** — `StripeEventDeduplicationService` uses `REQUIRES_NEW`
   transaction with unique constraint insertion — a failed duplicate
   insertion cannot poison the caller's transaction.
6. **SELECT FOR UPDATE** — Campaign rows are pessimistically locked during
   budget mutations (both spending via `BillingService.recordView()` and
   crediting via Stripe webhook) to prevent race conditions.
7. **Budget guard** — If a view would exceed the campaign budget, the campaign
   is marked COMPLETED and `AdCleanupService` deletes all ads in the campaign
   (S3 files + DB cascade: views, ad versions, embed links, ad entities).
8. **Hard deletes** — Ads are fully deleted from S3 and DB via
   `AdCleanupService`. Both admins and advertisers can delete ads
   (advertisers only their own); automatic deletion happens when a
   campaign's budget is exhausted.
9. **SSE is single-instance** — The in-process `ConcurrentHashMap` of SSE
   emitters means subscribers are only notified by the instance they
   connected to.
10. **Async feature processing** — `POST /api/ads/{id}/features` returns
    202 immediately and processes translation in a background thread via
    `CompletableFuture.runAsync()`. This avoids Render's 30-second proxy
    timeout during HuggingFace translation (~18s+).
11. **Campaign-level embed** — The embed is served from the campaign
    dashboard, not individual ad pages. The individual ad detail page
    has been removed — all ad management happens at campaign level.
