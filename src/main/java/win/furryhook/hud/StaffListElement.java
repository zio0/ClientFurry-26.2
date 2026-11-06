package win.furryhook.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.scores.PlayerTeam;
import win.furryhook.utils.ColorUtil;
import win.furryhook.utils.math.AnimatedValue;
import win.furryhook.utils.math.MathUtil;
import win.furryhook.utils.render.Textures;
import win.furryhook.utils.render.Theme;
import win.furryhook.utils.render.gui.MsdfFont;
import win.furryhook.utils.render.gui.PlayerHead;
import win.furryhook.utils.render.gui.Render2DUtil;
import win.furryhook.utils.render.gui.UiFontStyle;
import win.furryhook.utils.render.gui.UiFonts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Staff/role roster panel, ported from divine's StaffListHudElement. Divine also merges in a
 * manually-configured "staff name" list from its command system; furryhook has no equivalent
 * command, so this element only sources entries from scoreboard team prefixes (admin/mod/helper/
 * staff/owner/curator, English and Russian) the same way divine's second source worked.
 */
public final class StaffListElement extends HudElement {
    private static final float MIN_WIDTH = 108.0F;
    private static final float HEADER_HEIGHT = 19.0F;
    private static final float BODY_PAD_TOP = 6.0F;
    private static final float BODY_PAD_BOTTOM = 6.0F;
    private static final float ROW_HEIGHT = 9.0F;
    private static final float ROW_GAP = 3.0F;
    private static final float STATUS_SIZE = 5.4F;
    private static final float NAME_SIZE = 7.2F;
    private static final float TITLE_SIZE = 8.5F;
    private static final float HEAD_SIZE = 7.5F;
    private static final float HEAD_GAP = 4.0F;
    private static final float HEADER_ICON_SIZE = 10.0F;
    private static final float PAD_X = 7.0F;
    private static final float HEADER_RADIUS = 5.0F;
    private static final float BODY_RADIUS = 8.0F;
    private static final int HEADER_BG = ColorUtil.rgb(30, 30, 30);
    private static final int BODY_BG = ColorUtil.rgba(19, 20, 22, 179);
    private static final long REFRESH_INTERVAL_MS = 400L;

    private static final int STATUS_ONLINE = ColorUtil.rgba(92, 211, 128, 255);
    private static final int STATUS_VANISH = ColorUtil.rgba(238, 171, 76, 255);

    // Always excluded from the roster regardless of team prefix.
    private static final Set<String> EXCLUDED_NAMES = Set.of("d.helper");

    private final AnimatedValue visibilityAnim = new AnimatedValue();
    private final AnimatedValue widthAnim = new AnimatedValue();
    private final AnimatedValue heightAnim = new AnimatedValue();
    private final Map<String, RowState> rowStates = new HashMap<>();

    private List<StaffEntry> cachedStaff = List.of();
    private Object cachedWorld;
    private long nextRefreshAtMs;
    private List<RowVisual> visuals = List.of();
    private float visible;

    public StaffListElement() {
        super("staff_list", "StaffList");
    }

    @Override
    protected float defaultX(float unit) {
        return 104.0F * unit;
    }

    @Override
    protected float defaultY(float unit) {
        return 42.0F * unit;
    }

