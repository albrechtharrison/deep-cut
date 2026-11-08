package org.example.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds each day's 5 prompts from a large pool of broad categories (hundreds of valid answers each).
 * Every day uses 5 different categories, and any exact prompt used recently is skipped, so the game
 * doesn't keep asking the same things.
 */
public final class QuestionBank {

    private QuestionBank() {}

    private static final List<String> TEAMS = List.of(
            "Anaheim Ducks", "Boston Bruins", "Buffalo Sabres", "Calgary Flames", "Carolina Hurricanes",
            "Chicago Blackhawks", "Colorado Avalanche", "Columbus Blue Jackets", "Dallas Stars",
            "Detroit Red Wings", "Edmonton Oilers", "Florida Panthers", "Los Angeles Kings", "Minnesota Wild",
            "Montreal Canadiens", "Nashville Predators", "New Jersey Devils", "New York Islanders",
            "New York Rangers", "Ottawa Senators", "Philadelphia Flyers", "Pittsburgh Penguins",
            "San Jose Sharks", "St. Louis Blues", "Tampa Bay Lightning", "Toronto Maple Leafs",
            "Vancouver Canucks", "Vegas Golden Knights", "Washington Capitals", "Winnipeg Jets");

    private static final List<String> CUP_TEAMS = List.of(
            "Anaheim Ducks", "Boston Bruins", "Carolina Hurricanes", "Chicago Blackhawks", "Colorado Avalanche",
            "Detroit Red Wings", "Edmonton Oilers", "Florida Panthers", "Los Angeles Kings",
            "Montreal Canadiens", "New Jersey Devils", "New York Islanders", "Pittsburgh Penguins",
            "Tampa Bay Lightning", "Toronto Maple Leafs", "Vegas Golden Knights", "Philadelphia Flyers");

    private static final List<String> COUNTRIES = List.of(
            "Canada", "the United States", "Sweden", "Finland", "Russia", "Czechia (or Czechoslovakia)",
            "Slovakia", "Europe");

    private static final List<String> PROVINCES_STATES = List.of(
            "Ontario", "Quebec", "Alberta", "Saskatchewan", "Manitoba", "British Columbia",
            "Minnesota", "Michigan", "Massachusetts", "New York State");

    private static final List<String[]> RIVAL_PAIRS = List.of(
            new String[]{"Toronto Maple Leafs", "Montreal Canadiens"},
            new String[]{"New York Rangers", "New York Islanders"},
            new String[]{"Philadelphia Flyers", "Pittsburgh Penguins"},
            new String[]{"Boston Bruins", "Montreal Canadiens"},
            new String[]{"Edmonton Oilers", "Calgary Flames"},
            new String[]{"Colorado Avalanche", "Detroit Red Wings"},
            new String[]{"Chicago Blackhawks", "Detroit Red Wings"},
            new String[]{"Los Angeles Kings", "Anaheim Ducks"},
            new String[]{"Washington Capitals", "Pittsburgh Penguins"},
            new String[]{"Toronto Maple Leafs", "Ottawa Senators"},
            new String[]{"Florida Panthers", "Tampa Bay Lightning"},
            new String[]{"Boston Bruins", "Toronto Maple Leafs"},
            new String[]{"New York Rangers", "New Jersey Devils"},
            new String[]{"Vancouver Canucks", "Edmonton Oilers"});

    private static final List<String> AWARDS = List.of(
            "Hart Trophy", "Vezina Trophy", "Norris Trophy", "Calder Trophy", "Selke Trophy",
            "Conn Smythe Trophy", "Art Ross Trophy", "Lady Byng Trophy");

    /** One category = a way to make a prompt. Each day takes at most one prompt per category. */
    private static final List<Function<Random, String>> CATEGORIES = List.of(
            r -> "Name an NHL player who has played for the " + pick(r, TEAMS),
            r -> "Name an NHL player from " + pick(r, COUNTRIES),
            r -> "Name an NHL player born in " + pick(r, PROVINCES_STATES),
            r -> pick(r, List.of("Name an NHL goalie", "Name an NHL defenseman", "Name an NHL center",
                    "Name an NHL left winger", "Name an NHL right winger")),
            r -> "Name a player who played in the NHL during the " + pick(r, List.of("1970s", "1980s", "1990s", "2000s", "2010s")),
            r -> pick(r, List.of("Name a player who scored 30+ goals in an NHL season",
                    "Name a player who scored 40+ goals in an NHL season",
                    "Name a player who recorded 100+ points in an NHL season",
                    "Name a player with 1,000+ career NHL points",
                    "Name a player with 400+ career NHL goals",
                    "Name a defenseman who scored 15+ goals in an NHL season",
                    "Name a goalie with 300+ career NHL wins")),
            r -> "Name a player who won the Stanley Cup with the " + pick(r, CUP_TEAMS),
            r -> "Name a player who was nominated for (or won) the " + pick(r, AWARDS),
            r -> { String[] p = pick(r, RIVAL_PAIRS); return "Name a player who played for both the " + p[0] + " and the " + p[1]; },
            r -> pick(r, List.of("Name a player who played 1,000+ NHL games",
                    "Name a player who spent his whole NHL career with one team",
                    "Name a player who played for 5 or more NHL teams",
                    "Name a player who played in the NHL past age 38")),
            r -> pick(r, List.of("Name an NHL player who played in the OHL",
                    "Name an NHL player who played in the WHL",
                    "Name an NHL player who played in the QMJHL",
                    "Name an NHL player who played NCAA college hockey",
                    "Name an NHL player who also played in the KHL",
                    "Name an NHL player who also played in the Swedish league (SHL)",
                    "Name an NHL player who played in the AHL before making the NHL")),
            r -> pick(r, List.of("Name a first-round NHL draft pick",
                    "Name a player picked in the top 10 of an NHL draft",
                    "Name an undrafted player who made the NHL",
                    "Name a player drafted in the 5th round or later who made the NHL")),
            r -> pick(r, List.of("Name a player who captained an NHL team",
                    "Name a player who played in an NHL All-Star Game",
                    "Name a member of the Hockey Hall of Fame",
                    "Name a player who has been traded at least 3 times")),
            r -> "Name a player who represented " + pick(r, List.of("Canada", "the USA", "Sweden", "Finland", "Russia", "Czechia")) + " at the Olympics or a World Championship",
            r -> pick(r, List.of("Name an NHL player whose brother also played in the NHL",
                    "Name an NHL player whose father also played in the NHL",
                    "Name a player who scored a goal in a Stanley Cup Final",
                    "Name a player who played in an NHL outdoor game",
                    "Name a player who won the Stanley Cup more than once")));

    /** Five prompts for the date, each from a different category, avoiding the recently used prompts. */
    public static List<String> promptsFor(LocalDate date, Set<String> recentlyUsed, int count) {
        Random r = new Random(date.toEpochDay() * 0x9E3779B97F4A7C15L);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < CATEGORIES.size(); i++) order.add(i);
        Collections.shuffle(order, r);

        List<String> out = new ArrayList<>();
        for (int idx : order) {
            if (out.size() == count) break;
            Function<Random, String> category = CATEGORIES.get(idx);
            // A few tries to find a prompt in this category that wasn't asked recently.
            for (int attempt = 0; attempt < 8; attempt++) {
                String prompt = category.apply(r);
                if (!recentlyUsed.contains(prompt) && !out.contains(prompt)) {
                    out.add(prompt);
                    break;
                }
            }
        }
        return out;
    }

    private static <T> T pick(Random r, List<T> list) {
        return list.get(r.nextInt(list.size()));
    }
}
