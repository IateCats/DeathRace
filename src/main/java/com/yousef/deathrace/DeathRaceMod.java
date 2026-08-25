package com.yousef.deathrace;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(DeathRaceMod.MOD_ID)
public final class DeathRaceMod {
    public static final String MOD_ID = "deathrace";
    private final DeathRaceGame game = new DeathRaceGame();

    public DeathRaceMod() {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("deathrace")
                .then(Commands.literal("start")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> game.start(context.getSource())))
                .then(Commands.literal("stop")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> game.stop(context.getSource())))
                .then(Commands.literal("skip")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> game.forceSkip(context.getSource())))
                .then(Commands.literal("vote")
                        .executes(context -> game.voteSkip(context.getSource())))
                .then(Commands.literal("status")
                        .executes(context -> game.status(context.getSource()))));
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            game.onPlayerDeath(player, event.getSource());
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        game.tick(event.getServer());
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            game.onPlayerJoin(player);
        }
    }
}
