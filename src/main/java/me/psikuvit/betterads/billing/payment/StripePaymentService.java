package me.psikuvit.betterads.billing.payment;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
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

    public PaymentIntent createPaymentIntent(BigDecimal amount, String currency, Long campaignId) throws StripeException {
        long amountInMinorUnits = amount.movePointRight(2).longValueExact();
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountInMinorUnits)
                .setCurrency(currency)
                .putMetadata("campaignId", campaignId.toString())
                .build();
        return PaymentIntent.create(params);
    }

    public Event constructWebhookEvent(String payload, String signatureHeader) throws SignatureVerificationException {
        return Webhook.constructEvent(payload, signatureHeader, webhookSecret);
    }
}
