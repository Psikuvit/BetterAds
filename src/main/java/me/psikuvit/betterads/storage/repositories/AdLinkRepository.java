package me.psikuvit.betterads.storage.repositories;

import me.psikuvit.betterads.storage.entities.AdLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * @deprecated Backs the legacy {@code embed/} path only ({@link AdLink}).
 */
@Deprecated
public interface AdLinkRepository extends JpaRepository<AdLink, Long> {
    Optional<AdLink> findByToken(String token);
    Optional<AdLink> findByAdId(Long adId);
    void deleteByAdId(Long adId);
}
