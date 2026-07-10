package me.psikuvit.betterads.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ProcessingQueueService {
    private final RabbitTemplate rabbitTemplate;

    public ProcessingQueueService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void enqueueProcessingJob(String adId) {
        log.info("Enqueuing adId={} onto queue={}", adId, RabbitConfig.PROCESSING_QUEUE);
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.PROCESSING_QUEUE, adId);
            log.debug("Enqueued adId={} successfully", adId);
        } catch (Exception e) {
            log.error("Failed to enqueue adId={} onto queue={}: {}", adId, RabbitConfig.PROCESSING_QUEUE, e.getMessage(), e);
            throw e;
        }
    }
}
