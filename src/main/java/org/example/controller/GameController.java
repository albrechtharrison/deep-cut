package org.example.controller;

import org.example.model.Answer;
import org.example.model.DailyQuestion;
import org.example.model.PlayerSession;
import org.example.repository.AnswerRepository;
import org.example.repository.PlayerSessionRepository;
import org.example.service.GameDay;
import org.example.service.QuestionService;
import org.example.service.ScoutService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
public class GameController {

    private final QuestionService questionService;
    private final ScoutService scoutService;
    private final PlayerSessionRepository sessionRepo;
    private final AnswerRepository answerRepo;

    public GameController(QuestionService questionService, ScoutService scoutService,
                          PlayerSessionRepository sessionRepo, AnswerRepository answerRepo) {
        this.questionService = questionService;
        this.scoutService = scoutService;
        this.sessionRepo = sessionRepo;
        this.answerRepo = answerRepo;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/join")
    public String join(@RequestParam String playerName, HttpSession httpSession,
                       RedirectAttributes redirectAttrs) {
        String name = playerName.trim();
        if (name.isBlank()) {
            redirectAttrs.addFlashAttribute("error", "Please enter your name.");
            return "redirect:/";
        }
        LocalDate today = GameDay.today();
        Optional<PlayerSession> existing = sessionRepo.findByPlayerNameAndPlayDate(name, today);
        PlayerSession ps = existing.orElseGet(() -> sessionRepo.save(new PlayerSession(name, today)));

        httpSession.setAttribute("sessionId", ps.getId());
        if (ps.isLocked()) {
            return "redirect:/results";
        }
        return "redirect:/play";
    }

    @GetMapping("/play")
    public String play(HttpSession httpSession, Model model) {
        PlayerSession ps = getSession(httpSession);
        if (ps == null) return "redirect:/";
        if (ps.isLocked()) return "redirect:/results";

        List<DailyQuestion> questions = questionService.getTodaysQuestions();
        model.addAttribute("playerName", ps.getPlayerName());
        model.addAttribute("questions", questions);
        return "play";
    }

    @PostMapping("/check-spelling")
    @ResponseBody
    public ResponseEntity<Map<String, String>> checkSpelling(@RequestBody Map<String, String> body) {
        String raw = body.getOrDefault("name", "").trim();
        if (raw.isBlank()) {
            return ResponseEntity.ok(Map.of("suggestion", ""));
        }
        String suggestion = scoutService.checkSpelling(raw);
        return ResponseEntity.ok(Map.of("suggestion", suggestion));
    }

    @PostMapping("/lock-in")
    public String lockIn(@RequestParam Map<String, String> params, HttpSession httpSession) {
        PlayerSession ps = getSession(httpSession);
        if (ps == null) return "redirect:/";
        if (ps.isLocked()) return "redirect:/results";
        if (!answerRepo.findBySessionOrderByQuestionNumber(ps).isEmpty()) return "redirect:/results";

        List<DailyQuestion> questions = questionService.getTodaysQuestions();

        for (DailyQuestion q : questions) {
            String raw = params.getOrDefault("answer" + q.getQuestionNumber(), "").trim();
            Answer answer = new Answer(ps, q.getQuestionNumber(), raw);

            if (raw.isBlank()) {
                answer.setCorrectedName("");
                answer.setScoutScore(0);
                answer.setPoints(0);
                answer.setScoutReason("No answer given.");
            } else {
                String corrected = scoutService.checkSpelling(raw);
                answer.setCorrectedName(corrected.equals("UNKNOWN") ? raw : corrected);

                ScoutService.ScoringResult result = scoutService.scoreAnswer(
                        q.getPromptText(),
                        answer.getCorrectedName()
                );
                answer.setScoutScore(result.score());
                answer.setPoints(result.points());
                answer.setScoutReason(result.reason());
            }
            try {
                answerRepo.save(answer);
            } catch (DataIntegrityViolationException e) {
                // Same player locked in twice at once; the other request already saved this answer.
                return "redirect:/results";
            }
        }

        ps.setLocked(true);
        sessionRepo.save(ps);
        return "redirect:/results";
    }

    @GetMapping("/results")
    public String results(HttpSession httpSession, Model model) {
        PlayerSession ps = getSession(httpSession);
        if (ps == null) return "redirect:/";
        if (!ps.isLocked()) return "redirect:/play";

        List<DailyQuestion> questions = questionService.getTodaysQuestions();
        List<Answer> answers = answerRepo.findBySessionOrderByQuestionNumber(ps);

        Map<Integer, String> prompts = new LinkedHashMap<>();
        for (DailyQuestion q : questions) {
            prompts.put(q.getQuestionNumber(), q.getPromptText());
        }

        int total = answers.stream().mapToInt(Answer::getPoints).sum();

        model.addAttribute("playerName", ps.getPlayerName());
        model.addAttribute("answers", answers);
        model.addAttribute("prompts", prompts);
        model.addAttribute("total", total);
        return "results";
    }

    @GetMapping("/ladder")
    public String ladder(Model model) {
        LocalDate today = GameDay.today();
        List<PlayerSession> locked = sessionRepo.findByPlayDateAndLockedTrueOrderByIdAsc(today);

        List<Map<String, Object>> entries = locked.stream().map(ps -> {
            List<Answer> answers = answerRepo.findBySessionOrderByQuestionNumber(ps);
            int total = answers.stream().mapToInt(Answer::getPoints).sum();
            return Map.<String, Object>of("name", ps.getPlayerName(), "total", total);
        }).sorted((a, b) -> Integer.compare((int) b.get("total"), (int) a.get("total"))).toList();

        model.addAttribute("entries", entries);
        model.addAttribute("date", today);
        return "ladder";
    }

    private PlayerSession getSession(HttpSession httpSession) {
        Long id = (Long) httpSession.getAttribute("sessionId");
        if (id == null) return null;
        return sessionRepo.findById(id).orElse(null);
    }
}
