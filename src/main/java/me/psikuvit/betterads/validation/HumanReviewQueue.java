package me.psikuvit.betterads.validation;

import org.springframework.stereotype.Service;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class HumanReviewQueue {
    private final Queue<String> queue = new ConcurrentLinkedQueue<>();

    public void enqueue(String adId) {
        queue.add(adId);
        System.out.println("Human review enqueued for adId=" + adId);
    }

    public String poll() {
        return queue.poll();
    }
}
