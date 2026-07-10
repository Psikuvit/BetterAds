package me.psikuvit.betterads.billing.payment;

import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.auth.CurrentUserService;
import me.psikuvit.betterads.billing.payment.dto.FundCampaignRequest;
import me.psikuvit.betterads.fraud.PaymentRateLimiter;
import me.psikuvit.betterads.storage.dto.PaymentStatus;
import me.psikuvit.betterads.storage.entities.Campaign;
import me.psikuvit.betterads.storage.entities.Payment;
import me.psikuvit.betterads.storage.entities.User;
import me.psikuvit.betterads.storage.repositories.CampaignRepository;
import me.psikuvit.betterads.storage.repositories.PaymentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@Slf4j
public class PaymentController {

    private final CampaignRepository campaignRepository;
    private final PaymentRepository paymentRepository;
    private final StripePaymentService stripePaymentService;
    private final StripeEventDeduplicationService stripeEventDeduplicationService;
    private final CurrentUserService currentUserService;
    private final PaymentRateLimiter paymentRateLimiter;
    private final BigDecimal maxFundingAmount;

    public PaymentController(CampaignRepository campaignRepository, PaymentRepository paymentRepository,
                             StripePaymentService stripePaymentService,
                             StripeEventDeduplicationService stripeEventDeduplicationService,
                             CurrentUserService currentUserService,
                             PaymentRateLimiter paymentRateLimiter,
                             @Value("${app.stripe.max-funding-amount:100000}") BigDecimal maxFundingAmount) {
        this.campaignRepository = campaignRepository;
        this.paymentRepository = paymentRepository;
        this.stripePaymentService = stripePaymentService;
        this.stripeEventDeduplicationService = stripeEventDeduplicationService;
        this.currentUserService = currentUserService;
        this.paymentRateLimiter = paymentRateLimiter;
        this.maxFundingAmount = maxFundingAmount;
    }

    @PostMapping("/campaigns/{id}/fund")
    @PreAuthorize("hasRole('ADVERTISER')")
    public ResponseEntity<?> fund(@PathVariable Long id, @Valid @RequestBody FundCampaignRequest request, Authentication auth) {
        Optional<Campaign> campaignOpt = campaignRepository.findById(id);
        if (campaignOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Campaign campaign = campaignOpt.get();
        User user = currentUserService.resolve(auth);
        if (!currentUserService.isAdmin(auth) &&
                (campaign.getAdvertiserId() == null || !campaign.getAdvertiserId().equals(user.getId()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "You do not have access to this campaign"));
        }

        if (request.amount().compareTo(maxFundingAmount) > 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "amount exceeds the maximum funding amount of " + maxFundingAmount));
        }

        if (!paymentRateLimiter.isFundingAllowed(user.getId())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many funding attempts. Please try again later."));
        }

        String idempotencyKey = (request.idempotencyKey() != null && !request.idempotencyKey().isBlank())
                ? request.idempotencyKey()
                : UUID.randomUUID().toString();

