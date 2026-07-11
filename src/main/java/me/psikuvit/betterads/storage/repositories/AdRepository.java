package me.psikuvit.betterads.storage.repositories;

import me.psikuvit.betterads.storage.dto.AdStatus;
import me.psikuvit.betterads.storage.entities.Ad;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdRepository extends JpaRepository<Ad, Long> {
    List<Ad> findByCampaignId(Long campaignId);
    Page<Ad> findByCampaignId(Long campaignId, Pageable pageable);
    List<Ad> findByCampaignIdAndStatus(Long campaignId, AdStatus status);
}
