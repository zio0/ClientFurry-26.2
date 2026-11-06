package win.furryhook.utils.render.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import win.furryhook.feature.impl.player.FullBrightFeature;
import win.furryhook.utils.render.Render3DUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DynamicLightManager {
    public static final DynamicLightManager INSTANCE = new DynamicLightManager();

    private static final int MAX_CANDIDATES = 64;
    private static final int MAX_SHADER_LIGHTS = 16;
    private static final double MAX_DISTANCE_SQR = 64.0D * 64.0D;

    private volatile Map<Long, Integer> virtualLuminance = Map.of();
    private List<Candidate> candidates = List.of();
    private ClientLevel engineLevel;

    private DynamicLightManager() {
    }

    public static int virtualLuminance(long blockPos) {
        return INSTANCE.virtualLuminance.getOrDefault(blockPos, 0);
    }

    public void tick(Minecraft minecraft, FullBrightFeature feature) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            clear();
            return;
        }

        List<Candidate> collected = new ArrayList<>();
        LightSpec localLight = strongest(
                lightFor(player.getMainHandItem()),
                lightFor(player.getOffhandItem())
        );
        if (localLight != null) {
            collected.add(Candidate.entity(player, player.getEyeHeight() * 0.72D, localLight));
        }

        for (Entity entity : level.entitiesForRendering()) {
            if (entity == player
                    || entity.isRemoved()
                    || entity.distanceToSqr(player) > MAX_DISTANCE_SQR) {
                continue;
            }

            LightSpec source = null;
            double yOffset = entity.getBbHeight() * 0.5D;
            if (feature.lightItems.getValue() && entity instanceof ItemEntity itemEntity) {
                source = lightFor(itemEntity.getItem());
                yOffset = 0.2D;
            }
            if (feature.lightOthers.getValue() && entity instanceof LivingEntity living) {
                source = strongest(
                        source,
                        strongest(lightFor(living.getMainHandItem()), lightFor(living.getOffhandItem()))
                );
            }
            if (feature.lightOthers.getValue() && entity.displayFireAnimation()) {
                source = strongest(source, LightSpec.FIRE);
            }
            if (source != null) {
                collected.add(Candidate.entity(entity, yOffset, source));
            }
        }

        collected.sort(Comparator.comparingDouble(candidate ->
                candidate.position(1.0F).distanceToSqr(player.position())));
        if (collected.size() > MAX_CANDIDATES) {
            collected = new ArrayList<>(collected.subList(0, MAX_CANDIDATES));
        }
        this.candidates = List.copyOf(collected);
        updateEngineLights(level, feature);
    }

    public List<RenderLight> shaderLights(FullBrightFeature feature,
                                          float partialTick,
                                          Vec3 cameraPosition) {
        if (!feature.usesShaderLights() || this.candidates.isEmpty()) {
            return List.of();
        }
        float radiusMultiplier = feature.lightRadius.getValue().floatValue();
        return this.candidates.stream()
                .filter(candidate -> !candidate.entity.isRemoved())
                .map(candidate -> candidate.toRenderLight(partialTick, radiusMultiplier))
                .sorted(Comparator.comparingDouble(light ->
                        light.position.distanceToSqr(cameraPosition)))
                .limit(MAX_SHADER_LIGHTS)
                .toList();
    }

    public void clear() {
        this.candidates = List.of();
        clearEngineLights();
    }

    public void revalidateAfterVanillaUpdates(ClientLevel level) {
        Map<Long, Integer> sources = this.virtualLuminance;
        if (level == null || level != this.engineLevel || sources.isEmpty()) {
            return;
        }
        var blockLight = level.getChunkSource()
                .getLightEngine()
                .getLayerListener(LightLayer.BLOCK);
        for (long packedPos : sources.keySet()) {
            blockLight.checkBlock(BlockPos.of(packedPos));
        }
        blockLight.runLightUpdates();
    }

    private void updateEngineLights(ClientLevel level, FullBrightFeature feature) {
        if (this.engineLevel != null && this.engineLevel != level) {
            clearEngineLights();
        }
        this.engineLevel = level;

        Map<Long, Integer> next = new HashMap<>();
        if (feature.usesEngineLights()) {
            float radiusMultiplier = feature.lightRadius.getValue().floatValue();
            float intensity = feature.lightIntensity.getValue().floatValue();
            for (Candidate candidate : this.candidates) {
                Vec3 position = candidate.position(1.0F);
                BlockPos blockPos = BlockPos.containing(position);
                if (!level.hasChunkAt(blockPos)) {
                    continue;
                }
                int luminance = Math.clamp(
                        Math.round(candidate.spec.luminance * radiusMultiplier * Math.min(1.5F, intensity)),
                        1,
                        15
                );
                next.merge(blockPos.asLong(), luminance, Math::max);
            }
        }

        Map<Long, Integer> previous = this.virtualLuminance;
        this.virtualLuminance = Map.copyOf(next);
        if (previous.equals(next)) {
            return;
        }

        Set<Long> changed = new HashSet<>(previous.keySet());
        changed.addAll(next.keySet());
        var lightEngine = level.getChunkSource()
                .getLightEngine()
                .getLayerListener(LightLayer.BLOCK);
        for (long packedPos : changed) {
            if (previous.getOrDefault(packedPos, 0).intValue()
                    != next.getOrDefault(packedPos, 0).intValue()) {
                lightEngine.checkBlock(BlockPos.of(packedPos));
            }
        }
    }

    private void clearEngineLights() {
        Map<Long, Integer> previous = this.virtualLuminance;
        this.virtualLuminance = Map.of();
        ClientLevel level = this.engineLevel;
        this.engineLevel = null;
        if (level == null || previous.isEmpty()) {
            return;
        }
        var lightEngine = level.getChunkSource()
                .getLightEngine()
                .getLayerListener(LightLayer.BLOCK);
        for (long packedPos : previous.keySet()) {
            lightEngine.checkBlock(BlockPos.of(packedPos));
        }
    }

    private static LightSpec lightFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (stack.is(Items.SOUL_TORCH) || stack.is(Items.SOUL_LANTERN)
                || stack.is(Items.SOUL_CAMPFIRE)) {
            return new LightSpec(0x6AD9FF, 12, 0.08F);
        }
        if (stack.is(Items.REDSTONE_TORCH)) {
            return new LightSpec(0xFF3D24, 9, 0.04F);
        }
        if (stack.is(Items.SEA_LANTERN) || stack.is(Items.END_ROD)) {
            return new LightSpec(0xD8F8FF, 14, 0.02F);
        }
        if (stack.is(Items.OCHRE_FROGLIGHT)) {
            return new LightSpec(0xFFD36A, 15, 0.01F);
        }
        if (stack.is(Items.VERDANT_FROGLIGHT)) {
            return new LightSpec(0x8DFFC1, 15, 0.01F);
        }
        if (stack.is(Items.PEARLESCENT_FROGLIGHT)) {
            return new LightSpec(0xE3A8FF, 15, 0.01F);
        }
        if (stack.is(Items.LAVA_BUCKET) || stack.is(Items.FIRE_CHARGE)
                || stack.is(Items.BLAZE_ROD) || stack.is(Items.BLAZE_POWDER)
                || stack.is(Items.MAGMA_CREAM)) {
            return new LightSpec(0xFF6A24, 15, 0.18F);
        }
        if (stack.is(Items.GLOW_BERRIES) || stack.is(Items.GLOW_INK_SAC)) {
            return new LightSpec(0x8DFFB0, 10, 0.04F);
        }
        if (stack.is(Items.NETHER_STAR)) {
            return new LightSpec(0xC7E8FF, 15, 0.08F);
        }
        if (stack.getItem() instanceof BlockItem blockItem) {
            int luminance = blockItem.getBlock().defaultBlockState().getLightEmission();
            if (luminance > 0) {
                return new LightSpec(0xFFD090, luminance, luminance >= 14 ? 0.07F : 0.03F);
            }
        }
        return null;
    }

    private static LightSpec strongest(LightSpec first, LightSpec second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.luminance >= second.luminance ? first : second;
    }

    private record Candidate(Entity entity, double yOffset, LightSpec spec) {
        private static Candidate entity(Entity entity, double yOffset, LightSpec spec) {
            return new Candidate(entity, yOffset, spec);
        }

        private Vec3 position(float partialTick) {
            return Render3DUtil.interpolatedPosition(this.entity, partialTick)
                    .add(0.0D, this.yOffset, 0.0D);
        }

        private RenderLight toRenderLight(float partialTick, float radiusMultiplier) {
            return new RenderLight(
                    position(partialTick),
                    this.spec.rgb,
                    (3.0F + this.spec.luminance * 0.62F) * radiusMultiplier,
                    this.spec.flicker,
                    this.entity.getId() * 0.731F
            );
        }
    }

    private record LightSpec(int rgb, int luminance, float flicker) {
        private static final LightSpec FIRE = new LightSpec(0xFF6A24, 15, 0.22F);
    }

    public record RenderLight(Vec3 position, int rgb, float radius, float flicker, float phase) {
    }
}
