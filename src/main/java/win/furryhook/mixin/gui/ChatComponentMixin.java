package win.furryhook.mixin.gui;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import win.furryhook.feature.impl.visual.AnimationsFeature;
import win.furryhook.utils.text.NameProtectUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Map;
import java.util.WeakHashMap;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Unique
    private static final Map<GuiMessage.Line, Long> furryhook$revealStartNanos = new WeakHashMap<>();

    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true
    )
    private Component protectMessage(Component message) {
        return NameProtectUtil.protect(message);
    }

    @ModifyVariable(method = "forEachLine", at = @At("HEAD"), argsOnly = true)
    private ChatComponent.AlphaCalculator furryhook$wrapAlphaCalculator(ChatComponent.AlphaCalculator calculator) {
        if (!AnimationsFeature.usesChatAnimation()) {
            return calculator;
        }
        return line -> {
            float base = calculator.calculate(line);
            long now = System.nanoTime();
            Long start = furryhook$revealStartNanos.get(line);
            if (start == null) {
                start = now;
                furryhook$revealStartNanos.put(line, start);
            }
            float duration = AnimationsFeature.chatAnimationDurationNanos();
            float raw = duration <= 0.0F ? 1.0F : Math.min(1.0F, (now - start) / duration);
            float inv = 1.0F - raw;
            float eased = 1.0F - inv * inv * inv;
            return base * eased;
        };
    }
}
