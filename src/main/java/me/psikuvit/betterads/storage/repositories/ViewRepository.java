package me.psikuvit.betterads.storage.repositories;

import me.psikuvit.betterads.storage.entities.View;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ViewRepository extends JpaRepository<View, Long> {

    @Query("SELECT COUNT(v) FROM View v WHERE v.adVersionId IN " +
           "(SELECT av.id FROM AdVersion av WHERE av.adId IN " +
           "(SELECT a.id FROM Ad a WHERE a.campaignId = :campaignId))")
    long countViewsByCampaignId(@Param("campaignId") Long campaignId);

    @Query(value = "SELECT DATE(v.viewed_at) AS day, COUNT(*) AS views FROM views v " +
            "JOIN ad_versions av ON v.ad_version_id = av.id " +
            "JOIN ads a ON av.ad_id = a.id " +
            "WHERE a.campaign_id = :campaignId AND v.viewed_at >= :since " +
            "GROUP BY DATE(v.viewed_at) ORDER BY day", nativeQuery = true)
    List<Object[]> viewsByDay(@Param("campaignId") Long campaignId, @Param("since") Instant since);

    @Query(value = "SELECT a.id AS ad_id, a.title AS title, COUNT(v.id) AS views FROM ads a " +
            "LEFT JOIN ad_versions av ON av.ad_id = a.id " +
            "LEFT JOIN views v ON v.ad_version_id = av.id " +
            "WHERE a.campaign_id = :campaignId GROUP BY a.id, a.title", nativeQuery = true)
    List<Object[]> viewsByAd(@Param("campaignId") Long campaignId);
}
