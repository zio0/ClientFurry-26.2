package win.furryhook.feature.impl.combat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import win.furryhook.context.MinecraftContext;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.EntityAttackEvent;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.NumberSetting;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Announces a taunt in chat whenever a player killed via {@link EntityAttackEvent}
 * (manual, Aura, or FakePlayer) dies - name and death both come from vanilla state,
 * no server-specific kill packet is required.
 */
public final class TrashTalkFeature extends Feature implements MinecraftContext {
    private static final String[] MESSAGES = {
            "на парашу сынок",
            "это гташка",
            "гг сучка",
            "у тебя так смешно разлетелись зубы",
            "че деду в зеркальную ракету",
            "че деду в нос ебанный паравоз",
            "причина тряски?",
            "гетни фурри клиент",
            "я ты",
            "еба ты долбоебис",
            "го секс",
            "кто поймал тот и съел",
            "кто успел тот и съел",
            "пиздорез",
            "армо бог,ты лох",
            "въебал в мозгу",
            "ок да?",
            "го ты валетиком ляжешь в гроб ебанутый",
            "мур мур",
            "cry more сынок",
            "ты кто по масте?",
            "привет чика",
            "привет котик",
            "паста твоя не бустит гетни фурри длс",
            "уебу суку",
    };

    private static final long TRACK_TIMEOUT_MS = 30_000L;

    private record TrackedTarget(Player player, long expiresAt) {
    }

    private record PendingTaunt(String name, long sendAt) {
    }

    public final BooleanSetting mentionName = register(new BooleanSetting("Mention Name", true));
    public final NumberSetting delay = register(new NumberSetting("Delay", 0.5, 0.0, 5.0, 0.1, "s"));
    public final NumberSetting cooldown = register(new NumberSetting("Cooldown", 2.0, 0.5, 10.0, 0.5, "s"));

    private final Map<Integer, TrackedTarget> tracked = new LinkedHashMap<>();
    private final Map<Integer, PendingTaunt> pending = new LinkedHashMap<>();
    private final ThreadLocalRandom random = ThreadLocalRandom.current();
    private long lastMessageAt;

    public TrashTalkFeature() {
        super("TrashTalk", "Отправляет сообщение в чат после убийства цели", FeatureCategory.COMBAT, BindSetting.UNBOUND);
    }

    @Override
    protected void onDisable() {
        tracked.clear();
        pending.clear();
        lastMessageAt = 0L;
    }

    @EventTarget
    public void onAttack(EntityAttackEvent event) {
        Entity target = event.getTarget();
        if (!(target instanceof Player player) || player == event.getPlayer()) {
            return;
        }
        tracked.put(player.getId(), new TrackedTarget(player, System.currentTimeMillis() + TRACK_TIMEOUT_MS));
    }

    @EventTarget
    public void onTick(GameTickEvent event) {
        if (player() == null) {
            return;
        }

        long now = System.currentTimeMillis();

        tracked.values().removeIf(tracked -> now >= tracked.expiresAt() && !tracked.player().isDeadOrDying());
        tracked.entrySet().removeIf(entry -> {
            if (!entry.getValue().player().isDeadOrDying()) {
                return false;
            }
            long sendAt = now + Math.round(this.delay.getValue().doubleValue() * 1000.0);
            pending.put(entry.getKey(), new PendingTaunt(entry.getValue().player().getName().getString(), sendAt));
            return true;
        });

        pending.entrySet().removeIf(entry -> {
            if (now < entry.getValue().sendAt()) {
                return false;
            }
            trySend(entry.getValue().name(), now);
            return true;
        });
    }

    private void trySend(String name, long now) {
        if (name == null || name.isBlank() || mc.player == null) {
            return;
        }
        if (now - this.lastMessageAt < Math.round(this.cooldown.getValue().doubleValue() * 1000.0)) {
            return;
        }

        String taunt = MESSAGES[this.random.nextInt(MESSAGES.length)];
        String message = this.mentionName.getValue() ? name + " " + taunt : taunt;
        mc.player.connection.sendChat(message);
        this.lastMessageAt = now;
    }
}
