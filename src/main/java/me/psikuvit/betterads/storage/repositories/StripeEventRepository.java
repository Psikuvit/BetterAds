package me.psikuvit.betterads.storage.repositories;

import me.psikuvit.betterads.storage.entities.StripeEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StripeEventRepository extends JpaRepository<StripeEvent, Long> {
}
