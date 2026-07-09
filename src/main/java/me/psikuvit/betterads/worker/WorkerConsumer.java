package me.psikuvit.betterads.worker;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class WorkerConsumer {

    @RabbitListener(queues = "ad-processing")
    public void handle(String adId) {
        // TODO: lookup ad by id, run validation and feature processing, update DB
        System.out.println("Received job for adId=" + adId);
    }
}
