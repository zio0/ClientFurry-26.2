package win.furryhook.utils.render.target;

import net.minecraft.world.entity.LivingEntity;
import win.furryhook.feature.impl.combat.AuraFeature;
import win.furryhook.feature.impl.combat.TriggerBotFeature;

public final class TargetMarkers {
    private final AuraMarkerRenderer marker = new AuraMarkerRenderer();
    private final GhostTargetRenderer ghost = new GhostTargetRenderer();
    private final CircleTargetRenderer circle = new CircleTargetRenderer();
    private final SoulTargetRenderer souls = new SoulTargetRenderer();

    public void render(AuraFeature feature, float tickDelta) {

        LivingEntity target = feature != null && feature.isEnabled() ? feature.getCurrentTarget() : null;
        if (target == null) {
            TriggerBotFeature triggerBot = TriggerBotFeature.getEnabled();
            if (triggerBot != null) {
                target = triggerBot.getCurrentTarget();
            }
        }
        boolean ghosts = feature != null && feature.usesGhostTargetEsp();
        boolean circles = feature != null && feature.usesCircleTargetEsp();
        boolean lightning = feature != null && feature.usesLightningTargetEsp();
        boolean souls = feature != null && feature.usesSoulsTargetEsp();
        int color = feature != null ? feature.getMarkerColor() : 0xFF00FF88;
        this.marker.render(ghosts || circles || lightning || souls ? null : target, tickDelta, color);
        this.ghost.render(ghosts ? target : null, tickDelta, color);
        this.circle.render(circles ? target : null, tickDelta, color);
        if (souls) {
            this.souls.render(feature, target, tickDelta, color);
        } else {
            this.souls.reset();
        }
    }

    public void release() {
        this.marker.release();
        this.souls.reset();
    }
}