    @Override
    protected void layout(Minecraft mc, float unit) {
        long now = System.nanoTime();
        boolean chatOpen = mc.gui.screen() instanceof ChatScreen;
        List<StaffEntry> staff = getCachedStaff(mc, System.currentTimeMillis());

        for (RowState state : this.rowStates.values()) {
            state.seen = false;
        }
        for (int index = 0; index < staff.size(); index++) {
            StaffEntry entry = staff.get(index);
            String key = entry.name().toLowerCase(Locale.ROOT);
            RowState state = this.rowStates.get(key);
            if (state == null) {
                state = new RowState(entry);
                this.rowStates.put(key, state);
            } else {
                state.entry = entry;
            }
            state.seen = true;
            state.targetIndex = index;
        }

        List<RowVisual> visuals = new ArrayList<>();
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, RowState> stateEntry : this.rowStates.entrySet()) {
            RowState state = stateEntry.getValue();
            float progress = Math.max(0.0F, state.animation.update(state.seen, now));
            if (!state.seen && state.animation.isHidden()) {
                toRemove.add(stateEntry.getKey());
                continue;
            }
            if (progress > 0.001F) {
                visuals.add(new RowVisual(state.entry, state.targetIndex, progress));
            }
        }
        toRemove.forEach(this.rowStates::remove);
        visuals.sort(Comparator.comparingInt(RowVisual::targetIndex)
                .thenComparing(visual -> visual.entry().name(), String.CASE_INSENSITIVE_ORDER));
        this.visuals = visuals;

        MsdfFont font = UiFonts.sfPro(UiFontStyle.MEDIUM.weight());
        float targetWidth = MIN_WIDTH;
        for (RowState state : this.rowStates.values()) {
            StaffEntry entry = state.entry;
            float rowWidth = 14.0F + HEAD_SIZE + HEAD_GAP
                    + font.measureWidth(entry.name(), NAME_SIZE, 0.0F)
                    + font.measureWidth(entry.status(), STATUS_SIZE, 0.0F);
            targetWidth = Math.max(targetWidth, rowWidth);
        }

        float rowStride = ROW_HEIGHT + ROW_GAP;
        float animatedRowsHeight = 0.0F;
        for (RowVisual visual : visuals) {
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

        if (this.visible <= 0.01F) {
            this.width = 0.0F;
            this.height = 0.0F;
            return;
        }
        this.width = width * unit;
        this.height = height * unit;
    }

    @Override
    protected void draw(Minecraft mc, float unit) {
        if (this.visible <= 0.01F) {
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

        MsdfFont titleFont = UiFonts.sfPro(600);
        float titleSize = TITLE_SIZE * unit;
        float titleCenterY = y + headerHeight * 0.5F;
        Render2DUtil.text(x + PAD_X * unit, titleFont.centeredTextY(titleCenterY, titleSize), titleSize, "Staff list")
                .font(titleFont)
                .style(UiFontStyle.SEMIBOLD)
                .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TITLE, this.visible))
                .draw();

        float headerIconSize = HEADER_ICON_SIZE * unit;
        Render2DUtil.texture(x + width - PAD_X * unit - headerIconSize,
                        titleCenterY - headerIconSize / 2.0F,
                        headerIconSize, headerIconSize, Textures.Hud.ICON_PLAYER)
                .color(ColorUtil.multiplyAlpha(Theme.getAccent(), this.visible))
                .draw();

        if (!hasBody) {
            return;
        }

