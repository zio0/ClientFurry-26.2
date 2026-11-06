package win.furryhook.menu.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import win.furryhook.menu.core.MenuBackgroundFx;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.DiscordPresence;
import win.furryhook.utils.math.MathUtil;
import win.furryhook.utils.render.Textures;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.gui.Render2DUtil;
import win.furryhook.utils.render.gui.UiFontStyle;
import win.furryhook.utils.render.gui.UiFonts;

import java.awt.Desktop;
import java.net.URI;

/**
 * Title screen for the client: flat boot-screen background, the wordmark, the Discord
 * handle of whoever is running it, the button list and the two social glyphs.
 *
 * Nothing carries a surface except the buttons themselves - plain filled rows on the
 * background, no glass. Settings and Quit share the bottom row.
 *
 * Drawn by TitleScreenMixin, which cancels the vanilla panorama plus widgets and hands
 * the raw screen size and mouse position over. All sizes are GUI-scaled pixels - the
 * vanilla GUI Scale option stays the only scaling layer, same as any vanilla screen.
 */
public final class MainMenuScreen {
    /** Same flat charcoal the boot overlay paints, so startup never flashes a different tone. */
    private static final int BACKGROUND = ColorUtil.rgb(21, 21, 22);

    private static final String[] LABELS = {"Singleplayer", "Multiplayer", "Alt Manager", "Settings", "Quit"};
    /** Index of the first entry on the shared bottom row. */
    private static final int SPLIT_INDEX = 3;

    private static final float BLOCK_WIDTH = 220.0F;
    private static final float BLOCK_PADDING = 14.0F;

    private static final float TITLE_SIZE = 18.0F;
    private static final float TITLE_GAP = 6.0F;
    private static final float WELCOME_SIZE = 9.0F;
    private static final float HEADER_GAP = 15.0F;

    private static final float ROW_HEIGHT = 26.0F;
    private static final float ROW_GAP = 4.0F;
    private static final float ROW_RADIUS = 8.0F;
    private static final float ROW_LABEL_SIZE = 11.0F;
    private static final float SPLIT_GAP = 6.0F;
    private static final float SPLIT_TOP_GAP = 8.0F;

    private static final float SOCIAL_ICON = 15.0F;
    private static final float SOCIAL_GAP = 12.0F;
    private static final float SOCIAL_MARGIN = 16.0F;

    private static final float META_MARGIN = 14.0F;
    private static final float META_SIZE = 8.0F;

    private static final float APPEAR_SECONDS = 0.26F;
    private static final float APPEAR_RISE = 8.0F;
    private static final float SPEED_HOVER = 16.0F;

    private static final String TITLE = "Furry Client";
    private static final String TELEGRAM_URL = "https://t.me/furryclient";
    private static final String DISCORD_URL = "https://discord.gg/8rsMKurMRE";

    private static final UiAnimator ANIM = new UiAnimator();

    private static long openedAtNanos;

    private MainMenuScreen() {
    }

    /** Called from the mixin init() so the fade-in replays on every return to the menu. */
    public static void onOpen() {
        openedAtNanos = System.nanoTime();
        ANIM.reset();
    }

    // -- render ---------------------------------------------------------------

    public static void render(Minecraft minecraft, int width, int height, int mouseX, int mouseY) {
        ANIM.tick();

        float appear = openedAtNanos == 0L
                ? 1.0F
                : MathUtil.clamp((System.nanoTime() - openedAtNanos) / 1_000_000_000.0F / APPEAR_SECONDS, 0.0F, 1.0F);
        float eased = UiAnimator.easeOutCubic(appear);
        float rise = (1.0F - eased) * APPEAR_RISE;

        Render2DUtil.rect(0.0F, 0.0F, width, height)
                .color(BACKGROUND)
                .draw();

        // Ambient layer the module menu already uses: accent light pooling at the bottom
        // of the screen with embers drifting up out of it.
        MenuBackgroundFx.render(width, height, ANIM.delta(), eased, 1.0F, true, true);

        float blockX = blockX(width);
        float blockY = blockY(height) + rise;
        int accent = Theme.getAccent();

        float titleCenterY = blockY + BLOCK_PADDING + TITLE_SIZE * 0.5F;
        float titleWidth = widthOf(TITLE, TITLE_SIZE, UiFontStyle.SEMIBOLD);
        Render2DUtil.text(blockX + (BLOCK_WIDTH - titleWidth) * 0.5F,
                        UiFonts.sfPro(UiFontStyle.SEMIBOLD.weight()).centeredTextY(titleCenterY, TITLE_SIZE),
                        TITLE_SIZE, TITLE)
                .style(UiFontStyle.SEMIBOLD)
                .color(ColorUtil.multiplyAlpha(Theme.Colors.PRIMARY_BRIGHT, eased))
                .draw();

        renderWelcome(blockX, blockY + BLOCK_PADDING + TITLE_SIZE + TITLE_GAP, eased, accent);

        float[] rect = new float[4];
        for (int i = 0; i < LABELS.length; i++) {
            rowRect(i, width, height, rect);
            rect[1] += rise;
            boolean hovered = appear > 0.6F && insideRect(mouseX, mouseY, rect[0], rect[1], rect[2], rect[3]);
            renderRow(i, rect, hovered, eased, accent);
        }

        renderSocials(width, mouseX, mouseY, eased, accent, rise);
        renderMeta(minecraft, height, eased, rise);
    }

