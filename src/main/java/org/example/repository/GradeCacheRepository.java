package org.example.repository;

import org.example.model.GradeCache;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

public interface GradeCacheRepository extends JpaRepository<GradeCache, Long> {
    Optional<GradeCache> findByPlayDateAndQuestionNumberAndAnswerKey(LocalDate playDate, int questionNumber, String answerKey);
}
