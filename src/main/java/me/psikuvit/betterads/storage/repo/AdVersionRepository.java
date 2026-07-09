package me.psikuvit.betterads.storage.repo;

import me.psikuvit.betterads.storage.entities.AdVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdVersionRepository extends JpaRepository<AdVersion, Long> {
    List<AdVersion> findByAdId(Long adId);
}