    private static void renderWelcome(float blockX, float top, float eased, int accent) {
        String handle = DiscordPresence.INSTANCE.username();
        boolean connected = handle != null && !handle.isBlank();
        String prefix = connected ? "Welcome, " : "Welcome";
        String name = connected ? handle : "";

        float prefixWidth = widthOf(prefix, WELCOME_SIZE, UiFontStyle.MEDIUM);
        float nameWidth = connected ? widthOf(name, WELCOME_SIZE, UiFontStyle.SEMIBOLD) : 0.0F;
        float startX = blockX + (BLOCK_WIDTH - (prefixWidth + nameWidth)) * 0.5F;
        float textY = UiFonts.sfPro(UiFontStyle.MEDIUM.weight()).centeredTextY(top + WELCOME_SIZE * 0.5F, WELCOME_SIZE);

        Render2DUtil.text(startX, textY, WELCOME_SIZE, prefix)
                .style(UiFontStyle.MEDIUM)
                .color(ColorUtil.multiplyAlpha(Theme.Colors.SECONDARY_DARK, eased))
                .draw();
        if (connected) {
            Render2DUtil.text(startX + prefixWidth, textY, WELCOME_SIZE, name)
                    .style(UiFontStyle.SEMIBOLD)
                    .color(ColorUtil.multiplyAlpha(accent, eased))
                    .draw();
        }
    }

    /** Plain filled button; the hover lifts the fill and pulls the border toward the accent. */
    private static void renderRow(int index, float[] rect, boolean hovered, float eased, int accent) {
        float hover = ANIM.value("row" + index, hovered ? 1.0F : 0.0F, SPEED_HOVER);
        boolean quit = index == LABELS.length - 1;
        int markerColor = quit ? Theme.Colors.SYSTEM_RED : accent;

        float x = rect[0];
        float y = rect[1];
        float width = rect[2];
        float height = rect[3];

        int fill = ColorUtil.lerp(Theme.Colors.CONTROL_IDLE, Theme.Colors.CONTROL_HOVER, hover);
        int border = ColorUtil.lerp(Theme.Colors.OUTLINES_SMALL, ColorUtil.withAlpha(markerColor, 110), hover);
        Render2DUtil.rect(x, y, width, height)
                .color(ColorUtil.multiplyAlpha(fill, eased))
                .radius(ROW_RADIUS)
                .border(1.0F, ColorUtil.multiplyAlpha(border, eased))
                .draw();

        String label = LABELS[index];
        float labelWidth = widthOf(label, ROW_LABEL_SIZE, UiFontStyle.MEDIUM);
        int idleColor = quit ? Theme.Colors.SECONDARY_DARK : Theme.Colors.SECONDARY;
        int labelColor = ColorUtil.lerp(idleColor, quit ? Theme.Colors.SYSTEM_RED : Theme.Colors.PRIMARY_BRIGHT, hover);
        Render2DUtil.text(x + (width - labelWidth) * 0.5F,
                        UiFonts.sfPro(UiFontStyle.MEDIUM.weight()).centeredTextY(y + height * 0.5F, ROW_LABEL_SIZE),
                        ROW_LABEL_SIZE, label)
                .style(UiFontStyle.MEDIUM)
                .color(ColorUtil.multiplyAlpha(labelColor, eased))
                .draw();
    }

    private static void renderSocials(int width, int mouseX, int mouseY, float eased, int accent, float rise) {
        float y = SOCIAL_MARGIN - rise;
        for (int i = 0; i < 2; i++) {
            float x = socialX(width, i);
            boolean hovered = insideRect(mouseX, mouseY, x, y, SOCIAL_ICON, SOCIAL_ICON);
            float hover = ANIM.value("social" + i, hovered ? 1.0F : 0.0F, SPEED_HOVER);
            Identifier icon = i == 0 ? Textures.Icons.TELEGRAM : Textures.Icons.DISCORD;
            Render2DUtil.texture(x, y, SOCIAL_ICON, SOCIAL_ICON, icon)
                    .color(ColorUtil.multiplyAlpha(ColorUtil.lerp(Theme.Colors.ICON, accent, hover), eased))
                    .draw();
        }
    }

