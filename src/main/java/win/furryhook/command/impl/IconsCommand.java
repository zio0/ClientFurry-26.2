package win.furryhook.command.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import win.furryhook.command.ClientCommand;
import win.furryhook.utils.render.gui.GuiTexture;
import win.furryhook.utils.text.ChatUtil;

import java.util.List;

/**
 * Turns every SVG icon in the interface off and on without a restart.
 *
 * <p>Exists to answer one question in a single launch: do the menu icons have anything to do with
 * the native memory leak. The evidence says no — bucketing their rasterisation moved the leak from
 * 128 MB/s to 107 MB/s, which is the same number inside measurement noise — but the icons arrived
 * in the same change that the crashes did, and a suspicion that cheap to test is worth testing.
 *
 * <p>Deleting the icons outright would answer it too, and cost forty-five of them permanently. This
 * costs a keystroke and is reversible mid-session.
 */
public final class IconsCommand extends ClientCommand {

    public IconsCommand() {
        super("icons", "Иконки интерфейса: icons on|off");
    }

    @Override
    public List<String> aliases() {
        return List.of("svg");
    }

    @Override
    public void build(LiteralArgumentBuilder<Object> builder) {
        builder.executes(context -> report());
        builder.then(LiteralArgumentBuilder.literal("on").executes(context -> set(false)));
        builder.then(LiteralArgumentBuilder.literal("off").executes(context -> set(true)));
    }

    private int set(boolean disabled) {
        GuiTexture.setSvgIconsDisabled(disabled);
        if (disabled) {
            ChatUtil.success("Иконки выключены  •  меню без картинок, растеризация SVG не выполняется");
        } else {
            ChatUtil.success("Иконки включены");
        }
        return 1;
    }

    private int report() {
        ChatUtil.info(GuiTexture.svgIconsDisabled() ? "Иконки: выключены" : "Иконки: включены");
        ChatUtil.usage("icons on  •  icons off");
        return 1;
    }
}
