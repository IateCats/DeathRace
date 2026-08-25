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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DeathRaceGame {
    private static final String OBJECTIVE = "deathrace";
    private static final int NEXT_ROUND_DELAY_TICKS = 100;
    private static final int REMINDER_INTERVAL_TICKS = 20;

    private final Map<UUID, Integer> scores = new HashMap<>();
    private final Set<UUID> skipVotes = new HashSet<>();
    private final Deque<DeathChallenge> challengeBag = new ArrayDeque<>();

    private boolean active;
    private DeathChallenge currentChallenge;
    private long roundStartedTick;
    private long nextRoundTick = -1;
    private int roundNumber;

    public int start(CommandSourceStack source) {
        if (active) {
            source.sendFailure(Component.literal("Death Race is already running."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        active = true;
        roundNumber = 0;
        nextRoundTick = -1;
        scores.clear();
        skipVotes.clear();
        challengeBag.clear();

        runCommand(server, "scoreboard objectives remove " + OBJECTIVE);
        runCommand(server, "scoreboard objectives add " + OBJECTIVE + " dummy {\"text\":\"DEATH RACE\",\"color\":\"dark_red\",\"bold\":true}");
        runCommand(server, "scoreboard objectives setdisplay sidebar " + OBJECTIVE);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            scores.put(player.getUUID(), 0);
            updateScore(server, player, 0);
        }

        broadcast(server, Component.literal("DEATH RACE STARTED!").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        startNextRound(server);
        source.sendSuccess(() -> Component.literal("Death Race started."), true);
        return 1;
    }

    public int stop(CommandSourceStack source) {
        if (!active) {
            source.sendFailure(Component.literal("Death Race is not running."));
            return 0;
        }
        finish(source.getServer(), Component.literal("Death Race stopped by an administrator."));
        return 1;
    }

    public int forceSkip(CommandSourceStack source) {
        if (!isRoundOpen()) {
            source.sendFailure(Component.literal("There is no active round to skip."));
            return 0;
        }
        skipRound(source.getServer(), Component.literal("Round skipped by an administrator."));
        return 1;
    }

    public int voteSkip(CommandSourceStack source) {
        if (!isRoundOpen()) {
            source.sendFailure(Component.literal("There is no active round to vote on."));
            return 0;
        }
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("Only a player can vote."));
            return 0;
        }

        if (!skipVotes.add(player.getUUID())) {
            source.sendFailure(Component.literal("You already voted to skip this round."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        int required = requiredVotes(server);
        broadcast(server, Component.literal(player.getGameProfile().getName() + " voted to skip (" + skipVotes.size() + "/" + required + ")")
                .withStyle(ChatFormatting.YELLOW));
        if (skipVotes.size() >= required) {
            skipRound(server, Component.literal("Vote passed! Selecting a new challenge."));
        }
        return 1;
    }

    public int status(CommandSourceStack source) {
        if (!active) {
            source.sendSuccess(() -> Component.literal("Death Race is not running."), false);
            return 1;
        }
        Component goal = currentChallenge == null
                ? Component.literal("Waiting for the next round...")
                : Component.literal("Round " + roundNumber + ": ").append(currentChallenge.title());
        source.sendSuccess(() -> goal, false);
        return 1;
    }

    public void onPlayerDeath(ServerPlayer player, DamageSource source) {
        if (!isRoundOpen() || !currentChallenge.matcher().test(source)) {
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null || nextRoundTick >= 0) {
            return;
        }

        long elapsedTicks = Math.max(0, server.getTickCount() - roundStartedTick);
        int speedBonus = speedBonus(elapsedTicks);
        int earned = 10 + speedBonus;
        int total = scores.merge(player.getUUID(), earned, Integer::sum);
        updateScore(server, player, total);
        nextRoundTick = server.getTickCount() + NEXT_ROUND_DELAY_TICKS;
        skipVotes.clear();

        Component winner = Component.literal(player.getGameProfile().getName())
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(" WON ROUND " + roundNumber + "!").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        broadcast(server, winner);
        broadcast(server, Component.literal("+10 points" + (speedBonus > 0 ? "  •  Speed bonus: +" + speedBonus : ""))
                .withStyle(ChatFormatting.GREEN));

        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            showTitle(online, Component.literal("ROUND WINNER!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                    Component.literal(player.getGameProfile().getName() + "  +" + earned + " points").withStyle(ChatFormatting.YELLOW), 5, 55, 15);
            online.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1.0F, 1.0F);
        }
        player.serverLevel().sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
                player.getX(), player.getY() + 1.0, player.getZ(), 120, 1.1, 1.3, 1.1, 0.25);
        player.serverLevel().sendParticles(ParticleTypes.FIREWORK,
                player.getX(), player.getY() + 1.0, player.getZ(), 80, 1.2, 1.5, 1.2, 0.18);
    }

    public void tick(MinecraftServer server) {
        if (!active) {
            return;
        }

        long tick = server.getTickCount();
        if (nextRoundTick >= 0 && tick >= nextRoundTick) {
            startNextRound(server);
            return;
        }

        if (currentChallenge != null && nextRoundTick < 0 && tick % REMINDER_INTERVAL_TICKS == 0) {
            long seconds = Math.max(0, (tick - roundStartedTick) / 20);
            Component reminder = Component.literal("☠ ").withStyle(ChatFormatting.DARK_RED)
                    .append(currentChallenge.title().copy().withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                    .append(Component.literal("  •  " + formatTime(seconds) + "  •  /deathrace vote").withStyle(ChatFormatting.GRAY));
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.displayClientMessage(reminder, true);
            }
        }
    }

    public void onPlayerJoin(ServerPlayer player) {
        if (!active) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        int score = scores.computeIfAbsent(player.getUUID(), ignored -> 0);
        updateScore(server, player, score);
        if (currentChallenge != null && nextRoundTick < 0) {
            showTitle(player, Component.literal("CURRENT DEATH GOAL").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                    currentChallenge.title().copy().withStyle(ChatFormatting.RED, ChatFormatting.BOLD), 10, 70, 20);
        }
    }

    private void startNextRound(MinecraftServer server) {
        if (!active) {
            return;
        }
        if (challengeBag.isEmpty()) {
            List<DeathChallenge> shuffled = new ArrayList<>(DeathChallenge.ALL);
            Collections.shuffle(shuffled);
            challengeBag.addAll(shuffled);
        }

        currentChallenge = challengeBag.removeFirst();
        roundNumber++;
        roundStartedTick = server.getTickCount();
        nextRoundTick = -1;
        skipVotes.clear();

        broadcast(server, Component.literal("Round " + roundNumber + " — Death Goal: ").withStyle(ChatFormatting.GRAY)
                .append(currentChallenge.title().copy().withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        broadcast(server, currentChallenge.hint().copy().withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            showTitle(player, Component.literal("DEATH GOAL").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                    currentChallenge.title().copy().withStyle(ChatFormatting.RED, ChatFormatting.BOLD), 10, 80, 20);
            player.playNotifySound(SoundEvents.WITHER_SPAWN, SoundSource.MASTER, 0.55F, 1.35F);
            player.serverLevel().sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    player.getX(), player.getY() + 1.0, player.getZ(), 30, 0.7, 1.0, 0.7, 0.04);
        }
    }

    private void skipRound(MinecraftServer server, Component reason) {
        broadcast(server, reason.copy().withStyle(ChatFormatting.YELLOW));
        currentChallenge = null;
        skipVotes.clear();
        nextRoundTick = server.getTickCount() + 60;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, 0.9F, 0.8F);
        }
    }

    private void finish(MinecraftServer server, Component reason) {
        active = false;
        currentChallenge = null;
        nextRoundTick = -1;
        skipVotes.clear();
        broadcast(server, reason.copy().withStyle(ChatFormatting.RED));
        runCommand(server, "scoreboard objectives setdisplay sidebar");
    }

    private boolean isRoundOpen() {
        return active && currentChallenge != null && nextRoundTick < 0;
    }

    private int requiredVotes(MinecraftServer server) {
        return Math.max(1, server.getPlayerList().getPlayerCount() / 2 + 1);
    }

    private int speedBonus(long elapsedTicks) {
        long seconds = elapsedTicks / 20;
        if (seconds <= 30) return 5;
        if (seconds <= 60) return 3;
        if (seconds <= 120) return 1;
        return 0;
    }

    private void updateScore(MinecraftServer server, ServerPlayer player, int score) {
        runCommand(server, "scoreboard players set " + player.getScoreboardName() + " " + OBJECTIVE + " " + score);
    }

    private void runCommand(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPermission(4).withSuppressedOutput(), command);
    }

    private void broadcast(MinecraftServer server, Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    private void showTitle(ServerPlayer player, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        player.connection.send(new ClientboundSetTitleTextPacket(title));
        player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
    }

    private String formatTime(long totalSeconds) {
        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
