package win.furryhook.command.impl;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import win.furryhook.command.ClientCommand;
import win.furryhook.feature.FeatureManager;
import win.furryhook.utils.ConfigIO;
import win.furryhook.utils.text.ChatUtil;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ConfigCommand extends ClientCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigCommand.class);

    public ConfigCommand() {
        super("config", "Manages named configs: save/load/remove/list/dir");
    }

    @Override
    public void build(LiteralArgumentBuilder<Object> builder) {
        builder.executes(context -> {
            ChatUtil.usage("config save <name>  •  load <name>  •  remove <name>  •  list  •  dir");
            return 1;
        });
        builder.then(named("save", this::executeSave));
        builder.then(named("load", this::executeLoad));
        builder.then(named("remove", this::executeRemove));
        builder.then(LiteralArgumentBuilder.literal("list").executes(context -> {
            List<String> names = FeatureManager.INSTANCE.configNames();
            if (names.isEmpty()) {
                ChatUtil.info("No configs saved");
            } else {
                ChatUtil.header("Saved configs  •  " + names.size());
                names.forEach(name -> ChatUtil.entry(name, null));
            }
            return 1;
        }));
        builder.then(LiteralArgumentBuilder.literal("dir").executes(this::executeDir));
    }

    private int executeDir(CommandContext<Object> context) {
        Path dir = ConfigIO.configDir();
        try {
            Files.createDirectories(dir);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir.toFile());
            } else {
                new ProcessBuilder("explorer.exe", dir.toString()).start();
            }
            ChatUtil.success("Opened config directory  •  " + dir);
        } catch (IOException | UnsupportedOperationException exception) {
            LOGGER.warn("Failed to open config directory", exception);
            ChatUtil.error("Failed to open config directory  •  " + dir);
            return 0;
        }
        return 1;
    }

    private LiteralArgumentBuilder<Object> named(String literal, java.util.function.Function<String, Integer> action) {
        return LiteralArgumentBuilder.<Object>literal(literal)
                .then(RequiredArgumentBuilder.<Object, String>argument("name", StringArgumentType.word())
                        .executes(context -> action.apply(name(context))));
    }

    private int executeSave(String name) {
        if (FeatureManager.INSTANCE.saveConfigAs(name)) {
            ChatUtil.success("Saved config  •  " + name);
            return 1;
        }
        ChatUtil.error("Invalid config name  •  Use letters, digits, - or _  •  " + name);
        return 0;
    }

    private int executeLoad(String name) {
        if (FeatureManager.INSTANCE.loadConfig(name)) {
            ChatUtil.success("Loaded config  •  " + name);
            return 1;
        }
        ChatUtil.error("Config not found  •  " + name);
        return 0;
    }

    private int executeRemove(String name) {
        if (FeatureManager.INSTANCE.deleteConfig(name)) {
            ChatUtil.success("Removed config  •  " + name);
            return 1;
        }
        ChatUtil.error("Config not found  •  " + name);
        return 0;
    }

    private String name(CommandContext<Object> context) {
        return StringArgumentType.getString(context, "name");
    }
}