    private static void renderMeta(Minecraft minecraft, int height, float eased, float rise) {
        String meta = "furryhook.win   " + minecraft.getLaunchedVersion();
        Render2DUtil.text(META_MARGIN,
                        UiFonts.sfPro(UiFontStyle.REGULAR_TRACKED.weight())
                                .centeredTextY(height - META_MARGIN + rise, META_SIZE),
                        META_SIZE, meta)
                .style(UiFontStyle.REGULAR_TRACKED)
                .color(ColorUtil.multiplyAlpha(Theme.Colors.SECONDARY_DARK, eased))
                .draw();
    }

    // -- input ----------------------------------------------------------------

    public static boolean mouseClicked(Screen parent, Minecraft minecraft, double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }

        int width = parent.width;
        int height = parent.height;

        float[] rect = new float[4];
        for (int i = 0; i < LABELS.length; i++) {
            rowRect(i, width, height, rect);
            if (insideRect((float) mouseX, (float) mouseY, rect[0], rect[1], rect[2], rect[3])) {
                playClick(minecraft);
                activate(parent, minecraft, i);
                return true;
            }
        }

        for (int i = 0; i < 2; i++) {
            if (insideRect((float) mouseX, (float) mouseY, socialX(width, i), SOCIAL_MARGIN, SOCIAL_ICON, SOCIAL_ICON)) {
                playClick(minecraft);
                openLink(i == 0 ? TELEGRAM_URL : DISCORD_URL);
                return true;
            }
        }
        return false;
    }

    private static void activate(Screen parent, Minecraft minecraft, int index) {
        switch (index) {
            case 0 -> minecraft.setScreenAndShow(new SelectWorldScreen(parent));
            case 1 -> minecraft.setScreenAndShow(new JoinMultiplayerScreen(parent));
            case 2 -> minecraft.setScreenAndShow(new AltsScreen(parent));
            case 3 -> minecraft.setScreenAndShow(new OptionsScreen(parent, minecraft.options, false));
            default -> minecraft.stop();
        }
    }

    static void playClick(Minecraft minecraft) {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private static void openLink(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {
            // Nothing sensible to do if the platform refuses to hand us a browser.
        }
    }

    // -- layout helpers -------------------------------------------------------

    /** Fills {x, y, width, height} for one row. Rows {@code SPLIT_INDEX}+ share the last line. */
    private static void rowRect(int index, int screenWidth, int screenHeight, float[] out) {
        float blockX = blockX(screenWidth);
        float blockY = blockY(screenHeight);
        float inner = BLOCK_WIDTH - BLOCK_PADDING * 2.0F;
        float rowsTop = blockY + BLOCK_PADDING + TITLE_SIZE + TITLE_GAP + WELCOME_SIZE + HEADER_GAP;

        if (index < SPLIT_INDEX) {
            out[0] = blockX + BLOCK_PADDING;
            out[1] = rowsTop + index * (ROW_HEIGHT + ROW_GAP);
            out[2] = inner;
            out[3] = ROW_HEIGHT;
            return;
        }

        float half = (inner - SPLIT_GAP) * 0.5F;
        out[0] = blockX + BLOCK_PADDING + (index - SPLIT_INDEX) * (half + SPLIT_GAP);
        out[1] = rowsTop + SPLIT_INDEX * (ROW_HEIGHT + ROW_GAP) - ROW_GAP + SPLIT_TOP_GAP;
        out[2] = half;
        out[3] = ROW_HEIGHT;
    }

    private static float blockHeight() {
        float rows = SPLIT_INDEX * (ROW_HEIGHT + ROW_GAP) - ROW_GAP + SPLIT_TOP_GAP + ROW_HEIGHT;
        return BLOCK_PADDING * 2.0F + TITLE_SIZE + TITLE_GAP + WELCOME_SIZE + HEADER_GAP + rows;
    }

    private static float blockX(int screenWidth) {
        return (screenWidth - BLOCK_WIDTH) * 0.5F;
    }

    private static float blockY(int screenHeight) {
        return Math.max(SOCIAL_MARGIN + SOCIAL_ICON + 12.0F,
                (screenHeight - blockHeight()) * 0.5F - screenHeight * 0.02F);
    }

    private static float socialX(int width, int index) {
        float rightmost = width - SOCIAL_MARGIN - SOCIAL_ICON;
        return rightmost - (1 - index) * (SOCIAL_ICON + SOCIAL_GAP);
    }

    static float widthOf(String text, float size, UiFontStyle style) {
        return UiFonts.sfPro(style.weight()).measureWidth(text, size, size * style.letterSpacingEm());
    }

    static boolean insideRect(float pointX, float pointY, float x, float y, float width, float height) {
        return pointX >= x && pointX <= x + width && pointY >= y && pointY <= y + height;
    }
}
