package org.example.repository;

import org.example.model.PlayerSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PlayerSessionRepository extends JpaRepository<PlayerSession, Long> {
    Optional<PlayerSession> findByPlayerNameAndPlayDate(String playerName, LocalDate playDate);
    List<PlayerSession> findByPlayDateAndLockedTrueOrderByIdAsc(LocalDate playDate);
}
