package me.psikuvit.betterads.storage.repositories;

import me.psikuvit.betterads.storage.entities.Campaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {
    List<Campaign> findByAdvertiserId(Long advertiserId);
    Page<Campaign> findByAdvertiserId(Long advertiserId, Pageable pageable);
}
