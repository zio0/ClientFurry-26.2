package win.furryhook.menu.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import win.furryhook.account.Alt;
import win.furryhook.account.AltStore;
import win.furryhook.menu.core.MenuBackgroundFx;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.RandomNick;
import win.furryhook.utils.math.MathUtil;
import win.furryhook.utils.render.Textures;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.gui.PlayerHead;
import win.furryhook.utils.render.gui.Render2DUtil;
import win.furryhook.utils.render.gui.UiFontStyle;
import win.furryhook.utils.render.gui.UiFonts;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Alt Manager: one panel, one list, one input row.
 *
 * A row is the login button - double click it, or select it and press Enter with the
 * input empty. The dice next to the input rolls a name out of {@link RandomNick}.
 */
public final class AltsScreen extends Screen {
    private static final int BACKGROUND = ColorUtil.rgb(21, 21, 22);
    private static final int PANEL = ColorUtil.rgb(10, 11, 13);

    private static final float PANEL_WIDTH = 300.0F;
    private static final float PANEL_HEIGHT = 210.0F;
    private static final float PANEL_RADIUS = 10.0F;
    private static final float PADDING = 12.0F;

    private static final float HEADER_HEIGHT = 30.0F;
    private static final float FOOTER_HEIGHT = 38.0F;

    private static final float ROW_HEIGHT = 28.0F;
    private static final float ROW_PADDING_X = 10.0F;
    private static final float HEAD_SIZE = 18.0F;
    private static final float HEAD_GAP = 8.0F;
    private static final float REMOVE_ICON = 8.0F;

    private static final float INPUT_HEIGHT = 22.0F;
    private static final float ADD_WIDTH = 54.0F;
    private static final float DICE_SIZE = 22.0F;
    private static final float CONTROL_GAP = 6.0F;
    private static final float CONTROL_RADIUS = 6.0F;

    private static final float TITLE_SIZE = 12.0F;
    private static final float NAME_SIZE = 11.0F;
    private static final float META_SIZE = 8.0F;
    private static final float LABEL_SIZE = 10.0F;

    private static final float SCROLL_STEP = ROW_HEIGHT;
    private static final float SCROLLBAR_WIDTH = 3.0F;
    private static final float SPEED_HOVER = 16.0F;
    private static final long REMOVE_CONFIRM_NANOS = 3_000_000_000L;
    private static final int NAME_MAX_LENGTH = 16;

    private final UiAnimator anim = new UiAnimator();
    private final StringBuilder input = new StringBuilder();
    private final Screen parent;

    private int selected;
    private float scroll;
    private float scrollMax;
    private Alt pendingRemove;
    private long pendingRemoveUntilNanos;
    private long diceFlashNanos;

