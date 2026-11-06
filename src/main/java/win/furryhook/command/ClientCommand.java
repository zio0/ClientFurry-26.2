package win.furryhook.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import java.util.List;

public abstract class ClientCommand {
    private final String name;
    private final String description;

    protected ClientCommand(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public final String name() {
        return name;
    }

    public final String description() {
        return description;
    }

    public List<String> aliases() {
        return List.of();
    }

    public abstract void build(LiteralArgumentBuilder<Object> builder);
}
