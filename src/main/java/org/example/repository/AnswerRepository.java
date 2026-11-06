package org.example.repository;

import org.example.model.Answer;
import org.example.model.PlayerSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AnswerRepository extends JpaRepository<Answer, Long> {
    List<Answer> findBySessionOrderByQuestionNumber(PlayerSession session);
}
