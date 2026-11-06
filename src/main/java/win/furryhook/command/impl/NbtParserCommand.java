package win.furryhook.command.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import win.furryhook.command.ClientCommand;
import win.furryhook.utils.ScreenNbtParser;
import win.furryhook.utils.text.ChatUtil;

import java.nio.file.Path;

public final class NbtParserCommand extends ClientCommand {
    private final ScreenNbtParser parser = ScreenNbtParser.INSTANCE;

    public NbtParserCommand() {
        super("nbtparser", "Dumps container layouts and complete item NBT");
    }

    @Override
    public void build(LiteralArgumentBuilder<Object> builder) {
        builder.executes(context -> showStatus());
        builder.then(LiteralArgumentBuilder.<Object>literal("start")
                .executes(context -> start()));
        builder.then(LiteralArgumentBuilder.<Object>literal("stop")
                .executes(context -> stop()));
        builder.then(LiteralArgumentBuilder.<Object>literal("now")
                .executes(context -> captureNow()));
        builder.then(LiteralArgumentBuilder.<Object>literal("clear")
                .executes(context -> clear()));
        builder.then(LiteralArgumentBuilder.<Object>literal("status")
                .executes(context -> showStatus()));
    }

    private int start() {
        Minecraft client = Minecraft.getInstance();
        if (!this.parser.start(client)) {
            ChatUtil.error(this.parser.getLastError());
            return 0;
        }

        ChatUtil.success("NBT parser started  •  Open the required auction screens");
        ChatUtil.info("Log: " + displayPath(client));
        return 1;
    }

    private int stop() {
        Minecraft client = Minecraft.getInstance();
        if (!this.parser.stop(client)) {
            ChatUtil.error(this.parser.getLastError());
            return 0;
        }

        ChatUtil.success("NBT parser stopped  •  Snapshots: " + this.parser.getSnapshotCount());
        ChatUtil.info("Send this file: " + displayPath(client));
        return 1;
    }

    private int captureNow() {
        Minecraft client = Minecraft.getInstance();
        if (!this.parser.captureNow(client)) {
            ChatUtil.error(this.parser.getLastError());
            return 0;
        }

        ChatUtil.success("Current container captured  •  Snapshot " + this.parser.getSnapshotCount());
        return 1;
    }

    private int clear() {
        Minecraft client = Minecraft.getInstance();
        if (!this.parser.clear(client)) {
            ChatUtil.error(this.parser.getLastError());
            return 0;
        }

        ChatUtil.success("NBT parser log cleared");
        return 1;
    }

    private int showStatus() {
        Minecraft client = Minecraft.getInstance();
        ChatUtil.header("NBT parser");
        ChatUtil.info("State: " + (this.parser.isCapturing() ? "recording" : "stopped")
                + "  •  Snapshots: " + this.parser.getSnapshotCount());
        ChatUtil.info("Log: " + displayPath(client));
        ChatUtil.usage("nbtparser start  •  stop  •  now  •  clear  •  status");
        return 1;
    }

    private String displayPath(Minecraft client) {
        Path gameDirectory = client.gameDirectory.toPath().toAbsolutePath().normalize();
        Path output = this.parser.getOutputPath(client).toAbsolutePath().normalize();
        try {
            return gameDirectory.relativize(output).toString();
        } catch (IllegalArgumentException ignored) {
            return output.toString();
        }
    }
}
