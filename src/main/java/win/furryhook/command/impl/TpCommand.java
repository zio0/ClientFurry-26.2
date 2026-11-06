package win.furryhook.command.impl;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.player.Player;
import win.furryhook.command.ClientCommand;
import win.furryhook.context.MinecraftContext;
import win.furryhook.utils.text.ChatUtil;

import java.util.Locale;

/**
 * Клиентский телепорт: якорит сервер пачкой Pos-пакетов на текущей позиции,
 * затем той же пачкой продавливает абсолютные координаты и двигает клиент.
 * Работает по той же схеме, что vclip, только по всем трём осям сразу —
 * на серверах без строгой movement-валидации проходит без честного пути.
 */
public final class TpCommand extends ClientCommand implements MinecraftContext {

    public TpCommand() {
        super("tp", "Клиентский телепорт: tp <x> <y> <z>  •  tp <player>");
    }

    @Override
    public void build(LiteralArgumentBuilder<Object> builder) {
        builder.executes(context -> {
            ChatUtil.usage("tp <x> <y> <z>  •  tp <player>");
            return 1;
        });
        builder.then(RequiredArgumentBuilder.<Object, Double>argument("x", DoubleArgumentType.doubleArg())
                .then(RequiredArgumentBuilder.<Object, Double>argument("y", DoubleArgumentType.doubleArg())
                        .then(RequiredArgumentBuilder.<Object, Double>argument("z", DoubleArgumentType.doubleArg())
                                .executes(context -> teleport(
                                        DoubleArgumentType.getDouble(context, "x"),
                                        DoubleArgumentType.getDouble(context, "y"),
                                        DoubleArgumentType.getDouble(context, "z"))))));
        builder.then(RequiredArgumentBuilder.<Object, String>argument("player", StringArgumentType.word())
                .executes(context -> teleportToPlayer(StringArgumentType.getString(context, "player"))));
    }

    private int teleportToPlayer(String name) {
        LocalPlayer self = player();
        if (self == null || level() == null) {
            return 0;
        }

        Player target = null;
        for (Player candidate : level().players()) {
            if (candidate == self) {
                continue;
            }
            if (candidate.getGameProfile().name().equalsIgnoreCase(name)) {
                target = candidate;
                break;
            }
        }

        if (target == null) {
            ChatUtil.error("Игрок не найден  •  " + name);
            return 0;
        }

        return teleport(target.getX(), target.getY(), target.getZ());
    }

    private int teleport(double x, double y, double z) {
        LocalPlayer player = player();
        if (player == null || player.connection == null) {
            return 0;
        }

        for (int i = 0; i < 10; i++) {
            player.connection.send(new ServerboundMovePlayerPacket.Pos(
                    player.getX(), player.getY(), player.getZ(), false, false));
        }
        for (int i = 0; i < 10; i++) {
            player.connection.send(new ServerboundMovePlayerPacket.Pos(x, y, z, false, false));
        }

        player.setPos(x, y, z);
        ChatUtil.success("TP  •  X " + fmt(x) + "  •  Y " + fmt(y) + "  •  Z " + fmt(z));
        return 1;
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
