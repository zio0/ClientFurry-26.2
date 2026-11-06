package win.furryhook.feature.impl.visual;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.scores.PlayerTeam;
import org.joml.Matrix3x2fStack;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.render.Render2DEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.ModeSetting;
import win.furryhook.feature.setting.MultiSelectSetting;
import win.furryhook.feature.setting.NumberSetting;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.FriendManager;
import win.furryhook.utils.combat.HealthUtil;
import win.furryhook.utils.render.Render3DUtil;
import win.furryhook.utils.render.Render3DUtil.ScreenBounds;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.gui.MsdfFont;
import win.furryhook.utils.render.gui.Render2DUtil;
import win.furryhook.utils.render.gui.TextAlign;
import win.furryhook.utils.render.gui.UiFonts;
import win.furryhook.utils.render.gui.UiFontStyle;
import win.furryhook.utils.render.gui.VanillaTextMetrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layout, target-gating and armor/effects composition mirror divine's 1.16.5 {@code Tags}
 * module; the gradient extraction for team prefix/suffix is furryhook's own and kept as-is.
 */
public final class NameTagsFeature extends Feature {
    private static final float SCALE = 0.8F;
    private static final float NAME_TEXT_SIZE = 11.0F;
    private static final float ENCHANT_TEXT_SIZE = 8.0F;
    private static final float EFFECT_TEXT_SIZE = 9.0F;
    private static final float ITEM_ICON_SIZE = 14.0F;
    private static final float NAME_PART_GAP = 6.0F;
    private static final float HP_GAP = 4.0F;
    private static final float TAG_PAD_X = 4.0F;
    private static final float TAG_PAD_Y = 2.0F;
    private static final float HEAD_TO_TAG_GAP = 3.0F;
    private static final float ARMOR_ROW_GAP = 5.0F;
    private static final float ENCHANT_ROW_GAP = 2.0F;
    private static final float ITEM_ICON_GAP = 2.0F;
    private static final float FEET_GAP = 5.0F;
    private static final float EFFECTS_SIDE_GAP = 6.0F;
    private static final float EFFECT_LINE_GAP = 2.0F;
    private static final double MAX_DISTANCE = 100.0;
    private static final int SHORT_EFFECT_TICKS = 20;

    private static final int TAG_BG = ColorUtil.rgba(0, 0, 0, 120);
    private static final int FRIEND_TAG_BG = ColorUtil.rgba(20, 130, 55, 130);
    private static final int HEALTH_COLOR = ColorUtil.rgb(255, 85, 85);
    private static final int FRIEND_BRACKET_COLOR = ColorUtil.rgb(170, 170, 170);
    private static final int FRIEND_LETTER_COLOR = ColorUtil.rgb(85, 255, 85);
    private static final int ENCHANT_COLOR = ColorUtil.rgb(255, 255, 255);

    /** Weight and tracking every string in a tag is drawn with. */
    private static final UiFontStyle TAG_STYLE = UiFontStyle.MEDIUM;
    private static final int OUTLINE_COLOR = ColorUtil.rgba(0, 0, 0, 190);
    private static final float OUTLINE_THICKNESS = 0.35F;

    private static final String FONT_UI = "Google Sans";
    private static final String FONT_PRODUCT_SANS = "Product Sans";
    private static final String FONT_VANILLA = "Vanilla";

    private static final EquipmentSlot[] EQUIPMENT_ORDER = {
            EquipmentSlot.OFFHAND, EquipmentSlot.FEET, EquipmentSlot.LEGS,
            EquipmentSlot.CHEST, EquipmentSlot.HEAD, EquipmentSlot.MAINHAND
    };

    private record EquipmentEntry(ItemStack stack, List<String> labels) {
    }

    private record Run(String text, int color) {
    }

    private static final Pattern LEGACY_COLOR_CODE = Pattern.compile("§x(§[0-9a-fA-F]){6}|§[0-9a-fA-Fk-oK-Or]");

