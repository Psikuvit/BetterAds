package me.psikuvit.betterads.storage.repositories;

import me.psikuvit.betterads.storage.entities.Site;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SiteRepository extends JpaRepository<Site, Long> {
    Optional<Site> findBySiteKey(String siteKey);
}
