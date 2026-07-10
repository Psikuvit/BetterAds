package me.psikuvit.betterads.billing.payment;

import lombok.extern.slf4j.Slf4j;
import me.psikuvit.betterads.storage.entities.StripeEvent;
import me.psikuvit.betterads.storage.repositories.StripeEventRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class StripeEventDeduplicationService {

    private final StripeEventRepository stripeEventRepository;

    public StripeEventDeduplicationService(StripeEventRepository stripeEventRepository) {
        this.stripeEventRepository = stripeEventRepository;
    }

    /**
     * Records that a Stripe event is being processed. Runs in its own,
     * independent transaction (REQUIRES_NEW) so a caught duplicate-key
     * violation here never poisons the caller's own transaction, which goes
     * on to mutate Payment/Campaign rows using the same Hibernate session.
     * Returns true the first time this event id is seen; false on any
     * replay/redelivery of the same event.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markProcessedIfNew(String stripeEventId, String eventType) {
        try {
            stripeEventRepository.save(new StripeEvent(stripeEventId, eventType));
            return true;
        } catch (DataIntegrityViolationException e) {
            log.info("Stripe event {} already processed, skipping (replay/redelivery)", stripeEventId);
            return false;
        }
    }
}
