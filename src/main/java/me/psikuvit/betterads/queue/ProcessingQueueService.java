package me.psikuvit.betterads.queue;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProcessingQueueService {
    private final RabbitTemplate rabbitTemplate;

    public ProcessingQueueService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void enqueueProcessingJob(String adId) {
        rabbitTemplate.convertAndSend(RabbitConfig.PROCESSING_QUEUE, adId);
    }
}
