package me.psikuvit.betterads.queue;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    public static final String PROCESSING_QUEUE = "ad-processing";

    @Bean
    public Queue processingQueue() {
        return new Queue(PROCESSING_QUEUE, true);
    }
}
