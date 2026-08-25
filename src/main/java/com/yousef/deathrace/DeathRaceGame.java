package com.yousef.deathrace;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DeathRaceGame {
    private static final String OBJECTIVE = "deathrace";
    private static final String WINNER_TEAM = "deathrace_winner";
    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Map<UUID, Integer> streaks = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Set<UUID> skipVotes = new HashSet<>();
    private final Set<UUID> closeCalls = new HashSet<>();
    private final Deque<DeathChallenge> challengeBag = new ArrayDeque<>();
    private DeathRaceConfig config = DeathRaceConfig.load();
    private boolean active;
    private boolean matchEnding;
    private DeathChallenge challenge;
    private UUID roundWinner;
    private UUID matchWinner;
    private UUID glowingWinner;
    private String previousTeam;
    private long roundStart;
    private long winnerTick = -1;
    private long nextRoundTick = -1;
    private long glowEndTick = -1;
    private int round;

    public int start(CommandSourceStack source) {
        if (active) { source.sendFailure(Component.literal("Death Race is already running.")); return 0; }
        MinecraftServer server = source.getServer();
        config = DeathRaceConfig.load();
        active = true; matchEnding = false; round = 0; nextRoundTick = -1;
        scores.clear(); streaks.clear(); names.clear(); skipVotes.clear(); closeCalls.clear(); challengeBag.clear();
        run(server, "scoreboard objectives remove " + OBJECTIVE);
        run(server, "scoreboard objectives add " + OBJECTIVE + " dummy {\"text\":\"Death Race\",\"color\":\"dark_red\",\"bold\":true}");
        run(server, "scoreboard objectives setdisplay sidebar " + OBJECTIVE);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) registerPlayer(server, player);
        broadcast(server, Component.literal("DEATH RACE STARTED!").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        broadcast(server, Component.literal("First to " + config.pointsToWin() + " points wins!").withStyle(ChatFormatting.GOLD));
        nextRound(server);
        source.sendSuccess(() -> Component.literal("Death Race started. Settings loaded from config/deathrace.properties."), true);
        return 1;
    }

    public int stop(CommandSourceStack source) {
        if (!active) { source.sendFailure(Component.literal("Death Race is not running.")); return 0; }
        cleanupGlow(source.getServer()); active = false; challenge = null; roundWinner = null; nextRoundTick = -1;
        broadcast(source.getServer(), Component.literal("Death Race stopped by an administrator.").withStyle(ChatFormatting.RED));
        run(source.getServer(), "scoreboard objectives setdisplay sidebar"); return 1;
    }

    public int forceSkip(CommandSourceStack source) {
        if (!openRound()) { source.sendFailure(Component.literal("There is no active round to skip.")); return 0; }
        skip(source.getServer(), "Round skipped by an administrator."); return 1;
    }

    public int voteSkip(CommandSourceStack source) {
        if (!openRound()) { source.sendFailure(Component.literal("There is no active round to vote on.")); return 0; }
        ServerPlayer player;
        try { player = source.getPlayerOrException(); }
        catch (Exception e) { source.sendFailure(Component.literal("Only a player can vote.")); return 0; }
        if (!skipVotes.add(player.getUUID())) { source.sendFailure(Component.literal("You already voted to skip.")); return 0; }
        int needed = votesNeeded(source.getServer());
        broadcast(source.getServer(), Component.literal(player.getGameProfile().getName() + " voted to skip (" + skipVotes.size() + "/" + needed + ")").withStyle(ChatFormatting.YELLOW));
        if (skipVotes.size() >= needed) skip(source.getServer(), "Vote passed! Selecting a new challenge.");
        return 1;
    }

    public int status(CommandSourceStack source) {
        Component text = !active ? Component.literal("Death Race is not running.")
                : challenge == null ? Component.literal("Waiting for the next round...")
                : Component.literal("Round " + round + ": ").append(challenge.title()).append(Component.literal(" | First to " + config.pointsToWin()));
        source.sendSuccess(() -> text, false); return 1;
    }

    public void onPlayerDeath(ServerPlayer player, DamageSource damage) {
        if (!active || challenge == null || !challenge.matcher().test(damage)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (roundWinner != null) { closeCall(server, player); return; }
        if (!openRound()) return;

        long elapsed = Math.max(0, server.getTickCount() - roundStart);
        int bonus = speedBonus(elapsed);
        int earned = config.basePoints() + bonus;
        int total = scores.merge(player.getUUID(), earned, Integer::sum);
        names.put(player.getUUID(), player.getGameProfile().getName());
        setScore(server, player, total);
        roundWinner = player.getUUID(); winnerTick = server.getTickCount(); skipVotes.clear(); closeCalls.clear();
        celebrateRound(server, player, earned, bonus);
        announceStreak(server, player);
        startGlow(server, player);
        if (total >= config.pointsToWin()) { matchEnding = true; matchWinner = player.getUUID(); }
        nextRoundTick = winnerTick + (matchEnding ? Math.max(60, config.closeCallSeconds() * 20L) : config.nextRoundDelaySeconds() * 20L);
    }

    public void tick(MinecraftServer server) {
        if (!active) return;
        long tick = server.getTickCount();
        if (glowingWinner != null && tick >= glowEndTick) cleanupGlow(server);
        if (nextRoundTick >= 0 && tick >= nextRoundTick) {
            if (matchEnding) grandFinale(server); else nextRound(server);
            return;
        }
        if (challenge != null && roundWinner == null && tick % 20 == 0) {
            long seconds = Math.max(0, (tick - roundStart) / 20);
            Component bar = Component.literal("☠ ").withStyle(ChatFormatting.DARK_RED)
                    .append(challenge.title().copy().withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                    .append(Component.literal("  •  " + time(seconds)).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("  •  Skip " + skipVotes.size() + "/" + votesNeeded(server)).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("  •  /deathrace vote").withStyle(ChatFormatting.DARK_GRAY));
            for (ServerPlayer player : server.getPlayerList().getPlayers()) player.displayClientMessage(bar, true);
        }
    }

    public void onPlayerJoin(ServerPlayer player) {
        if (!active || player.getServer() == null) return;
        registerPlayer(player.getServer(), player);
        if (challenge != null && roundWinner == null) title(player, Component.literal("CURRENT DEATH GOAL").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), challenge.title().copy().withStyle(ChatFormatting.RED, ChatFormatting.BOLD), 10, 70, 20);
    }

    private void nextRound(MinecraftServer server) {
        cleanupGlow(server);
        if (challengeBag.isEmpty()) {
            List<DeathChallenge> pool = new ArrayList<>(DeathChallenge.ALL.stream().filter(c -> config.enabledChallenges().contains(c.id())).toList());
            if (pool.isEmpty()) pool.addAll(DeathChallenge.ALL);
            Collections.shuffle(pool); challengeBag.addAll(pool);
        }
        challenge = challengeBag.removeFirst(); roundWinner = null; winnerTick = -1; nextRoundTick = -1;
        skipVotes.clear(); closeCalls.clear(); round++; roundStart = server.getTickCount();
        broadcast(server, Component.literal("Round " + round + " — Death Goal: ").withStyle(ChatFormatting.GRAY).append(challenge.title().copy().withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        broadcast(server, challenge.hint().copy().withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            title(player, Component.literal("DEATH GOAL").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), challenge.title().copy().withStyle(ChatFormatting.RED, ChatFormatting.BOLD), 10, 80, 20);
            player.playNotifySound(SoundEvents.WITHER_SPAWN, SoundSource.MASTER, .55F, 1.35F);
            player.serverLevel().sendParticles(ParticleTypes.SOUL_FIRE_FLAME, player.getX(), player.getY() + 1, player.getZ(), 30, .7, 1, .7, .04);
        }
    }

    private void celebrateRound(MinecraftServer server, ServerPlayer winner, int earned, int bonus) {
        broadcast(server, Component.literal(winner.getGameProfile().getName()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(" WON ROUND " + round + "!").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)));
        broadcast(server, Component.literal("+" + config.basePoints() + " points" + (bonus > 0 ? "  •  Speed bonus: +" + bonus : "")).withStyle(ChatFormatting.GREEN));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            title(player, Component.literal("ROUND WINNER!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), Component.literal(winner.getGameProfile().getName() + "  +" + earned + " points").withStyle(ChatFormatting.YELLOW), 5, 55, 15);
            player.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1, 1);
        }
        winner.serverLevel().sendParticles(ParticleTypes.TOTEM_OF_UNDYING, winner.getX(), winner.getY() + 1, winner.getZ(), 120, 1.1, 1.3, 1.1, .25);
        winner.serverLevel().sendParticles(ParticleTypes.FIREWORK, winner.getX(), winner.getY() + 1, winner.getZ(), 80, 1.2, 1.5, 1.2, .18);
    }

    private void announceStreak(MinecraftServer server, ServerPlayer winner) {
        streaks.replaceAll((id, value) -> id.equals(winner.getUUID()) ? value : 0);
        int streak = streaks.merge(winner.getUUID(), 1, Integer::sum);
        if (streak == 2) broadcast(server, Component.literal("DOUBLE WIN! " + winner.getGameProfile().getName() + " has a 2-round streak!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        else if (streak == 3) broadcast(server, Component.literal("3-ROUND STREAK! " + winner.getGameProfile().getName() + " is dominating!").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        else if (streak >= 4) broadcast(server, Component.literal(streak + "-ROUND STREAK! Can anyone stop " + winner.getGameProfile().getName() + "?").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
    }

    private void closeCall(MinecraftServer server, ServerPlayer player) {
        if (player.getUUID().equals(roundWinner) || !closeCalls.add(player.getUUID())) return;
        long difference = server.getTickCount() - winnerTick;
        if (difference < 0 || difference > config.closeCallSeconds() * 20L) return;
        broadcast(server, Component.literal("SO CLOSE! ").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                .append(Component.literal(player.getGameProfile().getName() + " was only " + String.format("%.2f", difference / 20.0) + "s behind.").withStyle(ChatFormatting.WHITE)));
        player.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, .8F, 1.6F);
    }

    private void startGlow(MinecraftServer server, ServerPlayer winner) {
        cleanupGlow(server); glowingWinner = winner.getUUID(); previousTeam = winner.getTeam() == null ? null : winner.getTeam().getName(); glowEndTick = server.getTickCount() + 100;
        run(server, "team add " + WINNER_TEAM); run(server, "team modify " + WINNER_TEAM + " color gold");
        run(server, "team join " + WINNER_TEAM + " " + winner.getScoreboardName());
        run(server, "effect give " + winner.getScoreboardName() + " minecraft:glowing 5 0 true");
    }

    private void cleanupGlow(MinecraftServer server) {
        if (glowingWinner == null) return;
        String name = names.get(glowingWinner);
        if (name != null) { run(server, "team leave " + name); if (previousTeam != null && !previousTeam.equals(WINNER_TEAM)) run(server, "team join " + previousTeam + " " + name); }
        glowingWinner = null; previousTeam = null; glowEndTick = -1;
    }

    private void grandFinale(MinecraftServer server) {
        ServerPlayer champion = matchWinner == null ? null : server.getPlayerList().getPlayer(matchWinner);
        String championName = champion == null ? names.getOrDefault(matchWinner, "Unknown Player") : champion.getGameProfile().getName();
        broadcast(server, Component.literal("════════ FINAL LEADERBOARD ════════").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        List<Map.Entry<UUID, Integer>> ranking = scores.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).toList();
        for (int i = 0; i < ranking.size(); i++) {
            Map.Entry<UUID, Integer> entry = ranking.get(i);
            broadcast(server, Component.literal((i + 1) + ". " + names.getOrDefault(entry.getKey(), "Unknown Player") + " — " + entry.getValue() + " points").withStyle(i == 0 ? ChatFormatting.GOLD : ChatFormatting.GRAY));
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            title(player, Component.literal("DEATH RACE CHAMPION!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), Component.literal(championName + " wins the match!").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD), 10, 140, 30);
            player.playNotifySound(SoundEvents.RAID_HORN.value(), SoundSource.MASTER, 1, 1.1F);
            player.serverLevel().sendParticles(ParticleTypes.FIREWORK, player.getX(), player.getY() + 1.5, player.getZ(), 160, 2, 2, 2, .25);
        }
        if (champion != null) {
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(champion.serverLevel());
            if (bolt != null) { bolt.moveTo(champion.position()); bolt.setVisualOnly(true); champion.serverLevel().addFreshEntity(bolt); }
            champion.serverLevel().sendParticles(ParticleTypes.TOTEM_OF_UNDYING, champion.getX(), champion.getY() + 1, champion.getZ(), 300, 2, 2.5, 2, .35);
        }
        active = false; matchEnding = false; challenge = null; nextRoundTick = -1;
    }

    private void skip(MinecraftServer server, String reason) {
        broadcast(server, Component.literal(reason).withStyle(ChatFormatting.YELLOW)); challenge = null; roundWinner = null; skipVotes.clear(); nextRoundTick = server.getTickCount() + 60;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) player.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, .9F, .8F);
    }
    private boolean openRound() { return active && !matchEnding && challenge != null && roundWinner == null && nextRoundTick < 0; }
    private int votesNeeded(MinecraftServer server) { return Math.max(1, (int)Math.ceil(server.getPlayerList().getPlayerCount() * config.voteSkipPercentage())); }
    private int speedBonus(long ticks) { long s = ticks / 20; if (s <= config.fastSeconds()) return config.fastBonus(); if (s <= config.mediumSeconds()) return config.mediumBonus(); if (s <= config.slowSeconds()) return config.slowBonus(); return 0; }
    private void registerPlayer(MinecraftServer server, ServerPlayer player) { int score = scores.computeIfAbsent(player.getUUID(), id -> 0); names.put(player.getUUID(), player.getGameProfile().getName()); setScore(server, player, score); }
    private void setScore(MinecraftServer server, ServerPlayer player, int score) { run(server, "scoreboard players set " + player.getScoreboardName() + " " + OBJECTIVE + " " + score); }
    private void run(MinecraftServer server, String command) { server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPermission(4).withSuppressedOutput(), command); }
    private void broadcast(MinecraftServer server, Component message) { server.getPlayerList().broadcastSystemMessage(message, false); }
    private void title(ServerPlayer player, Component title, Component subtitle, int in, int stay, int out) { player.connection.send(new ClientboundSetTitlesAnimationPacket(in, stay, out)); player.connection.send(new ClientboundSetTitleTextPacket(title)); player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle)); }
    private String time(long seconds) { return String.format("%d:%02d", seconds / 60, seconds % 60); }
}
