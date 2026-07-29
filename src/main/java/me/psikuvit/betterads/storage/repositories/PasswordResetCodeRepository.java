package me.psikuvit.betterads.storage.repositories;

import me.psikuvit.betterads.storage.entities.PasswordResetCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, Long> {
    Optional<PasswordResetCode> findByCodeHash(String codeHash);

    List<PasswordResetCode> findAllByUserIdAndUsedAtIsNullAndInvalidatedAtIsNull(Long userId);
}