        MsdfFont font = UiFonts.sfPro(400);
        float statusSize = STATUS_SIZE * unit;
        float nameSize = NAME_SIZE * unit;
        float headSize = HEAD_SIZE * unit;
        float headGap = HEAD_GAP * unit;
        float cursorY = y + headerHeight + BODY_PAD_TOP * unit;
        for (RowVisual visual : this.visuals) {
            StaffEntry entry = visual.entry();
            float progress = visual.progress();
            float rowAlpha = this.visible * progress;
            float rowCenterY = cursorY + (ROW_HEIGHT * unit) / 2.0F;

            boolean online = "Online".equals(entry.status());
            int statusColor = online ? STATUS_ONLINE : STATUS_VANISH;
            String statusText = entry.status();

            Identifier headSkin = entry.skin();
            if (headSkin != null) {
                PlayerHead.draw(x + PAD_X * unit, rowCenterY - headSize / 2.0F, headSize, headSkin, headSize / 2.0F,
                        ColorUtil.multiplyAlpha(ColorUtil.WHITE, rowAlpha));
            } else {
                Render2DUtil.texture(x + PAD_X * unit, rowCenterY - headSize / 2.0F, headSize, headSize, Textures.Hud.ICON_PLAYER)
                        .color(ColorUtil.multiplyAlpha(Theme.getAccent(), rowAlpha))
                        .draw();
            }

            float statusW = font.measureWidth(statusText, statusSize, 0.0F);
            float nameX = x + PAD_X * unit + headSize + headGap;
            String name = font.ellipsize(entry.name(), nameSize, 0.0F,
                    Math.max(12.0F * unit, x + width - PAD_X * unit - statusW - 4.0F * unit - nameX));
            Render2DUtil.text(nameX, font.centeredTextY(rowCenterY, nameSize), nameSize, name)
                    .font(font)
                    .style(UiFontStyle.REGULAR)
                    .color(ColorUtil.multiplyAlpha(Theme.Colors.TEXT_TITLE, rowAlpha))
                    .draw();
            Render2DUtil.text(x + width - PAD_X * unit - statusW, font.centeredTextY(rowCenterY, statusSize), statusSize, statusText)
                    .font(font)
                    .style(UiFontStyle.REGULAR)
                    .color(ColorUtil.multiplyAlpha(statusColor, rowAlpha))
                    .draw();
            cursorY += (ROW_HEIGHT + ROW_GAP) * unit * progress;
        }
    }

    private List<StaffEntry> getCachedStaff(Minecraft mc, long nowMs) {
        if (mc.level == null || mc.getConnection() == null) {
            this.cachedStaff = List.of();
            this.cachedWorld = null;
            this.nextRefreshAtMs = 0L;
            return this.cachedStaff;
        }
        if (this.cachedWorld != mc.level || nowMs >= this.nextRefreshAtMs) {
            this.cachedWorld = mc.level;
            this.cachedStaff = collectStaff(mc);
            this.nextRefreshAtMs = nowMs + REFRESH_INTERVAL_MS;
        }
        return this.cachedStaff;
    }

    private List<StaffEntry> collectStaff(Minecraft mc) {
        if (mc.level == null || mc.getConnection() == null) {
            return List.of();
        }

        Map<String, PlayerInfo> onlineInfo = new HashMap<>();
        mc.getConnection().getOnlinePlayers().forEach(info ->
                onlineInfo.put(info.getProfile().name().toLowerCase(Locale.ROOT), info));

        Map<String, StaffEntry> result = new HashMap<>();
        Collection<PlayerTeam> teams = mc.level.getScoreboard().getPlayerTeams();
        for (PlayerTeam team : teams) {
            String role = team.getPlayerPrefix() == null ? "" : team.getPlayerPrefix().getString().trim();
            if (!isStaffRole(role)) {
                continue;
            }
            for (String name : team.getPlayers()) {
                String key = name.toLowerCase(Locale.ROOT);
                if (EXCLUDED_NAMES.contains(key)) {
                    continue;
                }
                PlayerInfo info = onlineInfo.get(key);
                Identifier skin = info != null ? info.getSkin().body().texturePath() : null;
                result.put(key, new StaffEntry(name, skin, info != null ? "Online" : "Vanish"));
            }
        }

        return result.values().stream()
                .sorted(Comparator.comparing(StaffEntry::status).reversed().thenComparing(StaffEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private boolean isStaffRole(String role) {
        String value = role.toLowerCase(Locale.ROOT);
        return value.contains("admin") || value.contains("moder") || value.contains("helper")
                || value.contains("staff") || value.contains("owner") || value.contains("curator")
                || value.contains("админ")
                || value.contains("модер")
                || value.contains("хелпер");
    }

    private static final class RowState {
        private StaffEntry entry;
        private final HudRowAnimation animation = new HudRowAnimation();
        private boolean seen;
        private int targetIndex;

        private RowState(StaffEntry entry) {
            this.entry = entry;
        }
    }

    private record RowVisual(StaffEntry entry, int targetIndex, float progress) {
    }

    private record StaffEntry(String name, Identifier skin, String status) {
    }
}
