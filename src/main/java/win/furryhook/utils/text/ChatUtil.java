package win.furryhook.utils.text;

import lombok.experimental.UtilityClass;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import win.furryhook.context.MinecraftContext;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.render.Theme;

@UtilityClass
public class ChatUtil {
    private final String PREFIX = "furryhook: ";

    public void print(String message) {
        info(message);
    }

    public void header(String message) {
        send(message);
    }

    public void info(String message) {
        send(message);
    }

    public void success(String message) {
        send(message);
    }

    public void error(String message) {
        send(message);
    }

    public void usage(String message) {
        send(message);
    }

    public void entry(String title, String detail) {
        MutableComponent component = gradient(PREFIX + title);
        if (detail != null && !detail.isBlank()) {
            component.append(Component.literal("  •  " + detail).withStyle(Style.EMPTY.withColor(ColorUtil.WHITE & 0xFFFFFF)));
        }
        sendComponent(component);
    }

    public void send(String message) {
        sendComponent(gradient(PREFIX + message));
    }

    private void sendComponent(MutableComponent component) {
        Minecraft mc = MinecraftContext.mc;
        if (mc.gui != null) {
            mc.gui.hud.getChat().addClientSystemMessage(component);
        }
    }

    private MutableComponent gradient(String text) {
        MutableComponent result = Component.empty();
        int startColor = Theme.getAccent();
        int endColor = ColorUtil.lerp(startColor, ColorUtil.WHITE, 0.62F);
        int cursor = 0;

        while (cursor < text.length()) {
            int codePoint = text.codePointAt(cursor);
            appendColored(result, new String(Character.toChars(codePoint)),
                    gradientColor(startColor, endColor, cursor, text.length()));
            cursor += Character.charCount(codePoint);
        }
        return result;
    }

    private static int gradientColor(int start, int end, int index, int length) {
        return ColorUtil.lerp(start, end, length <= 1 ? 0.0F : index / (float) (length - 1));
    }

    private static void appendColored(MutableComponent target, String text, int color) {
        target.append(Component.literal(text).withStyle(Style.EMPTY.withColor(color & 0xFFFFFF)));
    }
}
