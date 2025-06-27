package com.example.sharedhealthmod;


import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


@Mod("sharedhealth")
public class SharedHealthMod {

    public enum Mode {
        SHARED,
        INDIVIDUAL
    }


    private static Mode currentMode = Mode.SHARED;
    private static final Set<UUID> activeSyncs = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public SharedHealthMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }


    @SubscribeEvent
    public void onPlayerDamage(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer targetPlayer)) return;
        if (targetPlayer.level().isClientSide()) return;
        if (targetPlayer.isCreative() || targetPlayer.isSpectator()) return;
        if (currentMode != Mode.SHARED) return;

        if (activeSyncs.contains(targetPlayer.getUUID())) {
            activeSyncs.remove(targetPlayer.getUUID());
            return;
        }

        float damageAmount = event.getAmount();
        if (damageAmount <= 0) return;


        for (ServerPlayer player : getEligiblePlayers(targetPlayer)) {
            if (player == targetPlayer) continue;
            activeSyncs.add(player.getUUID());
            player.hurt(player.damageSources().generic(), damageAmount);
        }
    }

    @SubscribeEvent
    public void onPlayerHeal(LivingHealEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer targetPlayer)) return;
        if (targetPlayer.level().isClientSide()) return;
        if (targetPlayer.isCreative() || targetPlayer.isSpectator()) return;
        if (currentMode != Mode.SHARED) return;

        if (activeSyncs.contains(targetPlayer.getUUID())) {
            activeSyncs.remove(targetPlayer.getUUID());
            return;
        }

        float healAmount = event.getAmount();

        if (healAmount <= 0) return;

        for (ServerPlayer player : getEligiblePlayers(targetPlayer)) {
            if (player == targetPlayer) continue;
            activeSyncs.add(player.getUUID());
            player.heal(healAmount);

        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer newPlayer)) return;
        if (newPlayer.level().isClientSide()) return;
        if (newPlayer.isCreative() || newPlayer.isSpectator()) return;
        if (currentMode != Mode.SHARED) return;

        for (ServerPlayer player : getEligiblePlayers(newPlayer)) {
            if (player == newPlayer) continue;
            newPlayer.setHealth(player.getHealth());
            break;
        }
    }

    @SubscribeEvent
    public void onCommandRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("sh")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("mode")
                        .then(Commands.literal("shared")
                                .executes(ctx -> {
                                    currentMode = Mode.SHARED;
                                    sendFeedback(ctx.getSource(), "Shared health is ENABLED");
                                    return 1;
                                }))
                        .then(Commands.literal("individual")
                                .executes(ctx -> {
                                    currentMode = Mode.INDIVIDUAL;
                                    sendFeedback(ctx.getSource(), "Shared health is DISABLED");
                                    return 1;
                                }))
                        .then(Commands.literal("sync")
                                .executes(ctx -> {
                                    if (currentMode == Mode.SHARED) {
                                        syncAllPlayers(ctx.getSource());
                                        sendFeedback(ctx.getSource(), "All health are synchronized");
                                    } else {
                                        sendFeedback(ctx.getSource(), "ERR: Shared health is DISABLED");
                                    }
                                    return 1;
                                }))));
    }

    private List<ServerPlayer> getEligiblePlayers(ServerPlayer reference) {
        return reference.getServer().getPlayerList().getPlayers().stream()
                .filter(p -> !p.isCreative() && !p.isSpectator())
                .toList();
    }


    private void syncAllPlayers(CommandSourceStack source) {
        List<ServerPlayer> players = source.getLevel().players().stream()
                .filter(p -> p instanceof ServerPlayer)
                .map(p -> (ServerPlayer) p)
                .filter(p -> !p.isCreative() && !p.isSpectator())
                .toList();

        if (players.isEmpty()) return;

        float health = players.get(0).getHealth();
        for (int i = 1; i < players.size(); i++) {
            players.get(i).setHealth(health);
        }

        // Уведомление игроку о текущем режиме
        joinedPlayer.sendMessage(
                new StringTextComponent("[SharedHealth] Текущий режим: " + currentMode.getName() + " - " + currentMode.getDescription())
                        .withStyle(TextFormatting.AQUA),
                joinedPlayer.getGameProfile().getId()
        );
    }

    private void sendFeedback(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }
}