package win.furryhook.command.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import win.furryhook.command.ClientCommand;
import win.furryhook.command.CommandManager;
import win.furryhook.menu.i18n.MenuText;
import win.furryhook.utils.text.ChatUtil;

public final class HelpCommand extends ClientCommand {
    private final CommandManager manager;

    public HelpCommand(CommandManager manager) {
        super("help", "Shows all available commands");
        this.manager = manager;
    }

    @Override
    public void build(LiteralArgumentBuilder<Object> builder) {
        builder.executes(context -> {
            ChatUtil.header("Furry commands");
            for (ClientCommand command : manager.getCommands()) {
                ChatUtil.entry(manager.getPrefix() + command.name(), MenuText.commandDescription(command.description()));
            }
            return 1;
        });
    }
}
