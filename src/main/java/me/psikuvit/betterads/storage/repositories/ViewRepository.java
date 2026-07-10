package me.psikuvit.betterads.storage.repositories;

import me.psikuvit.betterads.storage.entities.View;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ViewRepository extends JpaRepository<View, Long> {

    @Query("SELECT COUNT(v) FROM View v WHERE v.adVersionId IN " +
           "(SELECT av.id FROM AdVersion av WHERE av.adId IN " +
           "(SELECT a.id FROM Ad a WHERE a.campaignId = :campaignId))")
    long countViewsByCampaignId(@Param("campaignId") Long campaignId);
}