package me.psikuvit.betterads.storage.repo;

import me.psikuvit.betterads.storage.entities.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {
}
