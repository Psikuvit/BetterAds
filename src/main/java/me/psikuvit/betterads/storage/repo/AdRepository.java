package me.psikuvit.betterads.storage.repo;

import me.psikuvit.betterads.storage.entities.Ad;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdRepository extends JpaRepository<Ad, Long> {
}
