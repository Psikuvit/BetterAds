package me.psikuvit.betterads.storage.repositories;

import me.psikuvit.betterads.storage.entities.Campaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {
    List<Campaign> findByAdvertiserId(Long advertiserId);
    Page<Campaign> findByAdvertiserId(Long advertiserId, Pageable pageable);

     @Query(value = "SELECT * FROM campaigns WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Campaign> findByIdForUpdate(@Param("id") Long id);
}