    public final NumberSetting scale = register(new NumberSetting("Scale", 1.0, 0.95, 1.2, 0.05, "x"));
    public final ModeSetting font = register(new ModeSetting(
            "Font", FONT_UI, FONT_UI, FONT_PRODUCT_SANS, FONT_VANILLA
    ));
    public final BooleanSetting outline = register(new BooleanSetting("Text Outline", true)
            .visibleWhen(() -> !this.font.is(FONT_VANILLA)));
    public final MultiSelectSetting targets = register(new MultiSelectSetting(
            "Targets",
            Set.of("Players", "Items"),
            "Players", "Hostile", "Passive", "Villagers", "Friends", "Naked", "Self", "Items"
    ));
    public final BooleanSetting privilege = register(new BooleanSetting("Privilege", true));
    public final BooleanSetting health = register(new BooleanSetting("Health", true));
    public final BooleanSetting viewArmor = register(new BooleanSetting("Armor", true));
    public final BooleanSetting viewEnchants = register(new BooleanSetting("Enchants", false).visibleWhen(() -> viewArmor.getValue()));
    public final BooleanSetting viewEffects = register(new BooleanSetting("Effects", false));

    public NameTagsFeature() {
        super("NameTags", "Draws styled nametags above players and other entities", FeatureCategory.VISUAL, BindSetting.UNBOUND);
    }

    public static boolean shouldHideVanillaTag() {
        return FeatureManager.INSTANCE.getEnabled(NameTagsFeature.class) != null;
    }

    private boolean vanillaFont() {
        return this.font.is(FONT_VANILLA);
    }

    /**
     * One font owns the vertical metrics for the whole tag, so a Cyrillic rank sitting next to a
     * Latin name never lands a pixel off its neighbour's baseline.
     */
    private MsdfFont referenceFont() {
        return this.font.is(FONT_PRODUCT_SANS)
                ? UiFonts.divineProductSans(TAG_STYLE.weight())
                : UiFonts.googleSans(TAG_STYLE.weight());
    }

    /**
     * Font for one string. Product Sans ships Latin only, so a Cyrillic rank or item name falls
     * through to the UI font rather than rendering as boxes — {@code Render2DUtil} would drop
     * such a string to the vanilla font anyway, and then the width measured here would be wrong.
     */
    private MsdfFont fontFor(String text) {
        if (this.font.is(FONT_PRODUCT_SANS)) {
            MsdfFont productSans = UiFonts.divineProductSans(TAG_STYLE.weight());
            if (productSans.canRender(text)) {
                return productSans;
            }
        }
        return UiFonts.getFontForText(text, TAG_STYLE.weight());
    }

    /**
     * MSDF size whose line height is exactly the one the vanilla font would have produced at
     * {@code size}. The whole tag layout — box height, row gaps, armour offsets — was tuned
     * against vanilla's metrics, so matching the line height keeps every one of those numbers
     * meaning what it meant and swaps nothing but the glyphs.
     */
    private float msdfSize(float size) {
        float lineHeightEm = referenceFont().lineHeight(1.0F);
        return lineHeightEm <= 0.0F ? size : VanillaTextMetrics.lineHeight(size) / lineHeightEm;
    }

    private float textWidth(String text, float size) {
        if (vanillaFont()) {
            return VanillaTextMetrics.width(text, size);
        }
        MsdfFont resolved = fontFor(text);
        if (!resolved.canRender(text)) {
            return VanillaTextMetrics.width(text, size);
        }
        float drawSize = msdfSize(size);
        return resolved.measureWidth(text, drawSize, drawSize * TAG_STYLE.letterSpacingEm());
    }

    /** Line height is vanilla's in both paths by construction; see {@link #msdfSize(float)}. */
    private float lineHeight(float size) {
        return VanillaTextMetrics.lineHeight(size);
    }

    private float centeredTextY(float centerY, float size) {
        return vanillaFont()
                ? VanillaTextMetrics.centeredTextY(centerY, size)
                : referenceFont().centeredTextY(centerY, msdfSize(size));
    }

