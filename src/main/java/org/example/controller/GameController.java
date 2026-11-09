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
    private final java.util.concurrent.ExecutorService gradingPool = java.util.concurrent.Executors.newFixedThreadPool(10);

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
    public ResponseEntity<Map<String, String>> checkSpelling(@RequestBody Map<String, Object> body) {
        String raw = String.valueOf(body.getOrDefault("name", "")).trim();
        if (raw.isBlank()) {
            return ResponseEntity.ok(Map.of("status", "unknown", "name", ""));
        }
        int number = body.get("question") instanceof Number n ? n.intValue() : 0;
        String question = questionService.getTodaysQuestions().stream()
                .filter(q -> q.getQuestionNumber() == number)
                .map(DailyQuestion::getPromptText)
                .findFirst().orElse("");
        ScoutService.NameCheck check = scoutService.checkSpelling(raw, question);
        return ResponseEntity.ok(Map.of("status", check.status(), "name", check.name()));
    }

    @PostMapping("/lock-in")
    public String lockIn(@RequestParam Map<String, String> params, HttpSession httpSession) {
        PlayerSession ps = getSession(httpSession);
        if (ps == null) return "redirect:/";
        if (ps.isLocked()) return "redirect:/results";
        if (!answerRepo.findBySessionOrderByQuestionNumber(ps).isEmpty()) return "redirect:/results";

        List<DailyQuestion> questions = questionService.getTodaysQuestions();

        // Grade all five at the same time: each check can include a web lookup, so one after another is slow.
        Map<Integer, java.util.concurrent.CompletableFuture<ScoutService.ScoringResult>> grading = new LinkedHashMap<>();
        for (DailyQuestion q : questions) {
            String raw = params.getOrDefault("answer" + q.getQuestionNumber(), "").trim();
            if (!raw.isBlank()) {
                grading.put(q.getQuestionNumber(), java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> scoutService.scoreAnswer(q.getPromptText(), raw), gradingPool));
            }
        }

        for (DailyQuestion q : questions) {
            String raw = params.getOrDefault("answer" + q.getQuestionNumber(), "").trim();
            Answer answer = new Answer(ps, q.getQuestionNumber(), raw);

            if (raw.isBlank()) {
                answer.setCorrectedName("");
                answer.setScoutScore(0);
                answer.setPoints(0);
                answer.setScoutReason("No answer given.");
            } else {
                // Grade exactly what the player typed. The scout works out misspellings itself, with the
                // question as context, instead of us swapping in a "corrected" name that may be someone else.
                ScoutService.ScoringResult result = grading.get(q.getQuestionNumber()).join();
                answer.setCorrectedName(result.playerName());
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

    /** All-time standings: every locked-in day, grouped by player name (case-insensitive). */
    @GetMapping("/all-time")
    public String allTime(Model model) {
        Map<String, List<int[]>> byPlayer = new LinkedHashMap<>(); // player -> one {dayTotal, wonDay} per day
        Map<String, String> displayName = new LinkedHashMap<>();

        // Total per session, then which sessions won their day.
        Map<LocalDate, Integer> bestOfDay = new java.util.HashMap<>();
        List<Object[]> rows = new java.util.ArrayList<>();
        for (PlayerSession ps : sessionRepo.findByLockedTrue()) {
            int total = answerRepo.findBySessionOrderByQuestionNumber(ps).stream().mapToInt(Answer::getPoints).sum();
            rows.add(new Object[]{ps, total});
            bestOfDay.merge(ps.getPlayDate(), total, Math::max);
        }
        for (Object[] r : rows) {
            PlayerSession ps = (PlayerSession) r[0];
            int total = (int) r[1];
            String key = ps.getPlayerName().trim().toLowerCase();
            displayName.putIfAbsent(key, ps.getPlayerName().trim());
            boolean won = total > 0 && total == bestOfDay.get(ps.getPlayDate());
            byPlayer.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(new int[]{total, won ? 1 : 0});
        }
        List<Map<String, Object>> entries = byPlayer.entrySet().stream().map(e -> {
            List<int[]> days = e.getValue();
            int total = days.stream().mapToInt(d -> d[0]).sum();
            int best = days.stream().mapToInt(d -> d[0]).max().orElse(0);
            int wins = days.stream().mapToInt(d -> d[1]).sum();
            int avg = Math.round((float) total / days.size());
            return Map.<String, Object>of("name", displayName.get(e.getKey()), "days", days.size(),
                    "total", total, "avg", avg, "best", best, "wins", wins);
        }).sorted((a, b) -> Integer.compare((int) b.get("total"), (int) a.get("total"))).toList();

        model.addAttribute("entries", entries);
        return "all-time";
    }

    private PlayerSession getSession(HttpSession httpSession) {
        Long id = (Long) httpSession.getAttribute("sessionId");
        if (id == null) return null;
        return sessionRepo.findById(id).orElse(null);
    }
}
