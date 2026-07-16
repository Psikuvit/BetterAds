package me.psikuvit.betterads.storage.repositories;

import me.psikuvit.betterads.storage.entities.AdSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AdSessionRepository extends JpaRepository<AdSession, Long> {
    Optional<AdSession> findBySessionToken(String sessionToken);

    // Locked for the rest of the transaction: serializes concurrent event
    // submissions for the same session so state-machine transitions can't race.
    @Query(value = "SELECT * FROM ad_sessions WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<AdSession> findByIdForUpdate(@Param("id") Long id);
}