    private void drawText(float x, float y, float size, String text, int color, TextAlign align) {
        if (vanillaFont()) {
            Render2DUtil.text(x, y, size, text)
                    .vanilla()
                    .align(align)
                    .color(color)
                    .draw();
            return;
        }

        Render2DUtil.TextBuilder builder = Render2DUtil.text(x, y, msdfSize(size), text)
                .font(fontFor(text))
                .style(TAG_STYLE)
                .align(align)
                .color(color);
        if (this.outline.getValue()) {
            builder.outline(OUTLINE_COLOR, OUTLINE_THICKNESS);
        }
        builder.draw();
    }

    private void drawText(float x, float y, float size, String text, int color) {
        drawText(x, y, size, text, color, TextAlign.LEFT);
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        Minecraft mc = event.getClient();
        if (mc == null || mc.level == null || mc.player == null) {
            return;
        }

        float tickDelta = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float unit = (float) (SCALE * this.scale.getValue() / mc.getWindow().getGuiScale());
        double maxDistanceSqr = MAX_DISTANCE * MAX_DISTANCE;

        // The target flags are string lookups into a MultiSelect set; resolve them once per frame
        // instead of once per entity, and bail out entirely when nothing is selected.
        captureFrameTargets(mc);
        if (!frameAnyTarget) {
            return;
        }

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.isRemoved()) {
                continue;
            }
            if (mc.player.distanceToSqr(entity) > maxDistanceSqr) {
                continue;
            }

            if (entity instanceof ItemEntity itemEntity) {
                if (!frameItems) {
                    continue;
                }
                ScreenBounds bounds = computeBounds(mc, itemEntity, tickDelta);
                if (bounds == null) {
                    continue;
                }
                drawItemTag(event, itemEntity, bounds, unit);
                continue;
            }

