package win.furryhook.pve.mining;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MiningInventory {
    private static final List<Block> COMMON_TUNNEL_BLOCKS = List.of(
            Blocks.STONE,
            Blocks.COBBLESTONE,
            Blocks.DEEPSLATE,
            Blocks.COBBLED_DEEPSLATE,
            Blocks.TUFF,
            Blocks.ANDESITE,
            Blocks.DIORITE,
            Blocks.GRANITE,
            Blocks.DIRT,
            Blocks.COARSE_DIRT,
            Blocks.GRAVEL,
            Blocks.NETHERRACK,
            Blocks.BLACKSTONE,
            Blocks.BASALT,
            Blocks.END_STONE
    );

    private MiningInventory() {
    }

    public static int freeSlots(LocalPlayer player) {
        int free = 0;
        for (int slot = 0; slot < 36; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    public static int bestPickaxeSlot(LocalPlayer player) {
        int bestSlot = -1;
        int bestRemaining = -1;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!isPickaxe(stack)) {
                continue;
            }
            int remaining = remainingDurability(stack);
            if (remaining > bestRemaining) {
                bestRemaining = remaining;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    public static boolean isPickaxe(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(ItemTags.PICKAXES);
    }

    public static int remainingDurability(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem()) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
    }

    public static double durabilityPercent(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem()) {
            return 100.0D;
        }
        return remainingDurability(stack) * 100.0D / Math.max(1, stack.getMaxDamage());
    }

    public static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
    }

    public static boolean isTrash(ItemStack stack, Set<String> trashIds) {
        if (stack == null || stack.isEmpty() || trashIds == null || trashIds.isEmpty()) {
            return false;
        }
        if (stack.isDamageableItem() || !stack.getEnchantments().isEmpty()) {
            return false;
        }
        return trashIds.contains(itemId(stack));
    }

    public static boolean isDepositItem(ItemStack stack, Set<String> configuredIds) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = itemId(stack);
        if (configuredIds != null && configuredIds.contains(id)) {
            return true;
        }
        String path = id.substring(id.indexOf(':') + 1);
        return path.startsWith("raw_")
                || path.endsWith("_ore")
                || path.equals("coal")
                || path.equals("diamond")
                || path.equals("emerald")
                || path.equals("lapis_lazuli")
                || path.equals("redstone")
                || path.equals("amethyst_shard")
                || path.equals("nether_quartz")
                || path.equals("ancient_debris")
                || path.equals("gold_nugget");
    }

    public static List<Block> resolveBlocks(Set<String> ids) {
        List<Block> blocks = new ArrayList<>();
        if (ids == null) {
            return blocks;
        }
        for (String id : ids) {
            try {
                Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
                if (block != null && block != Blocks.AIR && !blocks.contains(block)) {
                    blocks.add(block);
                }
            } catch (RuntimeException ignored) {

            }
        }
        return List.copyOf(blocks);
    }

    public static boolean isOre(Block block) {
        if (block == null || block == Blocks.AIR) {
            return false;
        }
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return path.endsWith("_ore")
                || path.equals("ancient_debris")
                || path.equals("gilded_blackstone");
    }

    public static List<Block> commonTunnelBlocks() {
        return COMMON_TUNNEL_BLOCKS;
    }

    public static boolean isCommonTunnelBlock(Block block) {
        return COMMON_TUNNEL_BLOCKS.contains(block);
    }

    public static boolean isSafeBreakTarget(Block block) {
        if (block == null || block == Blocks.AIR) {
            return false;
        }
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return !Set.of(
                "bedrock",
                "barrier",
                "command_block",
                "chain_command_block",
                "repeating_command_block",
                "structure_block",
                "jigsaw",
                "end_portal",
                "end_portal_frame",
                "nether_portal",
                "moving_piston",
                "light"
        ).contains(path);
    }
}
