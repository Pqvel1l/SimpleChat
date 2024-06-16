package me.vetustus.server.simplechat;

import com.google.gson.Gson;
import eu.pb4.placeholders.api.PlaceholderContext;
import eu.pb4.placeholders.api.Placeholders;
import eu.pb4.placeholders.api.TextParserUtils;
import me.vetustus.server.simplechat.api.event.PlayerChatCallback;
import me.vetustus.server.simplechat.integration.FTBTeamsIntegration;
import me.vetustus.server.simplechat.integration.LuckPermsIntegration;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.io.BufferedReader;

import static me.vetustus.server.simplechat.ChatColor.translateChatColors;

public class SimpleChat implements ModInitializer {
    public ChatConfig config;
    private static final Logger LOGGER = LogManager.getLogger();

    @Override
    public void onInitialize() {

        try {
            loadConfig();
	    LOGGER.info("The config is saved!");
        } catch (IOException e) {
            e.printStackTrace();
        }

        boolean ftbteams = FabricLoader.getInstance().isModLoaded("ftbteams");
        boolean luckperms = FabricLoader.getInstance().isModLoaded("luckperms");

        PlayerChatCallback.EVENT.register((player, message) -> {
            PlayerChatCallback.ChatMessage chatMessage = new PlayerChatCallback.ChatMessage(player, message);

            if (!config.isChatModEnabled())
                return chatMessage;

            chatMessage.setCancelled(true);

            boolean isGlobalMessage = false;
            boolean isWorldMessage = false;
            String chatFormat = config.getLocalChatFormat();
            if (config.isGlobalChatEnabled()) {
                if (message.startsWith("!")) {
                    isGlobalMessage = true;
                    chatFormat = config.getGlobalChatFormat();
                    message = message.substring(1);
                }
            }
            if (config.isWorldChatEnabled()) {
                if (message.startsWith("#")) {
                    isWorldMessage = true;
                    chatFormat = config.getWorldChatFormat();
                    message = message.substring(1);
                }
            }
            String prepareStringMessage = chatFormat
                    .replaceAll("%player%", player.getName().getString())
                    .replaceAll("%ftbteam%", ftbteams ? FTBTeamsIntegration.getTeam(player) : "")
                    .replaceAll("%lp_group%", luckperms ? translateChatColors('&', LuckPermsIntegration.getPrimaryGroup(player)) : "")
                    .replaceAll("%lp_prefix%", luckperms ? translateChatColors('&', LuckPermsIntegration.getPrefix(player)) : "")
                    .replaceAll("%lp_suffix%", luckperms ? translateChatColors('&', LuckPermsIntegration.getSuffix(player)) : "");
            prepareStringMessage = translateChatColors('&', prepareStringMessage);

            String stringMessage = prepareStringMessage
                    .replaceAll("%message%", message);

            if (config.isChatColorsEnabled())
                stringMessage = translateChatColors('&', stringMessage);

            Text resultMessage = Placeholders.parseText(TextParserUtils.formatText(stringMessage), PlaceholderContext.of(player));

            int isPlayerLocalFound = 0;

            List<ServerPlayerEntity> players = Objects.requireNonNull(player.getServer(), "The server cannot be null.")
                    .getPlayerManager().getPlayerList();
            for (ServerPlayerEntity p : players) {
                if (config.isGlobalChatEnabled()) {
                    if (isGlobalMessage) {
                        p.sendMessage(resultMessage, false);
                    } else if (isWorldMessage && config.isWorldChatEnabled()) {
                        if (p.getEntityWorld().getRegistryKey().getValue() == player.getEntityWorld().getRegistryKey().getValue()) {
                            p.sendMessage(resultMessage, false);
                        }
                    } else {
                        if (p.squaredDistanceTo(player) <= config.getChatRange() && p.getEntityWorld().getRegistryKey().getValue() == player.getEntityWorld().getRegistryKey().getValue()) {
                            p.sendMessage(resultMessage, false);
                            isPlayerLocalFound++;
                        }
                    }
                } else if (config.isWorldChatEnabled()) {
                    if (isWorldMessage) {
                        if (p.getEntityWorld().getRegistryKey().getValue() == player.getEntityWorld().getRegistryKey().getValue()) {
                            p.sendMessage(resultMessage, false);
                        }
                    } else {
                        if (p.squaredDistanceTo(player) <= config.getChatRange() && p.getEntityWorld().getRegistryKey().getValue() == player.getEntityWorld().getRegistryKey().getValue()) {
                            p.sendMessage(resultMessage, false);
                            isPlayerLocalFound++;
                        }
                    }
                } else {
                    p.sendMessage(resultMessage, false);
                }
            }

            if (config.noPlayerNearbyMessage() && isPlayerLocalFound <= 1 && !isGlobalMessage && !isWorldMessage) {
                String noPlayerNearbyText = config.getNoPlayerNearbyText();
                Text noPlayerNearbyTextResult = literal(translateChatColors('&', noPlayerNearbyText));
                player.sendMessage(noPlayerNearbyTextResult, config.noPlayerNearbyActionBar());
            }

            // Убедитесь, что логируется только одно сообщение
            LOGGER.info(ChatUtils.stripColorCodes(stringMessage));
            return chatMessage;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("simplechat").executes(context -> {
                    if (context.getSource().hasPermissionLevel(1)) {
                        try {
                            loadConfig();
                            context.getSource().sendMessage(literal("Settings are reloaded!"));
                        } catch (IOException e) {
                            context.getSource().sendMessage(literal("An error occurred while reloading the settings (see the console)!"));
                            e.printStackTrace();
                        }
                    } else {
                        context.getSource().sendMessage(literal("You don't have the right to do this! If you think this is an error, contact your server administrator.")
                                .copy().formatted(Formatting.RED));
                    }
                    return 1;
                })));
    }
    public class ChatUtils {
        public static String stripColorCodes(String message) {
            return message.replaceAll("(?i)<c:#([0-9a-fA-F]{6})>|<white>|<gray>|</gray>", "");
        }
    }

    private void loadConfig() throws IOException {
        File configFile = new File(ChatConfig.CONFIG_PATH);
        File configFolder = new File("config/");
        if (!configFolder.exists())
            configFolder.mkdirs();
        if (!configFile.exists()) {
            Files.copy(Objects.requireNonNull(
                    this.getClass().getClassLoader().getResourceAsStream("simplechat.json"),
                    "Couldn't find the configuration file in the JAR"), configFile.toPath());
        }
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(ChatConfig.CONFIG_PATH), StandardCharsets.UTF_8)) {
            config = new Gson().fromJson(reader, ChatConfig.class);
        } catch (FileNotFoundException e) {
            config = new ChatConfig();
            e.printStackTrace();
        }
    }




    private Text literal(String text) {
        return Text.literal(text);
    }
}
