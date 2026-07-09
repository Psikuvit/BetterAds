package me.psikuvit.betterads.storage.repo;

import me.psikuvit.betterads.storage.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