        Optional<Payment> existing = paymentRepository.findByClientIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return reuseExistingPayment(existing.get());
        }

        try {
            PaymentIntent intent = stripePaymentService.createPaymentIntent(request.amount(), "usd", campaign.getId(), idempotencyKey);

            Payment payment = new Payment();
            payment.setCampaignId(campaign.getId());
            payment.setAdvertiserId(user.getId());
            payment.setStripePaymentIntentId(intent.getId());
            payment.setClientIdempotencyKey(idempotencyKey);
            payment.setAmount(request.amount());
            payment.setCurrency("usd");
            payment.setStatus(PaymentStatus.PENDING);
            paymentRepository.save(payment);

            log.info("Created PaymentIntent {} for campaign {} amount {}", intent.getId(), campaign.getId(), request.amount());
            return ResponseEntity.ok(Map.of("clientSecret", intent.getClientSecret(), "paymentIntentId", intent.getId()));
        } catch (DataIntegrityViolationException raceLoss) {
            // Lost a race against a concurrent request using the same idempotencyKey.
            return paymentRepository.findByClientIdempotencyKey(idempotencyKey)
                    .map(this::reuseExistingPayment)
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(Map.of("error", "A funding request with this idempotency key is already in progress")));
        } catch (Exception e) {
            log.error("Failed to create Stripe PaymentIntent for campaign {}: {}", campaign.getId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Payment provider error: " + e.getMessage()));
        }
    }

    private ResponseEntity<?> reuseExistingPayment(Payment payment) {
        try {
            PaymentIntent intent = stripePaymentService.retrievePaymentIntent(payment.getStripePaymentIntentId());
            log.info("Reused existing PaymentIntent {} for idempotencyKey {}", intent.getId(), payment.getClientIdempotencyKey());
            return ResponseEntity.ok(Map.of("clientSecret", intent.getClientSecret(), "paymentIntentId", intent.getId()));
        } catch (Exception e) {
            log.error("Failed to retrieve existing PaymentIntent {}: {}", payment.getStripePaymentIntentId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Payment provider error: " + e.getMessage()));
        }
    }

    @PostMapping("/payments/webhook")
    @Transactional
    public ResponseEntity<?> webhook(@RequestBody String payload,
                                     @RequestHeader("Stripe-Signature") String signature) {
        Event event;
        try {
            event = stripePaymentService.constructWebhookEvent(payload, signature);
        } catch (Exception e) {
            log.warn("Rejected Stripe webhook: invalid signature: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Invalid signature"));
        }

        if (!stripeEventDeduplicationService.markProcessedIfNew(event.getId(), event.getType())) {
            return ResponseEntity.ok(Map.of("received", true, "duplicate", true));
        }

        if ("payment_intent.succeeded".equals(event.getType())) {
            handleSucceeded(event);
        } else if ("payment_intent.payment_failed".equals(event.getType())) {
            handleFailed(event);
        }

        return ResponseEntity.ok(Map.of("received", true));
    }

    private void handleSucceeded(Event event) {
        withPaymentIntent(event, intent ->
                paymentRepository.findByStripePaymentIntentId(intent.getId()).ifPresent(payment -> {
                    if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
                        return;
                    }
                    String expectedCampaignId = intent.getMetadata() != null ? intent.getMetadata().get("campaignId") : null;
                    if (expectedCampaignId != null && !expectedCampaignId.equals(payment.getCampaignId().toString())) {
                        log.warn("PaymentIntent {} metadata campaignId={} does not match stored payment campaignId={} — skipping credit",
                                intent.getId(), expectedCampaignId, payment.getCampaignId());
                        return;
                    }

                    payment.setStatus(PaymentStatus.SUCCEEDED);
                    paymentRepository.save(payment);

                    // Locked read: serializes against concurrent ad-view spend
                    // (BillingService.recordView) and other budget edits on the
                    // same campaign row.
                    campaignRepository.findByIdForUpdate(payment.getCampaignId()).ifPresent(campaign -> {
                        campaign.setBudget(campaign.getBudget().add(payment.getAmount()));
                        campaignRepository.save(campaign);
                        log.info("Campaign {} budget increased by {} after payment {}",
                                campaign.getId(), payment.getAmount(), intent.getId());
                    });
                }));
    }

    private void handleFailed(Event event) {
        withPaymentIntent(event, intent ->
                paymentRepository.findByStripePaymentIntentId(intent.getId()).ifPresent(payment -> {
                    if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
                        payment.setStatus(PaymentStatus.FAILED);
                        paymentRepository.save(payment);
                        log.info("Payment {} marked failed", intent.getId());
                    }
                }));
    }

    private void withPaymentIntent(Event event, java.util.function.Consumer<PaymentIntent> consumer) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        deserializer.getObject().ifPresent(obj -> {
            if (obj instanceof PaymentIntent intent) {
                consumer.accept(intent);
            }
        });
    }
}
