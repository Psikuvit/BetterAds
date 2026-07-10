package me.psikuvit.betterads.billing.payment;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@Slf4j
public class StripePaymentService {

    private final String webhookSecret;

    public StripePaymentService(@Value("${app.stripe.secret-key:}") String secretKey,
                                @Value("${app.stripe.webhook-secret:}") String webhookSecret) {
        this.webhookSecret = webhookSecret;
        if (secretKey == null || secretKey.isBlank()) {
            log.warn("app.stripe.secret-key is not configured — campaign funding will fail until it is set");
        } else {
            Stripe.apiKey = secretKey;
        }
    }

    /**
     * idempotencyKey is passed through to Stripe's own idempotency mechanism:
     * a retried request with the same key within Stripe's 24h window returns
     * the original PaymentIntent instead of creating a second one, even if
     * our own dedup-by-key check (PaymentController) somehow gets bypassed.
     */
    public PaymentIntent createPaymentIntent(BigDecimal amount, String currency, Long campaignId, String idempotencyKey) throws StripeException {
        long amountInMinorUnits = amount.movePointRight(2).longValueExact();
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountInMinorUnits)
                .setCurrency(currency)
                .putMetadata("campaignId", campaignId.toString())
                .build();
        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey(idempotencyKey)
                .build();
        return PaymentIntent.create(params, options);
    }

    public PaymentIntent retrievePaymentIntent(String id) throws StripeException {
        return PaymentIntent.retrieve(id);
    }

    public Event constructWebhookEvent(String payload, String signatureHeader) throws SignatureVerificationException {
        return Webhook.constructEvent(payload, signatureHeader, webhookSecret);
    }
}
