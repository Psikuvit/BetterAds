package me.psikuvit.betterads.storage.repositories;

import me.psikuvit.betterads.storage.entities.AdLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdLinkRepository extends JpaRepository<AdLink, Long> {
    Optional<AdLink> findByToken(String token);
    Optional<AdLink> findByAdId(Long adId);
}
