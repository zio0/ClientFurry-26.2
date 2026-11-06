package win.furryhook.feature.impl.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.Vec3;
import win.furryhook.event.EventTarget;
import win.furryhook.event.events.game.PlayerTickEvent;
import win.furryhook.event.events.packet.PacketSendEvent;
import win.furryhook.feature.Feature;
import win.furryhook.feature.FeatureCategory;
import win.furryhook.feature.FeatureManager;
import win.furryhook.feature.setting.BindSetting;

/**
 * AirStuck — порт {@code alpha.night.modules.impl.movement.AirStuck}.
 *
 * <p>Держит вас на месте, как во фрикаме: скорость обнуляется каждый тик, а исходящие пакеты
 * движения переписываются на текущие координаты и поворот, так что сервер видит стоящего игрока.
 *
 * <p>Отличия от исходника, оба вынужденные:
 *
 * <ul>
 *   <li>{@code CPlayerPacket} с полями {@code x/y/z/yaw/pitch} в этой версии неизменяем — вместо
 *       правки полей пакет пересобирается заново из текущей позиции игрока. Результат на проводе
 *       тот же.</li>
 *   <li>Проверка {@code CUseEntityPacket.getEntityFromWorld(...).getEntityId() == mc.player.getEntityId()}
 *       в исходнике не срабатывает никогда: своего же игрока в пакете атаки быть не может.
 *       Не переносил.</li>
 * </ul>
 *
 * <p>Это открытый рассинхрон: сервер держит одну позицию, клиент живёт в другой. Любой античит,
 * предсказывающий движение, увидит это сразу.
 */
public final class AirStuckFeature extends Feature {

    public AirStuckFeature() {
        super("AirStuck", "Останавливает вас в воздухе как на фрикаме", FeatureCategory.MOVEMENT, BindSetting.UNBOUND);
    }

    public static AirStuckFeature getEnabled() {
        return FeatureManager.INSTANCE.getEnabled(AirStuckFeature.class);
    }

    /**
     * Оставлено для {@link win.furryhook.feature.impl.combat.AuraFeature} и триггербота: они
     * отпускают заморозку на тик удара. У этого порта отдельного окна для удара нет — заморозка
     * либо включена, либо нет, — так что вызов ничего не делает.
     */
    public void pauseForAttack() {
        // no-op
    }

    /** Этот порт реч ауры не расширяет. */
    public boolean isExtendedKillAuraRange() {
        return false;
    }

    public double getExtendedKillAuraRange() {
        return 0.0D;
    }

    @EventTarget
    public void onPlayerTick(PlayerTickEvent event) {
        LocalPlayer player = event.getPlayer();
        if (player != null) {
            player.setDeltaMovement(Vec3.ZERO);
        }
    }

    @EventTarget
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPhase() != PacketSendEvent.Phase.PRE) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            setEnabled(false);
            return;
        }
        player.setDeltaMovement(Vec3.ZERO);

        Packet<?> packet = event.getPacket();

        // Постановка блока проходит, всё остальное использование по блоку — нет.
        if (packet instanceof ServerboundUseItemOnPacket
                && !(player.getMainHandItem().getItem() instanceof BlockItem)) {
            event.cancel();
            return;
        }

        if (!(packet instanceof ServerboundMovePlayerPacket move)) {
            return;
        }

        // Позиция и поворот подменяются на текущие клиентские: сервер получает пакет там же, где
        // он получил предыдущий, то есть не видит движения вовсе.
        if (move.hasPosition() && move.hasRotation()) {
            event.setPacket(new ServerboundMovePlayerPacket.PosRot(
                    player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot(),
                    player.onGround(), player.horizontalCollision
            ));
        } else if (move.hasPosition()) {
            event.setPacket(new ServerboundMovePlayerPacket.Pos(
                    player.getX(), player.getY(), player.getZ(),
                    player.onGround(), player.horizontalCollision
            ));
        } else if (move.hasRotation()) {
            event.setPacket(new ServerboundMovePlayerPacket.Rot(
                    player.getYRot(), player.getXRot(),
                    player.onGround(), player.horizontalCollision
            ));
        }
    }
}
