package org.example.repository;

import org.example.model.DailyQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface DailyQuestionRepository extends JpaRepository<DailyQuestion, Long> {
    List<DailyQuestion> findByPlayDateOrderByQuestionNumber(LocalDate playDate);
}
