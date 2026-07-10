package me.psikuvit.betterads.billing.payment;

import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.auth.CurrentUserService;
import me.psikuvit.betterads.storage.entities.Campaign;
import me.psikuvit.betterads.storage.entities.Payment;
import me.psikuvit.betterads.storage.entities.User;
import me.psikuvit.betterads.storage.repositories.CampaignRepository;
import me.psikuvit.betterads.storage.repositories.PaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
@Slf4j
public class PaymentController {

    private final CampaignRepository campaignRepository;
    private final PaymentRepository paymentRepository;
    private final StripePaymentService stripePaymentService;
    private final CurrentUserService currentUserService;

    public PaymentController(CampaignRepository campaignRepository, PaymentRepository paymentRepository,
                             StripePaymentService stripePaymentService, CurrentUserService currentUserService) {
        this.campaignRepository = campaignRepository;
        this.paymentRepository = paymentRepository;
        this.stripePaymentService = stripePaymentService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/campaigns/{id}/fund")
    @PreAuthorize("hasRole('ADVERTISER')")
    public ResponseEntity<?> fund(@PathVariable Long id, @RequestBody Map<String, Object> body, Authentication auth) {
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

        Object amountRaw = body.get("amount");
        if (amountRaw == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "amount is required"));
        }
        BigDecimal amount = new BigDecimal(amountRaw.toString());
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "amount must be positive"));
        }

        try {
            PaymentIntent intent = stripePaymentService.createPaymentIntent(amount, "usd", campaign.getId());

            Payment payment = new Payment();
            payment.setCampaignId(campaign.getId());
            payment.setAdvertiserId(user.getId());
            payment.setStripePaymentIntentId(intent.getId());
            payment.setAmount(amount);
            payment.setCurrency("usd");
            payment.setStatus("pending");
            paymentRepository.save(payment);

            log.info("Created PaymentIntent {} for campaign {} amount {}", intent.getId(), campaign.getId(), amount);
            return ResponseEntity.ok(Map.of("clientSecret", intent.getClientSecret(), "paymentIntentId", intent.getId()));
        } catch (Exception e) {
            log.error("Failed to create Stripe PaymentIntent for campaign {}: {}", campaign.getId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Payment provider error: " + e.getMessage()));
        }
    }

    @PostMapping("/payments/webhook")
    public ResponseEntity<?> webhook(@RequestBody String payload,
                                     @RequestHeader("Stripe-Signature") String signature) {
        Event event;
        try {
            event = stripePaymentService.constructWebhookEvent(payload, signature);
        } catch (Exception e) {
            log.warn("Rejected Stripe webhook: invalid signature: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Invalid signature"));
        }

        if ("payment_intent.succeeded".equals(event.getType())) {
            EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
            deserializer.getObject().ifPresent(obj -> {
                if (obj instanceof PaymentIntent intent) {
                    paymentRepository.findByStripePaymentIntentId(intent.getId()).ifPresent(payment -> {
                        if (!"succeeded".equals(payment.getStatus())) {
                            payment.setStatus("succeeded");
                            paymentRepository.save(payment);

                            campaignRepository.findById(payment.getCampaignId()).ifPresent(campaign -> {
                                campaign.setBudget(campaign.getBudget().add(payment.getAmount()));
                                campaignRepository.save(campaign);
                                log.info("Campaign {} budget increased by {} after payment {}",
                                        campaign.getId(), payment.getAmount(), intent.getId());
                            });
                        }
                    });
                }
            });
        }

        return ResponseEntity.ok(Map.of("received", true));
    }
}
