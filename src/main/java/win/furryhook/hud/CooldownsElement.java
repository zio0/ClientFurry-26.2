package win.furryhook.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix3x2fStack;
import win.furryhook.context.RenderContext;
import win.furryhook.mixin.accessor.ItemCooldownInstanceAccessor;
import win.furryhook.mixin.accessor.ItemCooldownsAccessor;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.math.AnimatedValue;
import win.furryhook.utils.math.MathUtil;
import win.furryhook.utils.render.Textures;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.gui.MsdfFont;
import win.furryhook.utils.render.gui.Render2DUtil;
import win.furryhook.utils.render.gui.UiFontStyle;
import win.furryhook.utils.render.gui.UiFonts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Item-cooldown pip list, ported from divine's CooldownsHudElement. Shows every active item
 * cooldown (ender pearls, totems, etc.) as a row with the item icon, name and remaining time,
 * animating rows in/out via {@link HudRowAnimation} the same way divine did.
 */
public final class CooldownsElement extends HudElement {
    private static final float MIN_WIDTH = 96.0F;
    private static final float HEADER_HEIGHT = 19.0F;
    private static final float BODY_PAD_TOP = 6.0F;
    private static final float BODY_PAD_BOTTOM = 6.0F;
    private static final float ROW_HEIGHT = 10.0F;
    private static final float ROW_GAP = 3.0F;
    private static final float TEXT_SIZE = 8.0F;
    private static final float TITLE_SIZE = 8.5F;
    private static final float ICON_SIZE = 9.0F;
    private static final float HEADER_ICON_SIZE = 10.0F;
    private static final float PAD_X = 7.0F;
    private static final float HEADER_RADIUS = 5.0F;
    private static final float BODY_RADIUS = 8.0F;
    private static final int HEADER_BG = ColorUtil.rgb(30, 30, 30);
    private static final int BODY_BG = ColorUtil.rgba(19, 20, 22, 179);
    private static final Identifier CHAT_PREVIEW_ID = Identifier.parse("furryhook:cooldown_preview");

    private final AnimatedValue visibilityAnim = new AnimatedValue();
    private final AnimatedValue widthAnim = new AnimatedValue();
    private final AnimatedValue heightAnim = new AnimatedValue();
    private final Map<Identifier, RowState> rowStates = new LinkedHashMap<>();
    private final Map<Identifier, Item> resolvedItems = new HashMap<>();

    private float visible;

    public CooldownsElement() {
        super("cooldowns", "Cooldowns");
    }

    @Override
    protected float defaultX(float unit) {
        return 12.0F * unit;
    }

    @Override
    protected float defaultY(float unit) {
        return 94.0F * unit;
    }

    @Override
    protected void layout(Minecraft mc, float unit) {
        long now = System.nanoTime();
        boolean chatOpen = mc.gui.screen() instanceof ChatScreen;
        MsdfFont font = UiFonts.sfPro(UiFontStyle.MEDIUM.weight());
        List<RowVisual> entries = collectEntries(mc, font, now, chatOpen);

        float targetWidth = MIN_WIDTH;
        for (RowVisual visual : entries) {
            CooldownEntry entry = visual.entry();
            targetWidth = Math.max(targetWidth, PAD_X * 2.0F + ICON_SIZE + 3.0F
                    + font.measureWidth(entry.name(), TEXT_SIZE, 0.0F)
                    + 12.0F + font.measureWidth(entry.time(), TEXT_SIZE, 0.0F));
        }

        float rowStride = ROW_HEIGHT + ROW_GAP;
        float animatedRowsHeight = 0.0F;
        for (RowVisual visual : entries) {
            animatedRowsHeight += rowStride * visual.progress();
        }
        float listPresence = MathUtil.clamp01(animatedRowsHeight / Math.max(0.001F, rowStride));
        float bodyRowsHeight = Math.max(0.0F, animatedRowsHeight - ROW_GAP * listPresence);
        float bodyHeight = (BODY_PAD_TOP + BODY_PAD_BOTTOM) * listPresence + bodyRowsHeight;
        float targetHeight = HEADER_HEIGHT + (bodyHeight > 0.5F ? bodyHeight : 0.0F);

        boolean shouldShow = alwaysShow() || !this.rowStates.isEmpty() || (chatOpen && showcase(mc));
        this.visible = this.visibilityAnim.update(shouldShow ? 1.0F : 0.0F, 0.08F, now);
        float width = this.widthAnim.update(targetWidth, 0.09F, now);
        float height = this.heightAnim.update(targetHeight, 0.09F, now);

        this.entries = entries;
        if (this.visible <= 0.01F) {
            this.width = 0.0F;
            this.height = 0.0F;
            return;
        }
        this.width = width * unit;
        this.height = height * unit;
    }

    private List<RowVisual> entries = List.of();

