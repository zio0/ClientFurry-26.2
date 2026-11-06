package win.furryhook.pve.mining;

import net.minecraft.world.item.ItemStack;
import win.furryhook.pve.economy.EconomyItemText;
import win.furryhook.pve.economy.EconomyTextParser;
import win.furryhook.pve.server.ServerProfile;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public enum MiningToolProfile {
    STANDARD(0, 1),
    BULLDOZER_I(1, 9),
    BULLDOZER_II(2, 27);

    private static final Pattern BULLDOZER = Pattern.compile(
            "(?iuU)\\bбульдозер\\s*(?:[:\\-]?\\s*)?(ii|i|2|1)\\b"
    );

    private final int bulldozerLevel;
    private final int maximumBlocksPerBreak;

    MiningToolProfile(int bulldozerLevel, int maximumBlocksPerBreak) {
        this.bulldozerLevel = bulldozerLevel;
        this.maximumBlocksPerBreak = maximumBlocksPerBreak;
    }

    public int bulldozerLevel() {
        return this.bulldozerLevel;
    }

    public int maximumBlocksPerBreak() {
        return this.maximumBlocksPerBreak;
    }

    public int durabilityReserve(ItemStack stack, double minimumPercent) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem()) {
            return 0;
        }
        int configured = (int) Math.ceil(
                stack.getMaxDamage() * Math.max(0.0D, minimumPercent) / 100.0D
        );
        return configured + this.maximumBlocksPerBreak;
    }

    public String displayName() {
        return switch (this) {
            case STANDARD -> "Standard";
            case BULLDOZER_I -> "Бульдозер I (3x3x1)";
            case BULLDOZER_II -> "Бульдозер II (3x3x3)";
        };
    }

    public static MiningToolProfile detect(ServerProfile server, ItemStack stack) {
        if (server != ServerProfile.FUNTIME || stack == null || stack.isEmpty()) {
            return STANDARD;
        }
        MiningToolProfile enchantmentProfile = STANDARD;
        for (var entry : stack.getEnchantments().entrySet()) {
            String id = entry.getKey().unwrapKey()
                    .map(key -> key.identifier().toString())
                    .orElse("");
            String description = entry.getKey().value().description().getString();
            if (EconomyTextParser.containsAny(
                    id + " " + description,
                    "bulldozer",
                    "бульдозер"
            )) {
                if (entry.getIntValue() >= 2) {
                    return BULLDOZER_II;
                }
                enchantmentProfile = BULLDOZER_I;
            }
        }
        if (enchantmentProfile != STANDARD) {
            return enchantmentProfile;
        }
        return detect(EconomyItemText.combined(stack));
    }

    static MiningToolProfile detect(String text) {
        Matcher matcher = BULLDOZER.matcher(EconomyTextParser.normalize(text));
        MiningToolProfile detected = STANDARD;
        while (matcher.find()) {
            String level = matcher.group(1);
            if ("ii".equalsIgnoreCase(level) || "2".equals(level)) {
                return BULLDOZER_II;
            }
            detected = BULLDOZER_I;
        }
        return detected;
    }
}