            if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                continue;
            }
            if (!isTarget(mc, living)) {
                continue;
            }

            ScreenBounds bounds = computeBounds(mc, living, tickDelta);
            if (bounds == null) {
                continue;
            }
            drawEntityTag(event, living, bounds, unit);
        }
    }

    private boolean frameSelf;
    private boolean frameFriends;
    private boolean frameNaked;
    private boolean framePlayers;
    private boolean frameVillagers;
    private boolean framePassive;
    private boolean frameHostile;
    private boolean frameItems;
    private boolean frameFirstPerson;
    private boolean frameAnyTarget;

    private void captureFrameTargets(Minecraft mc) {
        frameSelf = this.targets.isSelected("Self");
        frameFriends = this.targets.isSelected("Friends");
        frameNaked = this.targets.isSelected("Naked");
        framePlayers = this.targets.isSelected("Players");
        frameVillagers = this.targets.isSelected("Villagers");
        framePassive = this.targets.isSelected("Passive");
        frameHostile = this.targets.isSelected("Hostile");
        frameItems = this.targets.isSelected("Items");
        frameFirstPerson = mc.options.getCameraType().isFirstPerson();
        frameAnyTarget = frameSelf || frameFriends || frameNaked || framePlayers
                || frameVillagers || framePassive || frameHostile || frameItems;
    }

    private boolean isTarget(Minecraft mc, LivingEntity entity) {
        if (entity == mc.player) {
            return frameSelf && !frameFirstPerson;
        }
        if (entity instanceof Player player) {
            if (FriendManager.INSTANCE.isFriend(player.getGameProfile().name())) {
                return frameFriends;
            }
            if (player.getArmorValue() <= 0.0F) {
                return frameNaked;
            }
            return framePlayers;
        }
        if (entity instanceof Villager) {
            return frameVillagers;
        }
        if (entity instanceof Animal) {
            return framePassive;
        }
        if (entity instanceof Mob) {
            return frameHostile;
        }
        return false;
    }

    private ScreenBounds computeBounds(Minecraft mc, Entity entity, float tickDelta) {
        double x = Render3DUtil.lerpX(entity, tickDelta);
        double y = Render3DUtil.lerpY(entity, tickDelta);
        double z = Render3DUtil.lerpZ(entity, tickDelta);
        double halfWidth = entity.getBbWidth() / 2.0D;
        double height = entity.getBbHeight();
        return Render3DUtil.projectBoxBounds(
                mc,
                x - halfWidth, y, z - halfWidth,
                x + halfWidth, y + height, z + halfWidth
        );
    }

    private void drawItemTag(Render2DEvent event, ItemEntity itemEntity, ScreenBounds bounds, float unit) {
        ItemStack stack = itemEntity.getItem();
        float nameSize = NAME_TEXT_SIZE * unit;

        Integer rarityColor = rarityColor(stack.getRarity());
        int fallbackColor = rarityColor != null ? rarityColor : Theme.Colors.TEXT_TITLE;
        List<Run> runs = extractRuns(stack.getHoverName(), fallbackColor);
        int count = stack.getCount();
        if (count > 1) {
            runs.add(new Run(" x" + count, fallbackColor));
        }

        float textWidth = runsWidth(runs, nameSize);
        float fontHeight = lineHeight(nameSize);
        float centerX = (bounds.minX() + bounds.maxX()) / 2.0F;
        float tagY = bounds.minY() - fontHeight - HEAD_TO_TAG_GAP * unit;
        float tagRectY = tagY - TAG_PAD_Y * unit;
        float tagRectHeight = fontHeight + 2.0F * TAG_PAD_Y * unit;
        float tagWidth = textWidth + 2.0F * TAG_PAD_X * unit;
        float tagRectX = centerX - tagWidth / 2.0F;

        Render2DUtil.rect(tagRectX, tagRectY, tagWidth, tagRectHeight)
                .color(TAG_BG)
                .draw();
        drawRuns(runs, centerX - textWidth / 2.0F, centeredTextY(tagRectY + tagRectHeight / 2.0F, nameSize), nameSize);
    }

    private void drawEntityTag(Render2DEvent event, LivingEntity entity, ScreenBounds bounds, float unit) {
        Minecraft mc = event.getClient();
        float nameSize = NAME_TEXT_SIZE * unit;
        float namePartGap = NAME_PART_GAP * unit;

        boolean friend = entity instanceof Player player && FriendManager.INSTANCE.isFriend(player.getGameProfile().name());

        List<List<Run>> gappedGroups = new ArrayList<>(3);
        if (this.privilege.getValue()) {
            PlayerTeam team = entity.getTeam();
            addNamePart(gappedGroups, team == null ? null : team.getPlayerPrefix());
        }
        String rawName = entity instanceof Player player ? player.getGameProfile().name() : entity.getName().getString();
        gappedGroups.add(List.of(new Run(rawName, Theme.Colors.TEXT_TITLE)));
        if (this.privilege.getValue()) {
            PlayerTeam team = entity.getTeam();
            addNamePart(gappedGroups, team == null ? null : team.getPlayerSuffix());
        }

        List<Run> tightRuns = new ArrayList<>(3);
        if (this.health.getValue()) {
            int hp = Math.max(0, Math.round(HealthUtil.getHealth(entity)));
            tightRuns.add(new Run(hp + "HP", HEALTH_COLOR));
        }
        if (friend) {
            tightRuns.add(new Run(" [", FRIEND_BRACKET_COLOR));
            tightRuns.add(new Run("F", FRIEND_LETTER_COLOR));
            tightRuns.add(new Run("]", FRIEND_BRACKET_COLOR));
        }

        float nameWidth = 0.0F;
        for (int i = 0; i < gappedGroups.size(); i++) {
            if (i > 0) {
                nameWidth += namePartGap;
            }
            for (Run run : gappedGroups.get(i)) {
                nameWidth += textWidth(run.text(), nameSize);
            }
        }
        if (!tightRuns.isEmpty()) {
            nameWidth += HP_GAP * unit;
        }
        for (Run run : tightRuns) {
            nameWidth += textWidth(run.text(), nameSize);
        }

        float fontHeight = lineHeight(nameSize);
        float centerX = (bounds.minX() + bounds.maxX()) / 2.0F;
        float tagY = bounds.minY() - fontHeight - HEAD_TO_TAG_GAP * unit;
        float tagRectY = tagY - TAG_PAD_Y * unit;
        float tagRectHeight = fontHeight + 2.0F * TAG_PAD_Y * unit;
        float tagWidth = nameWidth + 2.0F * TAG_PAD_X * unit;
        float tagRectX = centerX - tagWidth / 2.0F;
        float nameY = centeredTextY(tagRectY + tagRectHeight / 2.0F, nameSize);

        Render2DUtil.rect(tagRectX, tagRectY, tagWidth, tagRectHeight)
                .color(friend ? FRIEND_TAG_BG : TAG_BG)
                .draw();

        float cursorX = centerX - nameWidth / 2.0F;
        for (int i = 0; i < gappedGroups.size(); i++) {
            if (i > 0) {
                cursorX += namePartGap;
            }
            for (Run run : gappedGroups.get(i)) {
                drawText(cursorX, nameY, nameSize, run.text(), run.color());
                cursorX += textWidth(run.text(), nameSize);
            }
        }
        if (!tightRuns.isEmpty()) {
            cursorX += HP_GAP * unit;
        }
        for (Run run : tightRuns) {
            drawText(cursorX, nameY, nameSize, run.text(), run.color());
            cursorX += textWidth(run.text(), nameSize);
        }

        if (this.viewArmor.getValue()) {
            drawArmorRow(event, entity, centerX, tagRectY, unit);
        }

        if (this.viewEffects.getValue()) {
            drawEffects(entity, bounds, unit);
        }
    }

    private void drawArmorRow(Render2DEvent event, LivingEntity entity, float centerX, float tagRectY, float unit) {
        List<EquipmentEntry> equipment = new ArrayList<>(EQUIPMENT_ORDER.length);
        for (EquipmentSlot slot : EQUIPMENT_ORDER) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                equipment.add(new EquipmentEntry(stack, this.viewEnchants.getValue() ? enchantLabels(stack) : List.of()));
            }
        }
        if (equipment.isEmpty()) {
            return;
        }

        float itemSize = ITEM_ICON_SIZE * unit;
        float itemGap = ITEM_ICON_GAP * unit;
        float enchantTextSize = ENCHANT_TEXT_SIZE * unit;
        float enchantLineHeight = lineHeight(enchantTextSize);

        float iconTop = tagRectY - ARMOR_ROW_GAP * unit - itemSize;
        float enchantBlockBottom = iconTop - ENCHANT_ROW_GAP * unit;

        // Column width follows whichever is wider: the icon, or its enchant labels — a label
        // rendered at the vanilla font's floor size can outgrow a tightly packed icon slot.
        float[] columnWidths = new float[equipment.size()];
        float rowWidth = 0.0F;
        for (int i = 0; i < equipment.size(); i++) {
            float labelWidth = 0.0F;
            for (String label : equipment.get(i).labels()) {
                labelWidth = Math.max(labelWidth, textWidth(label, enchantTextSize));
            }
            columnWidths[i] = Math.max(itemSize, labelWidth);
            rowWidth += columnWidths[i];
        }
        rowWidth += (equipment.size() - 1) * itemGap;
        float columnX = centerX - rowWidth / 2.0F;

        Minecraft mc = event.getClient();
        Matrix3x2fStack pose = event.getGuiGraphicsExtractor().pose();
        float guiScale = (float) mc.getWindow().getGuiScale();
        float itemScale = itemSize / 16.0F;

        for (int i = 0; i < equipment.size(); i++) {
            EquipmentEntry entry = equipment.get(i);
            float columnWidth = columnWidths[i];
            float columnCenterX = columnX + columnWidth / 2.0F;
            List<String> labels = entry.labels();
            float labelY = enchantBlockBottom - labels.size() * enchantLineHeight;
            for (String label : labels) {
                drawText(columnCenterX, labelY, enchantTextSize, label, ENCHANT_COLOR, TextAlign.CENTER);
                labelY += enchantLineHeight;
            }

            Render2DUtil.flush();
            float itemX = Math.round((columnCenterX - itemSize / 2.0F) * guiScale) / guiScale;
            float itemY = Math.round(iconTop * guiScale) / guiScale;
            pose.pushMatrix();
            pose.translate(itemX, itemY);
            pose.scale(itemScale);
            event.getGuiGraphicsExtractor().item(entry.stack(), 0, 0);
            pose.popMatrix();

            columnX += columnWidth + itemGap;
        }
    }

    private void drawEffects(LivingEntity entity, ScreenBounds bounds, float unit) {
        float textSize = EFFECT_TEXT_SIZE * unit;

        List<EffectLine> lines = new ArrayList<>();
        for (MobEffectInstance effectInstance : entity.getActiveEffects()) {
            int durationTicks = effectInstance.getDuration();
            if (!effectInstance.isInfiniteDuration() && durationTicks <= SHORT_EFFECT_TICKS) {
                continue;
            }

            int packed = effectInstance.getEffect().value().getColor();
            int color = 0xFF000000 | (packed & 0xFFFFFF);

            String name = effectInstance.getEffect().value().getDisplayName().getString();
            int amplifier = effectInstance.getAmplifier() + 1;
            StringBuilder text = new StringBuilder(name);
            if (amplifier > 1) {
                text.append(' ').append(roman(amplifier));
            }
            text.append(' ').append(effectInstance.isInfiniteDuration() ? "∞" : formatDuration(durationTicks));

            float width = textWidth(text.toString(), textSize);
            lines.add(new EffectLine(text.toString(), width, color));
        }
        if (lines.isEmpty()) {
            return;
        }

        lines.sort(Comparator.comparingDouble(EffectLine::width));

        float lineStep = (lineHeight(textSize) + EFFECT_LINE_GAP * unit);
        float x = bounds.maxX() + EFFECTS_SIDE_GAP * unit;
        float totalHeight = lineStep * lines.size() - EFFECT_LINE_GAP * unit;
        float y = (bounds.minY() + bounds.maxY()) / 2.0F - totalHeight / 2.0F;
        for (EffectLine line : lines) {
            drawText(x, y, textSize, line.text(), line.color());
            y += lineStep;
        }
    }

    private record EffectLine(String text, float width, int color) {
    }

    /**
     * Reads the prefix/suffix component's own per-run colors instead of picking one color
     * and fabricating a fade — this is how the underlying text is actually styled server-side
     * (including per-letter legacy/hex gradient codes ranks are commonly tagged with), so the
     * "gradient" shown here is whatever gradient the source really carries, not an invented one.
     */
    private static void addNamePart(List<List<Run>> groups, Component prefixOrSuffix) {
        if (prefixOrSuffix == null) {
            return;
        }
        List<Run> runs = extractRuns(prefixOrSuffix, Theme.getAccent());

        List<Run> cleaned = new ArrayList<>(runs.size());
        for (int i = 0; i < runs.size(); i++) {
            Run run = runs.get(i);
            String trimmed = run.text();
            if (i == 0) {
                trimmed = trimmed.stripLeading();
            }
            if (i == runs.size() - 1) {
                trimmed = trimmed.stripTrailing();
            }
            if (!trimmed.isEmpty()) {
                cleaned.add(new Run(trimmed, run.color()));
            }
        }
        if (!cleaned.isEmpty()) {
            groups.add(cleaned);
        }
    }

    /**
     * Reads a component's own per-run colors (style colors plus embedded legacy/hex codes)
     * instead of flattening it to one color — this is how servers actually paint gradient
     * ranks and custom item names, so the runs returned are whatever the source really carries.
     */
    private static List<Run> extractRuns(Component component, int fallbackColor) {
        List<Run> runs = new ArrayList<>();
        component.visit((style, text) -> {
            TextColor styleColor = style.getColor();
            int baseColor = styleColor != null ? (0xFF000000 | styleColor.getValue()) : fallbackColor;
            splitLegacyRuns(text, baseColor, runs);
            return Optional.<Void>empty();
        }, Style.EMPTY);
        return runs;
    }

    private float runsWidth(List<Run> runs, float size) {
        float width = 0.0F;
        for (Run run : runs) {
            width += textWidth(run.text(), size);
        }
        return width;
    }

    private float drawRuns(List<Run> runs, float x, float y, float size) {
        float cursorX = x;
        for (Run run : runs) {
            drawText(cursorX, y, size, run.text(), run.color());
            cursorX += textWidth(run.text(), size);
        }
        return cursorX;
    }

    /** Splits raw text on embedded §-legacy and §x-hex color codes, tracking the running color. */
    private static void splitLegacyRuns(String text, int initialColor, List<Run> out) {
        if (text.isEmpty()) {
            return;
        }
        // Runs a regex over every name of every tagged entity every frame; almost none of them
        // carry a legacy code, so scan for the marker first and skip building a Matcher at all.
        if (text.indexOf('§') < 0) {
            out.add(new Run(text, initialColor));
            return;
        }

        Matcher matcher = LEGACY_COLOR_CODE.matcher(text);
        int last = 0;
        int currentColor = initialColor;
        while (matcher.find()) {
            if (matcher.start() > last) {
                out.add(new Run(text.substring(last, matcher.start()), currentColor));
            }
            Integer parsed = parseColorCode(matcher.group());
            if (parsed != null) {
                currentColor = parsed;
            }
            last = matcher.end();
        }
        if (last < text.length()) {
            out.add(new Run(text.substring(last), currentColor));
        }
    }

    private static Integer parseColorCode(String code) {
        if (code.startsWith("§x") || code.startsWith("§X")) {
            StringBuilder hex = new StringBuilder(6);
            for (int i = 2; i < code.length(); i += 2) {
                hex.append(code.charAt(i + 1));
            }
            try {
                return 0xFF000000 | Integer.parseInt(hex.toString(), 16);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        ChatFormatting formatting = ChatFormatting.getByCode(code.charAt(1));
        if (formatting == null) {
            return null;
        }
        TextColor color = TextColor.fromLegacyFormat(formatting);
        return color != null ? (0xFF000000 | color.getValue()) : null;
    }

    private static List<String> enchantLabels(ItemStack stack) {
        List<String> labels = new ArrayList<>();
        for (var entry : stack.getEnchantments().entrySet()) {
            String description = entry.getKey().value().description().getString();
            if (description.isBlank()) {
                continue;
            }
            String firstWord = description.trim().split("\\s+")[0];
            String abbreviation = abbreviate(firstWord);
            if (abbreviation.isEmpty()) {
                continue;
            }
            int level = entry.getIntValue();
            labels.add(level > 1 ? abbreviation + level : abbreviation);
        }
        return labels;
    }

    private static String abbreviate(String word) {
        StringBuilder letters = new StringBuilder(2);
        for (int i = 0; i < word.length() && letters.length() < 2; i++) {
            char character = word.charAt(i);
            if (Character.isLetter(character)) {
                letters.append(character);
            }
        }
        if (letters.isEmpty()) {
            return "";
        }
        String result = letters.toString();
        return result.length() > 1
                ? Character.toUpperCase(result.charAt(0)) + String.valueOf(Character.toLowerCase(result.charAt(1)))
                : String.valueOf(Character.toUpperCase(result.charAt(0)));
    }

    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private static String roman(int amplifier) {
        return amplifier > 0 && amplifier < ROMAN.length ? ROMAN[amplifier] : String.valueOf(amplifier);
    }

    private static String formatDuration(int ticks) {
        int seconds = Math.max(0, ticks) / 20;
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    private static Integer rarityColor(Rarity rarity) {
        TextColor color = TextColor.fromLegacyFormat(rarity.color());
        return color != null ? (0xFF000000 | color.getValue()) : null;
    }

}
