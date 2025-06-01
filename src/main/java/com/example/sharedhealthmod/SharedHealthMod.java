package com.example.sharedhealthmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
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

    // Режимы работы мода
    public enum DamageMode {
        ALL_FOR_ALL("all_for_all", "Все игроки получают урон от любого источника"),
        NOT_ME("not_me", "Урон получают все игроки кроме изначально поврежденного"),
        DISABLED("disabled", "Мод отключен, обычная механика урона");

        private final String name;
        private final String description;

        DamageMode(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }

        public static DamageMode fromString(String name) {
            for (DamageMode mode : values()) {
                if (mode.name.equalsIgnoreCase(name)) {
                    return mode;
                }
            }
            return null;
        }
    }

    // Текущий режим работы (по умолчанию all_for_all)
    private DamageMode currentMode = DamageMode.ALL_FOR_ALL;

    // Set для отслеживания игроков в процессе обработки
    private final Set<ServerPlayerEntity> processingPlayers = new HashSet<>();

    public SharedHealthMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("SharedHealthMod loaded! Default mode: {}", currentMode.getName());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSource> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("sharedhp")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("mode")
                        .then(Commands.argument("mode_name", StringArgumentType.string())
                                .executes(this::setMode)
                        )
                        .executes(this::showCurrentMode)
                )
                .then(Commands.literal("help")
                        .executes(this::showHelp)
                )
                .executes(this::showHelp)
        );
    }

    private int setMode(CommandContext<CommandSource> context) {
        String modeName = StringArgumentType.getString(context, "mode_name");
        DamageMode newMode = DamageMode.fromString(modeName);

        if (newMode == null) {
            context.getSource().sendFailure(
                    new StringTextComponent("Неизвестный режим: " + modeName)
            );
            return 0;
        }

        currentMode = newMode;
        context.getSource().sendSuccess(
                new StringTextComponent("Режим изменен на: " + newMode.getName() + " - " + newMode.getDescription())
                        .withStyle(TextFormatting.GREEN), true
        );

        LOGGER.info("Damage mode changed to: {}", newMode.getName());
        return 1;
    }

    private int showCurrentMode(CommandContext<CommandSource> context) {
        context.getSource().sendSuccess(
                new StringTextComponent("Текущий режим: " + currentMode.getName() + " - " + currentMode.getDescription())
                        .withStyle(TextFormatting.YELLOW), false
        );
        return 1;
    }

    private int showHelp(CommandContext<CommandSource> context) {
        context.getSource().sendSuccess(
                new StringTextComponent("=== SharedHealth Mod ===").withStyle(TextFormatting.GOLD), false
        );
        context.getSource().sendSuccess(
                new StringTextComponent("/sharedhp mode <режим> - изменить режим работы").withStyle(TextFormatting.AQUA), false
        );
        context.getSource().sendSuccess(
                new StringTextComponent("/sharedhp mode - показать текущий режим").withStyle(TextFormatting.AQUA), false
        );
        context.getSource().sendSuccess(
                new StringTextComponent("Доступные режимы:").withStyle(TextFormatting.WHITE), false
        );

        for (DamageMode mode : DamageMode.values()) {
            context.getSource().sendSuccess(
                    new StringTextComponent("  • " + mode.getName() + " - " + mode.getDescription())
                            .withStyle(TextFormatting.GRAY), false
            );
        }
        return 1;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPlayerDamage(LivingDamageEvent event) {
        if (currentMode == DamageMode.DISABLED) {
            return;
        }

        if (!(event.getEntityLiving() instanceof ServerPlayerEntity)) {
            return;
        }

        ServerPlayerEntity damagedPlayer = (ServerPlayerEntity) event.getEntityLiving();

        if (processingPlayers.contains(damagedPlayer)) {
            return;
        }

        MinecraftServer server = damagedPlayer.getServer();
        if (server == null) return;

        List<ServerPlayerEntity> players = server.getPlayerList().getPlayers();

        if (players.size() <= 1) {
            return;
        }

        float damage = event.getAmount();
        event.setCanceled(true);

        for (ServerPlayerEntity player : players) {
            if (player.isAlive()) {
                boolean shouldDamage = false;

                switch (currentMode) {
                    case ALL_FOR_ALL:
                        shouldDamage = true;
                        break;
                    case NOT_ME:
                        shouldDamage = !player.equals(damagedPlayer);
                        break;
                }

                if (shouldDamage) {
                    processingPlayers.add(player);
                    try {
                        float currentHealth = player.getHealth();
                        float newHealth = currentHealth - damage;

                        if (newHealth <= 0.0F) {
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
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPlayerHeal(LivingHealEvent event) {
        if (currentMode == DamageMode.DISABLED) {
            return;
        }

        if (!(event.getEntityLiving() instanceof ServerPlayerEntity)) {
            return;
        }

        ServerPlayerEntity healedPlayer = (ServerPlayerEntity) event.getEntityLiving();

        if (processingPlayers.contains(healedPlayer)) {
            return;
        }

        MinecraftServer server = healedPlayer.getServer();
        if (server == null) return;

        List<ServerPlayerEntity> players = server.getPlayerList().getPlayers();

        if (players.size() <= 1) {
            return;
        }

        float healAmount = event.getAmount();
        event.setCanceled(true);

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
        if (currentMode == DamageMode.DISABLED) {
            return;
        }

        if (!(event.getPlayer() instanceof ServerPlayerEntity)) {
            return;
        }

        ServerPlayerEntity joinedPlayer = (ServerPlayerEntity) event.getPlayer();
        MinecraftServer server = joinedPlayer.getServer();

        if (server == null) return;

        List<ServerPlayerEntity> players = server.getPlayerList().getPlayers();

        if (players.size() > 1) {
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

        // Уведомление игроку о текущем режиме
        joinedPlayer.sendMessage(
                new StringTextComponent("[SharedHealth] Текущий режим: " + currentMode.getName() + " - " + currentMode.getDescription())
                        .withStyle(TextFormatting.AQUA),
                joinedPlayer.getGameProfile().getId()
        );
    }
}