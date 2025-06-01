package com.example.sharedhealthmod;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Mod("sharedhealthmod")
public class SharedHealthMod {

    public static final String MODID = "sharedhealthmod";
    private static final Logger LOGGER = LogManager.getLogger();

    // Добавляем Set для отслеживания игроков в процессе обработки
    private final Set<ServerPlayerEntity> processingPlayers = new HashSet<>();

    public SharedHealthMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("SharedHealthMod loaded!");
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPlayerDamage(LivingDamageEvent event) {
        if (!(event.getEntityLiving() instanceof ServerPlayerEntity)) {
            return;
        }

        ServerPlayerEntity damagedPlayer = (ServerPlayerEntity) event.getEntityLiving();

        // Предотвращаем рекурсию
        if (processingPlayers.contains(damagedPlayer)) {
            return;
        }

        MinecraftServer server = damagedPlayer.getServer();
        if (server == null) return;

        List<ServerPlayerEntity> players = server.getPlayerList().getPlayers();

        // Если игрок один, обычное поведение
        if (players.size() <= 1) {
            return;
        }

        float damage = event.getAmount();

        // Отменяем стандартный урон
        event.setCanceled(true);

        // Применяем урон ко всем игрокам напрямую
        for (ServerPlayerEntity player : players) {
            if (player.isAlive()) {
                processingPlayers.add(player);
                try {
                    float currentHealth = player.getHealth();
                    float newHealth = currentHealth - damage;

                    if (newHealth <= 0.0F) {
                        // Убиваем игрока
                        player.setHealth(0.0F);
                        if (!player.isCreative() && !player.isSpectator()) {
                            player.die(DamageSource.GENERIC);
                        }
                    } else {
                        player.setHealth(newHealth);
                    }
                } finally {
                    processingPlayers.remove(player);
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPlayerHeal(LivingHealEvent event) {
        if (!(event.getEntityLiving() instanceof ServerPlayerEntity)) {
            return;
        }

        ServerPlayerEntity healedPlayer = (ServerPlayerEntity) event.getEntityLiving();

        // Предотвращаем рекурсию
        if (processingPlayers.contains(healedPlayer)) {
            return;
        }

        MinecraftServer server = healedPlayer.getServer();
        if (server == null) return;

        List<ServerPlayerEntity> players = server.getPlayerList().getPlayers();

        // Если игрок один, обычное поведение
        if (players.size() <= 1) {
            return;
        }

        float healAmount = event.getAmount();

        // Отменяем стандартное лечение для оригинального игрока
        event.setCanceled(true);

        // Лечим всех игроков (включая того, кто изначально лечился)
        for (ServerPlayerEntity player : players) {
            if (player.isAlive()) {
                processingPlayers.add(player);
                try {
                    player.heal(healAmount);
                } finally {
                    processingPlayers.remove(player);
                }
            }
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayerEntity)) {
            return;
        }

        ServerPlayerEntity joinedPlayer = (ServerPlayerEntity) event.getPlayer();
        MinecraftServer server = joinedPlayer.getServer();

        if (server == null) return;

        List<ServerPlayerEntity> players = server.getPlayerList().getPlayers();

        if (players.size() > 1) {
            // Синхронизируем здоровье с другими игроками
            float targetHealth = 0;
            boolean foundAlivePlayer = false;

            for (ServerPlayerEntity player : players) {
                if (player != joinedPlayer && player.isAlive()) {
                    targetHealth = player.getHealth();
                    foundAlivePlayer = true;
                    break;
                }
            }

            if (foundAlivePlayer) {
                joinedPlayer.setHealth(targetHealth);
                LOGGER.info("Synchronized health for player {} with existing players: {}",
                        joinedPlayer.getName().getString(), targetHealth);
            }
        }
    }
}