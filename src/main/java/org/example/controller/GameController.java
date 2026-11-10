package org.example.controller;

import org.example.model.Answer;
import org.example.model.DailyQuestion;
import org.example.model.PlayerSession;
import org.example.model.GradeCache;
import org.example.repository.AnswerRepository;
import org.example.repository.GradeCacheRepository;
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
    private final GradeCacheRepository gradeCache;
    private static final int MAX_HINTS_PER_ROUND = 40;
    private final java.util.concurrent.ExecutorService gradingPool = java.util.concurrent.Executors.newFixedThreadPool(10);

    public GameController(QuestionService questionService, ScoutService scoutService,
                          PlayerSessionRepository sessionRepo, AnswerRepository answerRepo,
                          GradeCacheRepository gradeCache) {
        this.questionService = questionService;
        this.scoutService = scoutService;
        this.sessionRepo = sessionRepo;
        this.answerRepo = answerRepo;
        this.gradeCache = gradeCache;
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
    public ResponseEntity<Map<String, String>> checkSpelling(@RequestBody Map<String, Object> body, HttpSession httpSession) {
        String raw = String.valueOf(body.getOrDefault("name", "")).trim();
        if (raw.length() > 60) raw = raw.substring(0, 60);
        Map<String, String> none = Map.of("status", "unknown", "name", "");
        if (raw.isBlank()) {
            return ResponseEntity.ok(none);
        }
        // Hints cost money, so only players in today's game who haven't locked in yet get them,
        // and each gets plenty for a round (a full round uses about 5).
        PlayerSession ps = getSession(httpSession);
        if (ps == null || ps.isLocked() || !ps.getPlayDate().equals(GameDay.today())) {
            return ResponseEntity.ok(none);
        }
        Integer used = (Integer) httpSession.getAttribute("hintChecks");
        int count = used == null ? 0 : used;
        if (count >= MAX_HINTS_PER_ROUND) {
            return ResponseEntity.ok(none);
        }
        httpSession.setAttribute("hintChecks", count + 1);
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
                        () -> gradeOnce(q, raw), gradingPool));
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

        // "The room": what everyone else who locked in today answered, per question.
        // Only shown here, after this player has locked in, so nobody can peek before answering.
        Map<Integer, List<Map<String, Object>>> room = new LinkedHashMap<>();
        Map<Integer, Map<String, Integer>> pickCounts = new java.util.HashMap<>();
        for (PlayerSession other : sessionRepo.findByPlayDateAndLockedTrueOrderByIdAsc(ps.getPlayDate())) {
            for (Answer a : answerRepo.findBySessionOrderByQuestionNumber(other)) {
                if (a.getCorrectedName() == null || a.getCorrectedName().isBlank()) continue;
                pickCounts.computeIfAbsent(a.getQuestionNumber(), k -> new java.util.HashMap<>())
                        .merge(pickKey(a.getCorrectedName()), 1, Integer::sum);
                if (other.getId().equals(ps.getId())) continue;
                room.computeIfAbsent(a.getQuestionNumber(), k -> new java.util.ArrayList<>())
                        .add(Map.of("who", other.getPlayerName(), "answer", a.getCorrectedName(), "points", a.getPoints()));
            }
        }
        room.values().forEach(list -> list.sort((x, y) -> Integer.compare((int) y.get("points"), (int) x.get("points"))));
        // Your picks that nobody else named today (and that scored).
        java.util.Set<Integer> unique = new java.util.HashSet<>();
        for (Answer a : answers) {
            if (a.getPoints() > 0 && pickCounts.getOrDefault(a.getQuestionNumber(), Map.of())
                    .getOrDefault(pickKey(a.getCorrectedName()), 0) == 1 && room.containsKey(a.getQuestionNumber())) {
                unique.add(a.getQuestionNumber());
            }
        }

        // Spoiler-free summary for the share button: one square per question.
        StringBuilder squares = new StringBuilder();
        for (Answer a : answers) {
            squares.append(a.getPoints() >= 70 ? "\uD83D\uDFE9" : a.getPoints() > 0 ? "\uD83D\uDFE8"
                    : a.getRawInput() == null || a.getRawInput().isBlank() ? "\u2B1C" : "\uD83D\uDFE5");
        }
        String shareText = "DEEP CUT \u00B7 " + ps.getPlayDate().format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
                + "\n\uD83C\uDFD2 " + total + "/500\n" + squares;

        model.addAttribute("playerName", ps.getPlayerName());
        model.addAttribute("answers", answers);
        model.addAttribute("prompts", prompts);
        model.addAttribute("total", total);
        model.addAttribute("room", room);
        model.addAttribute("unique", unique);
        model.addAttribute("shareText", shareText);
        return "results";
    }

    @GetMapping("/ladder")
    public String ladder(Model model) {
        LocalDate date = GameDay.today();
        List<PlayerSession> locked = sessionRepo.findByPlayDateAndLockedTrueOrderByIdAsc(date);

        List<Map<String, Object>> entries = locked.stream().map(ps -> {
            List<Answer> answers = answerRepo.findBySessionOrderByQuestionNumber(ps);
            int total = answers.stream().mapToInt(Answer::getPoints).sum();
            return Map.<String, Object>of("name", ps.getPlayerName(), "total", total);
        }).sorted((a, b) -> Integer.compare((int) b.get("total"), (int) a.get("total"))).toList();

        model.addAttribute("entries", entries);
        model.addAttribute("date", date);
        return "ladder";
    }

    /** All-time standings: every locked-in day, grouped by player name (case-insensitive). */
    @GetMapping("/all-time")
    public String allTime(Model model) {
        Map<String, List<int[]>> byPlayer = new LinkedHashMap<>(); // player -> one {dayTotal, wonDay} per day
        Map<String, String> displayName = new LinkedHashMap<>();

        // Total per session, then which sessions won their day.
        Map<LocalDate, Integer> bestOfDay = new java.util.HashMap<>();
        Map<String, java.util.Set<LocalDate>> playedDays = new java.util.HashMap<>();
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
            playedDays.computeIfAbsent(key, k -> new java.util.HashSet<>()).add(ps.getPlayDate());
            byPlayer.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(new int[]{total, won ? 1 : 0});
        }
        List<Map<String, Object>> entries = byPlayer.entrySet().stream().map(e -> {
            List<int[]> days = e.getValue();
            int total = days.stream().mapToInt(d -> d[0]).sum();
            int best = days.stream().mapToInt(d -> d[0]).max().orElse(0);
            int wins = days.stream().mapToInt(d -> d[1]).sum();
            int avg = Math.round((float) total / days.size());
            return Map.<String, Object>of("name", displayName.get(e.getKey()), "days", days.size(),
                    "total", total, "avg", avg, "best", best, "wins", wins,
                    "streak", currentStreak(playedDays.get(e.getKey())));
        }).sorted((a, b) -> Integer.compare((int) b.get("total"), (int) a.get("total"))).toList();

        model.addAttribute("entries", entries);
        return "all-time";
    }

    /**
     * Grade an answer, reusing today's saved verdict when someone already gave the same answer to the same
     * question. Saves money (popular picks are graded once) and keeps verdicts consistent between friends.
     */
    private ScoutService.ScoringResult gradeOnce(DailyQuestion q, String raw) {
        String key = pickKey(raw);
        if (!key.isEmpty()) {
            Optional<GradeCache> saved = gradeCache.findByPlayDateAndQuestionNumberAndAnswerKey(
                    q.getPlayDate(), q.getQuestionNumber(), key);
            if (saved.isPresent()) {
                GradeCache g = saved.get();
                return new ScoutService.ScoringResult(g.isValid(), g.getScore(), g.isValid() ? g.getScore() * 10 : 0,
                        g.getPlayerName(), g.getReason(), true);
            }
        }
        ScoutService.ScoringResult result = scoutService.scoreAnswer(q.getPromptText(), raw);
        if (result.checked() && !key.isEmpty()) {
            try {
                gradeCache.save(new GradeCache(q.getPlayDate(), q.getQuestionNumber(), key,
                        result.valid(), result.score(), result.playerName(), result.reason()));
            } catch (DataIntegrityViolationException e) {
                // Another player's identical answer was saved a moment ago; fine.
            }
        }
        return result;
    }

    /** Consecutive days played up to today (or up to yesterday, if they haven't played yet today). */
    private static int currentStreak(java.util.Set<LocalDate> days) {
        LocalDate d = GameDay.today();
        if (!days.contains(d)) d = d.minusDays(1);
        int streak = 0;
        while (days.contains(d)) { streak++; d = d.minusDays(1); }
        return streak;
    }

    /** Same player typed two ways ("Jaromír Jágr" / "jaromir jagr") counts as one pick. */
    private static String pickKey(String name) {
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase().replaceAll("[^a-z]", "");
    }

    private PlayerSession getSession(HttpSession httpSession) {
        Long id = (Long) httpSession.getAttribute("sessionId");
        if (id == null) return null;
        return sessionRepo.findById(id).orElse(null);
    }
}
