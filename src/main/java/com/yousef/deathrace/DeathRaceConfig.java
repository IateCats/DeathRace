package com.yousef.deathrace;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public record DeathRaceConfig(int pointsToWin, int basePoints,
        int fastSeconds, int fastBonus, int mediumSeconds, int mediumBonus,
        int slowSeconds, int slowBonus, int nextRoundDelaySeconds,
        int closeCallSeconds, double voteSkipPercentage, Set<String> enabledChallenges) {
    private static final Path FILE = Path.of("config", "deathrace.properties");

    public static DeathRaceConfig load() {
        Properties p = defaults();
        try {
            Files.createDirectories(FILE.getParent());
            if (Files.exists(FILE)) {
                try (InputStream in = Files.newInputStream(FILE)) { p.load(in); }
            } else {
                try (OutputStream out = Files.newOutputStream(FILE)) {
                    p.store(out, "Death Race settings - restart the match after editing");
                }
            }
        } catch (Exception ignored) { }
        Set<String> enabled = Arrays.stream(p.getProperty("enabledChallenges", ids()).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toCollection(HashSet::new));
        return new DeathRaceConfig(
                number(p, "pointsToWin", 100, 1, 100000), number(p, "basePoints", 10, 0, 10000),
                number(p, "fastBonusSeconds", 30, 1, 3600), number(p, "fastBonusPoints", 5, 0, 10000),
                number(p, "mediumBonusSeconds", 60, 1, 3600), number(p, "mediumBonusPoints", 3, 0, 10000),
                number(p, "slowBonusSeconds", 120, 1, 3600), number(p, "slowBonusPoints", 1, 0, 10000),
                number(p, "nextRoundDelaySeconds", 5, 2, 30), number(p, "closeCallSeconds", 2, 1, 10),
                decimal(p, "voteSkipPercentage", 0.51, 0.1, 1.0), enabled);
    }

    private static Properties defaults() {
        Properties p = new Properties();
        p.setProperty("pointsToWin", "100"); p.setProperty("basePoints", "10");
        p.setProperty("fastBonusSeconds", "30"); p.setProperty("fastBonusPoints", "5");
        p.setProperty("mediumBonusSeconds", "60"); p.setProperty("mediumBonusPoints", "3");
        p.setProperty("slowBonusSeconds", "120"); p.setProperty("slowBonusPoints", "1");
        p.setProperty("nextRoundDelaySeconds", "5"); p.setProperty("closeCallSeconds", "2");
        p.setProperty("voteSkipPercentage", "0.51"); p.setProperty("enabledChallenges", ids());
        return p;
    }

    private static String ids() {
        return DeathChallenge.ALL.stream().map(DeathChallenge::id).collect(Collectors.joining(","));
    }
    private static int number(Properties p, String key, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(p.getProperty(key)))); }
        catch (Exception e) { return fallback; }
    }
    private static double decimal(Properties p, String key, double fallback, double min, double max) {
        try { return Math.max(min, Math.min(max, Double.parseDouble(p.getProperty(key)))); }
        catch (Exception e) { return fallback; }
    }
}
