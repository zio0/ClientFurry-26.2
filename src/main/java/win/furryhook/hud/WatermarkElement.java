package win.furryhook.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import win.furryhook.feature.impl.visual.HudFeature;
import win.furryhook.utils.math.AnimatedValue;
import win.furryhook.utils.render.Textures;
import win.furryhook.utils.render.gui.MsdfFont;
import win.furryhook.utils.render.gui.UiFontStyle;
import win.furryhook.utils.render.gui.UiFonts;

import java.util.ArrayList;
import java.util.List;

/**
 * Draggable row of stats: player head / FPS / ping. Coordinates, BPS and TPS live on their own
 * fixed corner elements ({@link BottomStatsElement}, {@link TpsElement}) instead.
 */
public final class WatermarkElement extends StatRowElement {
    public static final String ITEM_PLAYER = "Player";
    public static final String ITEM_FPS = "FPS";
    public static final String ITEM_PING = "Ping";
    public static final String ITEM_SERVER = "Server";
    public static final String[] CONTENT_ITEMS = {ITEM_PLAYER, ITEM_FPS, ITEM_PING, ITEM_SERVER};

    /** Shown instead of an address while not connected to a dedicated server. */
    private static final String LABEL_SINGLEPLAYER = "singleplayer";
    private static final String LABEL_REALMS = "realms";
    /** Trimmed off the end of an address — nobody writes the default port on a server list entry. */
    private static final String DEFAULT_PORT = ":25565";
    /** Longest address rendered in full; anything past this is cut with an ellipsis. */
    private static final int ADDRESS_MAX_CHARS = 24;

    private final AnimatedValue widthAnim = new AnimatedValue();
    private final AnimatedValue fpsAnim = new AnimatedValue();
    private final AnimatedValue pingAnim = new AnimatedValue();
    private final List<Segment> row = new ArrayList<>(3);

    public WatermarkElement() {
        super("watermark", "Watermark");
    }

    @Override
    protected void layout(Minecraft mc, float unit) {
        long now = System.nanoTime();
        float fps = this.fpsAnim.update(Math.max(0, mc.getFps()), 0.05F, now);
        float ping = this.pingAnim.update(latency(mc), 0.05F, now);

        this.row.clear();
        if (HudFeature.watermarkShows(ITEM_PLAYER)) {
            this.row.add(new Segment(null, true, List.of(new TextPart("Furry", false))));
        }
        if (HudFeature.watermarkShows(ITEM_FPS)) {
            this.row.add(unit(Textures.Hud.ICON_COMPUTER, Long.toString(Math.round(fps)), " fps"));
        }
        if (HudFeature.watermarkShows(ITEM_PING)) {
            this.row.add(unit(Textures.Hud.ICON_WIFI, Long.toString(Math.round(ping)), " ms"));
        }
        if (HudFeature.watermarkShows(ITEM_SERVER)) {
            this.row.add(new Segment(Textures.Hud.ICON_CLOUD, false,
                    List.of(new TextPart(serverAddress(mc), false))));
        }

        MsdfFont font = UiFonts.sfPro(UiFontStyle.MEDIUM.weight());
        float textSize = TEXT_SIZE * unit;
        float target = this.row.isEmpty() ? 0.0F : measureRow(font, this.row, textSize, unit);
        float width = this.widthAnim.update(target, 0.13F, now);

        this.width = width;
        this.height = width > 0.5F ? ROW_HEIGHT * unit : 0.0F;
    }

    @Override
    protected void draw(Minecraft mc, float unit) {
        if (this.width <= 0.5F) {
            return;
        }
        float alpha = appearAlpha();
        float rowHeight = ROW_HEIGHT * unit;
        drawRow(this.row, this.x, this.y, this.width, rowHeight, unit, alpha);
        drawShine(this.x, this.y, this.width, rowHeight, unit);
    }

    /**
     * The address the player is actually on, as it would be typed into the server list —
     * {@code bravohvh.ru}, not the resolved socket. The default port is trimmed because nobody
     * writes it; a non-standard one is kept because it is part of how you get back there.
     */
    private static String serverAddress(Minecraft mc) {
        ServerData server = mc.getCurrentServer();
        if (server == null) {
            return LABEL_SINGLEPLAYER;
        }
        if (server.isRealm()) {
            return LABEL_REALMS;
        }

        String address = server.ip;
        if (address == null || address.isBlank()) {
            return LABEL_SINGLEPLAYER;
        }
        address = address.trim().toLowerCase(java.util.Locale.ROOT);
        if (address.endsWith(DEFAULT_PORT)) {
            address = address.substring(0, address.length() - DEFAULT_PORT.length());
        }
        if (address.length() > ADDRESS_MAX_CHARS) {
            address = address.substring(0, ADDRESS_MAX_CHARS - 1) + "…";
        }
        return address;
    }

    private static int latency(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) {
            return 0;
        }
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info != null ? info.getLatency() : 0;
    }
}
