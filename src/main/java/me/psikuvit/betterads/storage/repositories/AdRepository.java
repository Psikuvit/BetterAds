package me.psikuvit.betterads.storage.repositories;

import me.psikuvit.betterads.storage.entities.Ad;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdRepository extends JpaRepository<Ad, Long> {
}