    public AltsScreen(Screen parent) {
        super(Component.literal("Alts"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Alt current = AltStore.current();
        List<Alt> alts = AltStore.all();
        this.selected = current == null ? 0 : Math.max(0, alts.indexOf(current));
        this.anim.reset();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(this.parent);
    }

    // -- render ---------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphicsExtractor, int mouseX, int mouseY, float partialTick) {
        UiFrame.draw(guiGraphicsExtractor, () -> renderPanel(mouseX, mouseY));
    }

    private void renderPanel(int mouseX, int mouseY) {
        this.anim.tick();

        Render2DUtil.rect(0.0F, 0.0F, this.width, this.height)
                .color(BACKGROUND)
                .draw();
        MenuBackgroundFx.render(this.width, this.height, this.anim.delta(), 1.0F, 1.0F, true, true);

        float panelX = panelX();
        float panelY = panelY();

        Render2DUtil.rect(panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT)
                .color(PANEL)
                .radius(PANEL_RADIUS)
                .border(1.0F, Theme.Colors.PANEL_BORDER)
                .draw();

        renderHeader(panelX, panelY, mouseX, mouseY);
        renderList(panelX, panelY, mouseX, mouseY);
        renderFooter(panelX, panelY, mouseX, mouseY);
    }

    private void renderHeader(float panelX, float panelY, int mouseX, int mouseY) {
        Render2DUtil.text(panelX + PADDING,
                        UiFonts.sfPro(UiFontStyle.SEMIBOLD.weight()).centeredTextY(panelY + HEADER_HEIGHT * 0.5F, TITLE_SIZE),
                        TITLE_SIZE, "Alts")
                .style(UiFontStyle.SEMIBOLD)
                .color(Theme.Colors.PRIMARY_BRIGHT)
                .draw();

        float closeX = panelX + PANEL_WIDTH - PADDING - REMOVE_ICON - 1.0F;
        float closeY = panelY + (HEADER_HEIGHT - REMOVE_ICON) * 0.5F;
        boolean closeHovered = MainMenuScreen.insideRect(mouseX, mouseY, closeX - 4.0F, closeY - 4.0F,
                REMOVE_ICON + 8.0F, REMOVE_ICON + 8.0F);
        float closeHover = this.anim.value("close", closeHovered ? 1.0F : 0.0F, SPEED_HOVER);

        int count = AltStore.size();
        String countLabel = count + (count == 1 ? " account" : " accounts");
        float countWidth = MainMenuScreen.widthOf(countLabel, META_SIZE, UiFontStyle.REGULAR_TRACKED);
        Render2DUtil.text(closeX - 10.0F - countWidth,
                        UiFonts.sfPro(UiFontStyle.REGULAR_TRACKED.weight()).centeredTextY(panelY + HEADER_HEIGHT * 0.5F, META_SIZE),
                        META_SIZE, countLabel)
                .style(UiFontStyle.REGULAR_TRACKED)
                .color(Theme.Colors.SECONDARY_DARK)
                .draw();

        Render2DUtil.texture(closeX, closeY, REMOVE_ICON, REMOVE_ICON, Textures.Icons.X)
                .color(ColorUtil.lerp(Theme.Colors.ICON_GHOST, Theme.Colors.PRIMARY_BRIGHT, closeHover))
                .draw();

        Render2DUtil.rect(panelX, panelY + HEADER_HEIGHT, PANEL_WIDTH, 1.0F)
                .color(Theme.Colors.DIVIDER_SUBTLE)
                .draw();
    }

    private void renderList(float panelX, float panelY, int mouseX, int mouseY) {
        List<Alt> alts = AltStore.all();
        float listTop = panelY + HEADER_HEIGHT;
        float listHeight = PANEL_HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT;
        float content = alts.size() * ROW_HEIGHT;

        this.scrollMax = Math.max(0.0F, content - listHeight);
        this.scroll = MathUtil.clamp(this.scroll, 0.0F, this.scrollMax);

        Alt current = AltStore.current();
        int accent = Theme.getAccent();

        Render2DUtil.pushScissor(panelX, listTop, PANEL_WIDTH, listHeight);
        for (int i = 0; i < alts.size(); i++) {
            Alt alt = alts.get(i);
            float rowY = listTop - this.scroll + i * ROW_HEIGHT;
            if (rowY + ROW_HEIGHT < listTop || rowY > listTop + listHeight) {
                continue;
            }
            renderRow(alt, i, panelX, rowY, alt == current, mouseX, mouseY, accent, listTop, listHeight);
        }
        Render2DUtil.popScissor();

        if (this.scrollMax > 0.5F) {
            float trackHeight = listHeight - 8.0F;
            float thumbHeight = Math.max(18.0F, trackHeight * (listHeight / content));
            float progress = this.scroll / this.scrollMax;
            float thumbY = listTop + 4.0F + (trackHeight - thumbHeight) * progress;
            Render2DUtil.rect(panelX + PANEL_WIDTH - SCROLLBAR_WIDTH - 3.0F, thumbY, SCROLLBAR_WIDTH, thumbHeight)
                    .color(Theme.Colors.OUTLINES_LARGE)
                    .radius(SCROLLBAR_WIDTH * 0.5F)
                    .draw();
        }

        Render2DUtil.rect(panelX, listTop + listHeight, PANEL_WIDTH, 1.0F)
                .color(Theme.Colors.DIVIDER_SUBTLE)
                .draw();
    }

    private void renderRow(Alt alt, int index, float panelX, float rowY, boolean current,
                           int mouseX, int mouseY, int accent, float listTop, float listHeight) {
        boolean inList = mouseY >= listTop && mouseY <= listTop + listHeight;
        boolean hovered = inList && MainMenuScreen.insideRect(mouseX, mouseY, panelX, rowY, PANEL_WIDTH, ROW_HEIGHT);
        boolean confirming = alt == this.pendingRemove && System.nanoTime() < this.pendingRemoveUntilNanos;
        if (alt == this.pendingRemove && !confirming) {
            this.pendingRemove = null;
        }

        float hover = this.anim.value("row" + index, hovered ? 1.0F : 0.0F, SPEED_HOVER);
        float select = this.anim.value("rowSelect" + index, index == this.selected ? 1.0F : 0.0F, SPEED_HOVER);

        int fill = ColorUtil.lerp(ColorUtil.multiplyAlpha(Theme.Colors.SURFACE_HOVER, hover),
                Theme.Colors.SURFACE_ACTIVE, select);
        if (confirming) {
            fill = ColorUtil.withAlpha(Theme.Colors.SYSTEM_RED, 0.16F);
        }
        Render2DUtil.rect(panelX + 4.0F, rowY + 1.0F, PANEL_WIDTH - 8.0F, ROW_HEIGHT - 2.0F)
                .color(fill)
                .radius(6.0F)
                .draw();

        if (current) {
            Render2DUtil.rect(panelX + 4.0F, rowY + 7.0F, 2.0F, ROW_HEIGHT - 14.0F)
                    .color(accent)
                    .radius(1.0F)
                    .draw();
        }

        float headX = panelX + ROW_PADDING_X + 2.0F;
        float headY = rowY + (ROW_HEIGHT - HEAD_SIZE) * 0.5F;
        PlayerHead.draw(headX, headY, HEAD_SIZE, alt.skin(), 3.0F, ColorUtil.WHITE);

        float textX = headX + HEAD_SIZE + HEAD_GAP;
        int nameColor = current
                ? Theme.Colors.PRIMARY_BRIGHT
                : ColorUtil.lerp(Theme.Colors.TEXT, Theme.Colors.PRIMARY_BRIGHT, Math.max(hover, select));
        Render2DUtil.text(textX, UiFonts.sfPro(UiFontStyle.MEDIUM.weight()).centeredTextY(rowY + 10.0F, NAME_SIZE),
                        NAME_SIZE, alt.name())
                .style(UiFontStyle.MEDIUM)
                .color(nameColor)
                .draw();

        String meta = confirming ? "click again to remove" : alt.lastUsedLabel();
        Render2DUtil.text(textX, UiFonts.sfPro(UiFontStyle.REGULAR_TRACKED.weight()).centeredTextY(rowY + 20.0F, META_SIZE),
                        META_SIZE, meta)
                .style(UiFontStyle.REGULAR_TRACKED)
                .color(confirming ? Theme.Colors.SYSTEM_RED : Theme.Colors.SECONDARY_DARK)
                .draw();

        float removeX = removeIconX(panelX);
        float removeY = rowY + (ROW_HEIGHT - REMOVE_ICON) * 0.5F;
        boolean removeHovered = inList && MainMenuScreen.insideRect(mouseX, mouseY,
                removeX - 5.0F, removeY - 5.0F, REMOVE_ICON + 10.0F, REMOVE_ICON + 10.0F);
        float removeHover = this.anim.value("remove" + index, removeHovered ? 1.0F : 0.0F, SPEED_HOVER);
        float removeAlpha = confirming ? 1.0F : Math.max(hover, select);
        if (removeAlpha > 0.02F) {
            int removeColor = ColorUtil.lerp(Theme.Colors.ICON_GHOST, Theme.Colors.SYSTEM_RED,
                    Math.max(removeHover, confirming ? 1.0F : 0.0F));
            Render2DUtil.texture(removeX, removeY, REMOVE_ICON, REMOVE_ICON, Textures.Icons.X)
                    .color(ColorUtil.multiplyAlpha(removeColor, removeAlpha))
                    .draw();
        }
    }

    private void renderFooter(float panelX, float panelY, int mouseX, int mouseY) {
        float footerTop = panelY + PANEL_HEIGHT - FOOTER_HEIGHT;
        float controlY = footerTop + (FOOTER_HEIGHT - INPUT_HEIGHT) * 0.5F;
        float inputX = panelX + PADDING;
        float inputWidth = inputWidth();
        int accent = Theme.getAccent();

        Render2DUtil.rect(inputX, controlY, inputWidth, INPUT_HEIGHT)
                .color(Theme.Colors.CONTROL_IDLE)
                .radius(CONTROL_RADIUS)
                .border(1.0F, ColorUtil.withAlpha(accent, 70))
                .draw();

        boolean empty = this.input.length() == 0;
        String shown = empty ? "nickname" : this.input.toString();
        if (!empty && caretVisible()) {
            shown = shown + "|";
        } else if (empty && caretVisible()) {
            shown = "|";
        }
        Render2DUtil.text(inputX + 9.0F,
                        UiFonts.sfPro(UiFontStyle.MEDIUM.weight()).centeredTextY(controlY + INPUT_HEIGHT * 0.5F, LABEL_SIZE),
                        LABEL_SIZE, shown)
                .style(UiFontStyle.MEDIUM)
                .color(empty && !caretVisible() ? Theme.Colors.SECONDARY_DARK : Theme.Colors.TEXT)
                .draw();

        // Dice: rolls a furry/brainrot name into the field.
        float diceX = inputX + inputWidth + CONTROL_GAP;
        boolean diceHovered = MainMenuScreen.insideRect(mouseX, mouseY, diceX, controlY, DICE_SIZE, INPUT_HEIGHT);
        float diceHover = this.anim.value("dice", diceHovered ? 1.0F : 0.0F, SPEED_HOVER);
        float flash = this.diceFlashNanos == 0L
                ? 0.0F
                : MathUtil.clamp(1.0F - (System.nanoTime() - this.diceFlashNanos) / 400_000_000.0F, 0.0F, 1.0F);
        Render2DUtil.rect(diceX, controlY, DICE_SIZE, INPUT_HEIGHT)
                .color(ColorUtil.lerp(Theme.Colors.CONTROL_IDLE, Theme.Colors.CONTROL_HOVER, diceHover))
                .radius(CONTROL_RADIUS)
                .border(1.0F, Theme.Colors.OUTLINES_SMALL)
                .draw();
        float diceIcon = 13.0F;
        Render2DUtil.texture(diceX + (DICE_SIZE - diceIcon) * 0.5F, controlY + (INPUT_HEIGHT - diceIcon) * 0.5F,
                        diceIcon, diceIcon, Textures.Icons.DICES)
                .color(ColorUtil.lerp(ColorUtil.lerp(Theme.Colors.ICON, Theme.Colors.PRIMARY_BRIGHT, diceHover), accent, flash))
                .draw();

        // Add: solid, the one filled control on the screen.
        float addX = panelX + PANEL_WIDTH - PADDING - ADD_WIDTH;
        boolean valid = canAdd();
        boolean addHovered = valid && MainMenuScreen.insideRect(mouseX, mouseY, addX, controlY, ADD_WIDTH, INPUT_HEIGHT);
        float addHover = this.anim.value("add", addHovered ? 1.0F : 0.0F, SPEED_HOVER);
        int addFill = valid
                ? ColorUtil.lerp(Theme.Colors.PRIMARY, Theme.Colors.PRIMARY_BRIGHT, addHover)
                : Theme.Colors.CONTROL_IDLE;
        Render2DUtil.rect(addX, controlY, ADD_WIDTH, INPUT_HEIGHT)
                .color(addFill)
                .radius(CONTROL_RADIUS)
                .border(1.0F, valid ? ColorUtil.TRANSPARENT : Theme.Colors.OUTLINES_SMALL)
                .draw();
        float addLabelWidth = MainMenuScreen.widthOf("Add", LABEL_SIZE, UiFontStyle.MEDIUM);
        Render2DUtil.text(addX + (ADD_WIDTH - addLabelWidth) * 0.5F,
                        UiFonts.sfPro(UiFontStyle.MEDIUM.weight()).centeredTextY(controlY + INPUT_HEIGHT * 0.5F, LABEL_SIZE),
                        LABEL_SIZE, "Add")
                .style(UiFontStyle.MEDIUM)
                .color(valid ? PANEL : Theme.Colors.SECONDARY_DARK)
                .draw();
    }

    // -- input ----------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent mouseButtonEvent, boolean doubleClick) {
        if (mouseButtonEvent.button() != 0) {
            return super.mouseClicked(mouseButtonEvent, doubleClick);
        }

        float mouseX = (float) mouseButtonEvent.x();
        float mouseY = (float) mouseButtonEvent.y();
        float panelX = panelX();
        float panelY = panelY();

        float closeX = panelX + PANEL_WIDTH - PADDING - REMOVE_ICON - 1.0F;
        float closeY = panelY + (HEADER_HEIGHT - REMOVE_ICON) * 0.5F;
        if (MainMenuScreen.insideRect(mouseX, mouseY, closeX - 4.0F, closeY - 4.0F, REMOVE_ICON + 8.0F, REMOVE_ICON + 8.0F)) {
            MainMenuScreen.playClick(this.minecraft);
            onClose();
            return true;
        }

        float footerTop = panelY + PANEL_HEIGHT - FOOTER_HEIGHT;
        float controlY = footerTop + (FOOTER_HEIGHT - INPUT_HEIGHT) * 0.5F;
        float diceX = panelX + PADDING + inputWidth() + CONTROL_GAP;
        if (MainMenuScreen.insideRect(mouseX, mouseY, diceX, controlY, DICE_SIZE, INPUT_HEIGHT)) {
            rollNickname();
            return true;
        }

        float addX = panelX + PANEL_WIDTH - PADDING - ADD_WIDTH;
        if (MainMenuScreen.insideRect(mouseX, mouseY, addX, controlY, ADD_WIDTH, INPUT_HEIGHT)) {
            addTyped();
            return true;
        }

        float listTop = panelY + HEADER_HEIGHT;
        float listHeight = PANEL_HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT;
        if (mouseY >= listTop && mouseY <= listTop + listHeight
                && mouseX >= panelX && mouseX <= panelX + PANEL_WIDTH) {
            List<Alt> alts = AltStore.all();
            int index = (int) Math.floor((mouseY - listTop + this.scroll) / ROW_HEIGHT);
            if (index < 0 || index >= alts.size()) {
                return true;
            }
            Alt alt = alts.get(index);
            float removeX = removeIconX(panelX);
            float rowY = listTop - this.scroll + index * ROW_HEIGHT;
            float removeY = rowY + (ROW_HEIGHT - REMOVE_ICON) * 0.5F;
            if (MainMenuScreen.insideRect(mouseX, mouseY, removeX - 5.0F, removeY - 5.0F,
                    REMOVE_ICON + 10.0F, REMOVE_ICON + 10.0F)) {
                requestRemove(alt);
                return true;
            }

            this.selected = index;
            if (doubleClick) {
                login(alt);
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        this.scroll = MathUtil.clamp(this.scroll - (float) scrollY * SCROLL_STEP, 0.0F, this.scrollMax);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        List<Alt> alts = AltStore.all();
        switch (keyEvent.key()) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                onClose();
                return true;
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (this.input.length() > 0) {
                    this.input.deleteCharAt(this.input.length() - 1);
                }
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (this.input.length() > 0) {
                    addTyped();
                } else if (this.selected >= 0 && this.selected < alts.size()) {
                    login(alts.get(this.selected));
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (this.selected >= 0 && this.selected < alts.size()) {
                    requestRemove(alts.get(this.selected));
                }
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                moveSelection(-1);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                moveSelection(1);
                return true;
            }
            default -> {
                return super.keyPressed(keyEvent);
            }
        }
    }

    @Override
    public boolean charTyped(CharacterEvent characterEvent) {
        int codePoint = characterEvent.codepoint();
        if (this.input.length() >= NAME_MAX_LENGTH || !isNameChar(codePoint)) {
            return true;
        }
        this.input.append((char) codePoint);
        return true;
    }

    // -- actions --------------------------------------------------------------

    private void rollNickname() {
        this.input.setLength(0);
        this.input.append(RandomNick.next(AltStore.names()));
        this.diceFlashNanos = System.nanoTime();
        MainMenuScreen.playClick(this.minecraft);
    }

    private void addTyped() {
        if (!canAdd()) {
            return;
        }
        Alt added = AltStore.add(this.input.toString());
        if (added != null) {
            this.input.setLength(0);
            this.selected = AltStore.all().indexOf(added);
            this.scroll = 0.0F;
            MainMenuScreen.playClick(this.minecraft);
        }
    }

    private void login(Alt alt) {
        AltStore.login(alt);
        MainMenuScreen.playClick(this.minecraft);
    }

    /** First click arms the row, a second one inside the window actually drops it. */
    private void requestRemove(Alt alt) {
        if (this.pendingRemove == alt && System.nanoTime() < this.pendingRemoveUntilNanos) {
            AltStore.remove(alt);
            this.pendingRemove = null;
            this.selected = MathUtil.clamp(this.selected, 0, Math.max(0, AltStore.size() - 1));
            MainMenuScreen.playClick(this.minecraft);
            return;
        }
        this.pendingRemove = alt;
        this.pendingRemoveUntilNanos = System.nanoTime() + REMOVE_CONFIRM_NANOS;
    }

    private void moveSelection(int delta) {
        int size = AltStore.size();
        if (size == 0) {
            return;
        }
        this.selected = MathUtil.clamp(this.selected + delta, 0, size - 1);

        // Keep the moved-to row inside the viewport.
        float listHeight = PANEL_HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT;
        float rowTop = this.selected * ROW_HEIGHT;
        if (rowTop < this.scroll) {
            this.scroll = rowTop;
        } else if (rowTop + ROW_HEIGHT > this.scroll + listHeight) {
            this.scroll = rowTop + ROW_HEIGHT - listHeight;
        }
        this.scroll = MathUtil.clamp(this.scroll, 0.0F, this.scrollMax);
    }

    private boolean canAdd() {
        String typed = this.input.toString();
        return Alt.isValidName(typed) && !AltStore.contains(typed);
    }

    private static boolean isNameChar(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= '0' && codePoint <= '9')
                || codePoint == '_';
    }

    private static boolean caretVisible() {
        return (System.currentTimeMillis() / 500L) % 2L == 0L;
    }

    // -- layout ---------------------------------------------------------------

    private float panelX() {
        return (this.width - PANEL_WIDTH) * 0.5F;
    }

    private float panelY() {
        return (this.height - PANEL_HEIGHT) * 0.5F;
    }

    private static float inputWidth() {
        return PANEL_WIDTH - PADDING * 2.0F - ADD_WIDTH - DICE_SIZE - CONTROL_GAP * 2.0F;
    }

    private static float removeIconX(float panelX) {
        return panelX + PANEL_WIDTH - ROW_PADDING_X - REMOVE_ICON - 4.0F;
    }
}