    @Override
    protected void draw(Minecraft mc, float unit) {
        if (this.visible <= 0.01F) {
            return;
        }
        GuiGraphicsExtractor extractor = RenderContext.currentGuiGraphicsExtractor();
        if (extractor == null) {
            return;
        }

        float x = this.x;
        float y = this.y;
        float width = this.width;

        float panelAlpha = Math.min(1.0F, this.visible / 0.34F);
        float headerHeight = HEADER_HEIGHT * unit;
        boolean hasBody = this.height - headerHeight > 0.5F;

        if (hasBody) {
            Render2DUtil.rect(x, y, width, this.height)
                    .color(ColorUtil.multiplyAlpha(BODY_BG, panelAlpha))
                    .radius(BODY_RADIUS * unit)
                    .border(Math.max(0.5F, 0.5F * unit),
                            ColorUtil.multiplyAlpha(Theme.Colors.OUTLINES_SMALL, panelAlpha))
                    .blur(6.0F * unit, panelAlpha)
                    .draw();
        }
        Render2DUtil.rect(x, y, width, headerHeight)
                .color(ColorUtil.multiplyAlpha(HEADER_BG, panelAlpha))
                .radius(HEADER_RADIUS * unit)
                .draw();

        float barWidth = 1.3F * unit;
        float barHeight = headerHeight * 0.5F;
        Render2DUtil.rect(x, y + (headerHeight - barHeight) * 0.5F, barWidth, barHeight)
                .color(ColorUtil.multiplyAlpha(Theme.getAccent(), this.visible))
                .radius(0.0F, barWidth * 0.5F, barWidth * 0.5F, 0.0F)
                .draw();

        MsdfFont font = UiFonts.sfPro(400);
        MsdfFont titleFont = UiFonts.sfPro(600);
        float titleSize = TITLE_SIZE * unit;
        float titleCenterY = y + headerHeight * 0.5F;
        Render2DUtil.text(x + PAD_X * unit, titleFont.centeredTextY(titleCenterY, titleSize), titleSize, "Cooldowns")
                .font(titleFont)
                .style(UiFontStyle.SEMIBOLD)
                .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TITLE, this.visible))
                .draw();

        float headerIconSize = HEADER_ICON_SIZE * unit;
        Render2DUtil.texture(x + width - PAD_X * unit - headerIconSize,
                        titleCenterY - headerIconSize / 2.0F,
                        headerIconSize, headerIconSize, Textures.Hud.ICON_TIME)
                .color(ColorUtil.multiplyAlpha(Theme.getAccent(), this.visible))
                .draw();

        if (!hasBody) {
            return;
        }

        float textSize = TEXT_SIZE * unit;
        float iconSize = ICON_SIZE * unit;
        float cursorY = y + headerHeight + BODY_PAD_TOP * unit;

        Render2DUtil.flush();
        for (RowVisual rowVisual : this.entries) {
            CooldownEntry entry = rowVisual.entry();
            float progress = MathUtil.clamp01(rowVisual.progress());
            float rowAlpha = this.visible * progress;
            float rowCenterY = cursorY + ROW_HEIGHT * 0.5F * unit;

            drawItem(mc, extractor, entry.stack(), x + PAD_X * unit, rowCenterY - iconSize / 2.0F, iconSize);
            Render2DUtil.text(x + PAD_X * unit + iconSize + 4.0F * unit, font.centeredTextY(rowCenterY, textSize), textSize, entry.name())
                    .font(font)
                    .style(UiFontStyle.REGULAR)
                    .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TITLE, rowAlpha))
                    .draw();
            float timeWidth = font.measureWidth(entry.time(), textSize, 0.0F);
            Render2DUtil.text(x + width - PAD_X * unit - timeWidth, font.centeredTextY(rowCenterY, textSize), textSize, entry.time())
                    .font(font)
                    .style(UiFontStyle.REGULAR)
                    .color(ColorUtil.multiplyAlpha(Theme.getAccent(), rowAlpha))
                    .draw();
            cursorY += (ROW_HEIGHT + ROW_GAP) * unit * progress;
        }
    }

    private static void drawItem(Minecraft mc, GuiGraphicsExtractor extractor, ItemStack stack, float x, float y, float size) {
        if (stack.isEmpty()) {
            return;
        }
        float guiScale = (float) mc.getWindow().getGuiScale();
        float itemX = Math.round(x * guiScale) / guiScale;
        float itemY = Math.round(y * guiScale) / guiScale;
        Matrix3x2fStack pose = extractor.pose();
        pose.pushMatrix();
        pose.translate(itemX, itemY);
        pose.scale(size / 16.0F);
        extractor.item(mc.player, stack, 0, 0, 0);
        pose.popMatrix();
    }

    private List<RowVisual> collectEntries(Minecraft mc, MsdfFont font, long now, boolean chatOpen) {
        Set<Identifier> liveIds = new HashSet<>();
        List<RowState> liveStates = new ArrayList<>();

        if (mc.player != null) {
            ItemCooldowns manager = mc.player.getCooldowns();
            ItemCooldownsAccessor accessor = (ItemCooldownsAccessor) manager;
            int currentTick = accessor.furryhook$getTickCount();

            for (Map.Entry<Identifier, Object> raw : accessor.furryhook$getCooldowns().entrySet()) {
                if (!(raw.getValue() instanceof ItemCooldownInstanceAccessor cooldown)) {
                    continue;
                }
                float remainingTicks = cooldown.furryhook$getEndTime() - currentTick;
                if (remainingTicks <= 0.0F) {
                    continue;
                }

                Identifier id = raw.getKey();
                Item item = resolveItem(id, manager, mc);
                ItemStack stack = new ItemStack(item);
                float seconds = remainingTicks / 20.0F;
                int durationTicks = Math.max(1, cooldown.furryhook$getEndTime() - cooldown.furryhook$getStartTime());
                float progress = MathUtil.clamp(remainingTicks / durationTicks, 0.0F, 1.0F);
                String name = item == Items.AIR
                        ? id.getPath().replace('_', ' ')
                        : stack.getHoverName().getString().toLowerCase(Locale.ROOT);

                RowState state = this.rowStates.computeIfAbsent(id, RowState::new);
                state.entry = new CooldownEntry(stack, name, formatSeconds(seconds), seconds, progress);
                state.seen = true;
                liveIds.add(id);
                liveStates.add(state);
            }
        }

        liveStates.sort(Comparator.comparingDouble((RowState state) -> state.entry.remaining()).reversed()
                .thenComparing(state -> state.id.toString()));
        for (int i = 0; i < liveStates.size(); i++) {
            liveStates.get(i).targetIndex = i;
        }

        if (liveStates.isEmpty() && chatOpen && showcase(mc)) {
            RowState preview = this.rowStates.computeIfAbsent(CHAT_PREVIEW_ID, RowState::new);
            preview.entry = new CooldownEntry(new ItemStack(Items.ENDER_PEARL), "ender pearl", "3.2s", 3.2F, 0.64F);
            preview.seen = true;
            preview.targetIndex = 0;
            liveIds.add(CHAT_PREVIEW_ID);
        }

        List<RowVisual> visuals = new ArrayList<>();
        List<Identifier> remove = new ArrayList<>();
        for (Map.Entry<Identifier, RowState> mapEntry : this.rowStates.entrySet()) {
            RowState state = mapEntry.getValue();
            boolean seen = liveIds.contains(mapEntry.getKey());
            state.seen = seen;
            float animation = Math.max(0.0F, state.animation.update(seen, now));
            if (!seen && state.animation.isHidden()) {
                remove.add(mapEntry.getKey());
            } else if (state.entry != null && animation > 0.001F) {
                visuals.add(new RowVisual(state.entry, state.targetIndex, animation, state.id));
            }
        }
        remove.forEach(this.rowStates::remove);
        visuals.sort(Comparator.comparingInt(RowVisual::targetIndex).thenComparing(visual -> visual.id().toString()));
        return visuals;
    }

    private Item resolveItem(Identifier group, ItemCooldowns manager, Minecraft mc) {
        Item cached = this.resolvedItems.get(group);
        if (cached != null) {
            return cached;
        }

        if (BuiltInRegistries.ITEM.containsKey(group)) {
            Item direct = BuiltInRegistries.ITEM.getValue(group);
            if (direct != Items.AIR) {
                this.resolvedItems.put(group, direct);
                return direct;
            }
        }

        for (int slot = 0; slot < mc.player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && group.equals(manager.getCooldownGroup(stack))) {
                this.resolvedItems.put(group, stack.getItem());
                return stack.getItem();
            }
        }

        for (Item item : BuiltInRegistries.ITEM) {
            if (item != Items.AIR && group.equals(manager.getCooldownGroup(new ItemStack(item)))) {
                this.resolvedItems.put(group, item);
                return item;
            }
        }

        this.resolvedItems.put(group, Items.AIR);
        return Items.AIR;
    }

    private static String formatSeconds(float seconds) {
        return String.format(Locale.ROOT, "%.1fs", Math.max(0.0F, seconds));
    }

    private static final class RowState {
        private final Identifier id;
        private final HudRowAnimation animation = new HudRowAnimation();
        private CooldownEntry entry;
        private int targetIndex;
        private boolean seen;

        private RowState(Identifier id) {
            this.id = id;
        }
    }

    private record CooldownEntry(ItemStack stack, String name, String time, float remaining, float cooldownProgress) {
    }

    private record RowVisual(CooldownEntry entry, int targetIndex, float progress, Identifier id) {
    }
}
