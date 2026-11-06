package win.furryhook.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

public final class ClientSounds {

    public static final SoundEvent MODULE_ENABLE =
            SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("furryhook", "module_enable"));
    public static final SoundEvent MODULE_DISABLE =
            SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("furryhook", "module_disable"));

    private static volatile boolean enabled = true;

    private ClientSounds() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static void playModuleToggle(boolean moduleEnabled) {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getSoundManager() == null) {
            return;
        }
        mc.getSoundManager()
                .play(SimpleSoundInstance.forUI(moduleEnabled ? MODULE_ENABLE : MODULE_DISABLE, 1.0f));
    }
}
