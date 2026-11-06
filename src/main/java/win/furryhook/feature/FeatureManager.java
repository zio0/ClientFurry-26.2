package win.furryhook.feature;

import win.furryhook.event.EventTarget;
import win.furryhook.event.events.input.KeyboardInputEvent;
import win.furryhook.event.events.input.MouseInputEvent;
import win.furryhook.event.events.lifecycle.ShutdownEvent;
import win.furryhook.feature.impl.combat.AntiBotFeature;
import win.furryhook.feature.impl.combat.AutoExplosionFeature;
import win.furryhook.feature.impl.combat.AutoWebFeature;
import win.furryhook.feature.impl.combat.CrystalAuraFeature;
import win.furryhook.feature.impl.combat.CrystalTapFeature;
import win.furryhook.feature.impl.combat.ElytraResolverFeature;
import win.furryhook.feature.impl.combat.AuraFeature;
import win.furryhook.feature.impl.combat.AutoClickerFeature;
import win.furryhook.feature.impl.combat.AutoTotemFeature;
import win.furryhook.feature.impl.combat.BowAimbotFeature;
import win.furryhook.feature.impl.combat.HitBoxesFeature;
import win.furryhook.feature.impl.combat.HitSoundFeature;
import win.furryhook.feature.impl.combat.TrashTalkFeature;
import win.furryhook.feature.impl.combat.TriggerBotFeature;
import win.furryhook.feature.impl.combat.VelocityFeature;
import win.furryhook.feature.impl.misc.AuctionHelperFeature;
import win.furryhook.feature.impl.misc.AutoTpaAcceptFeature;
import win.furryhook.feature.impl.misc.ChestStealerFeature;
import win.furryhook.feature.impl.misc.DeathCoordsFeature;
import win.furryhook.feature.impl.misc.NameProtectFeature;
import win.furryhook.feature.impl.misc.ScoreboardHealthFeature;
import win.furryhook.feature.impl.misc.ServerHelperFeature;
import win.furryhook.feature.impl.misc.XCarryFeature;
import win.furryhook.feature.impl.movement.AirStuckFeature;
import win.furryhook.feature.impl.movement.AutoJumpFeature;
import win.furryhook.feature.impl.movement.ElytraBoostFeature;
import win.furryhook.feature.impl.player.ElytraHelperFeature;
import win.furryhook.feature.impl.movement.ElytraMotionFeature;
import win.furryhook.feature.impl.movement.FreecamFeature;
import win.furryhook.feature.impl.movement.GrimCollideFeature;
import win.furryhook.feature.impl.movement.GrimElytraFeature;
import win.furryhook.feature.impl.movement.GrimFlyFeature;
import win.furryhook.feature.impl.movement.InventoryMoveFeature;
import win.furryhook.feature.impl.movement.NoSlowFeature;
import win.furryhook.feature.impl.movement.NoPushFeature;
import win.furryhook.feature.impl.movement.ParkourFeature;
import win.furryhook.feature.impl.movement.SafeWalkFeature;
import win.furryhook.feature.impl.movement.SprintFeature;
import win.furryhook.feature.impl.movement.SpeedFeature;
import win.furryhook.feature.impl.movement.StrafeFeature;
import win.furryhook.feature.impl.movement.TimerFeature;
import win.furryhook.feature.impl.movement.WallClimbFeature;
import win.furryhook.feature.impl.movement.WaterSpeedFeature;
import win.furryhook.feature.impl.player.AutoRespawnFeature;
import win.furryhook.feature.impl.player.FakePlayerFeature;
import win.furryhook.feature.impl.player.AutoSwapFeature;
import win.furryhook.feature.impl.player.AutoToolFeature;
import win.furryhook.feature.impl.player.ClickPearlFeature;
import win.furryhook.feature.impl.player.FullBrightFeature;
import win.furryhook.feature.impl.player.MultiActionFeature;
import win.furryhook.feature.impl.player.NoDelayFeature;
import win.furryhook.feature.impl.player.NoJumpBoostFeature;
import win.furryhook.feature.impl.player.WindHopFeature;
import win.furryhook.feature.impl.pve.*;
import win.furryhook.feature.impl.pve.autowarden.AutoWardenFeature;
import win.furryhook.feature.impl.visual.AnimationsFeature;
import win.furryhook.feature.impl.visual.ArrowsFeature;
import win.furryhook.feature.impl.visual.AmbienceFeature;
import win.furryhook.feature.impl.visual.AtmosphereFeature;
import win.furryhook.feature.impl.visual.CapeFeature;
import win.furryhook.feature.impl.visual.ChamsFeature;
import win.furryhook.feature.impl.visual.CrosshairFeature;
import win.furryhook.feature.impl.visual.CrystalOptimizerFeature;
import win.furryhook.feature.impl.visual.EmotionsFeature;
import win.furryhook.feature.impl.visual.EntityEspFeature;
import win.furryhook.feature.impl.visual.HudFeature;
import win.furryhook.feature.impl.visual.ItemPhysicsFeature;
import win.furryhook.feature.impl.visual.JumpCirclesFeature;
import win.furryhook.feature.impl.visual.NameTagsFeature;
import win.furryhook.feature.impl.visual.LineGlyphesFeature;
import win.furryhook.feature.impl.visual.ParticlesFeature;
import win.furryhook.feature.impl.visual.TrailsFeature;
import win.furryhook.feature.impl.visual.RemovalsFeature;
import win.furryhook.feature.impl.visual.RtxFeature;
import win.furryhook.feature.impl.visual.ShaderHandsFeature;
import win.furryhook.feature.impl.visual.SwingAnimationFeature;
import win.furryhook.feature.impl.visual.TrajectoriesFeature;
import win.furryhook.feature.impl.visual.ViewModelFeature;
import win.furryhook.feature.impl.visual.WorldTweaksFeature;
import win.furryhook.feature.setting.BindSetting;
import win.furryhook.feature.setting.BooleanSetting;
import win.furryhook.feature.setting.Setting;
import win.furryhook.utils.InputGuard;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class FeatureManager {
    public static final FeatureManager INSTANCE = new FeatureManager();

    private final Map<String, Feature> features = new LinkedHashMap<>();
    private final Map<Class<? extends Feature>, Feature> featuresByType = new ConcurrentHashMap<>();
    private final FeatureConfigStore configStore = new FeatureConfigStore();
    private final ScheduledExecutorService configWriter = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Blade Config Writer");
        thread.setDaemon(true);
        return thread;
    });
    private final Object saveLock = new Object();
    private final Map<Feature, Set<Integer>> activeHoldBinds = new IdentityHashMap<>();
    private ScheduledFuture<?> pendingSave;
    private boolean initialized;

    private FeatureManager() {
    }

    public void initialize() {
        if (initialized) {
            return;
        }

        List.of(
                new SprintFeature(),
                new AutoJumpFeature(),
                new GrimFlyFeature(),
                new InventoryMoveFeature(),
                new NoSlowFeature(),
                new NoPushFeature(),
                new SafeWalkFeature(),
                new WallClimbFeature(),
                new WaterSpeedFeature(),
                new ParkourFeature(),
                new TimerFeature(),
                new SpeedFeature(),
                new StrafeFeature(),
                new FreecamFeature(),
                new GrimCollideFeature(),
                new GrimElytraFeature(),
                new AirStuckFeature(),
                new ElytraBoostFeature(),
                new ElytraHelperFeature(),
                new ElytraMotionFeature(),
                new AuraFeature(),
                new AntiBotFeature(),
                new ElytraResolverFeature(),
                new TriggerBotFeature(),
                new AutoTotemFeature(),
                new AutoExplosionFeature(),
                new AutoWebFeature(),
                new CrystalAuraFeature(),
                new CrystalTapFeature(),
                new AutoClickerFeature(),
                new HitBoxesFeature(),
                new HitSoundFeature(),
                new TrashTalkFeature(),
                new VelocityFeature(),
                new BowAimbotFeature(),
                new HudFeature(),
                new ArrowsFeature(),
                new EntityEspFeature(),
                new NameTagsFeature(),
                new ParticlesFeature(),
                new TrailsFeature(),
                new LineGlyphesFeature(),
                new JumpCirclesFeature(),
                new TrajectoriesFeature(),
                new RemovalsFeature(),
                new CrosshairFeature(),
                new CrystalOptimizerFeature(),
                new SwingAnimationFeature(),
                new ViewModelFeature(),
                new ShaderHandsFeature(),
                // HandsTest is a WIP module, deliberately not registered so it stays hidden from the GUI for now.
                new CapeFeature(),
                new EmotionsFeature(),
                new ChamsFeature(),
                new ItemPhysicsFeature(),
                new WorldTweaksFeature(),
                new AmbienceFeature(),
                new AtmosphereFeature(),
                new RtxFeature(),
                new AnimationsFeature(),
                new NameProtectFeature(),
                new DeathCoordsFeature(),
                new ScoreboardHealthFeature(),
                new XCarryFeature(),
                new AuctionHelperFeature(),
                new ServerHelperFeature(),
                new AutoTpaAcceptFeature(),
                new ChestStealerFeature(),
                new AutoSwapFeature(),
                new AutoRespawnFeature(),
                new WindHopFeature(),
                new FakePlayerFeature(),
                new FullBrightFeature(),
                new NoJumpBoostFeature(),
                new AutoToolFeature(),
                new ClickPearlFeature(),
                new MultiActionFeature(),
                new NoDelayFeature(),
                PveManagerFeature.INSTANCE,
                new AutoFishFeature(),
                new AutoArmorFeature(),
                new AutoPotionFeature(),
                new AutoUseFeature(),
                new AutoGappleFeature(),
                new AntiAfkFeature(),
                new AutoLeaveFeature(),
                new AutoAuthFeature(),
                new NukerFeature(),
                new MineHelperFeature(),
                new AutoTpLootFeature(),
                new AutoMineFeature(),
                new BaseFinderFeature(),
                new AppleFarmerFeature(),
                new CreeperFarmFeature(),
                new AutoCrafterFeature(),
                new AutoTradeFeature(),
                new AuctionRelistFeature(),
                new ClanInvestFeature(),
                new ClanUpgradeFeature(),
                new GriefJoinerFeature(),
                new SynchronizationFeature(),
                new AutoWardenFeature()
        ).forEach(this::register);

        configStore.load(this);
        initialized = true;

        Runtime.getRuntime().addShutdownHook(new Thread(this::flushPendingSave, "Blade Config Flush"));
    }

    public void save() {
        if (!initialized) {
            return;
        }
        var snapshot = configStore.snapshot(this);
        synchronized (saveLock) {
            if (pendingSave != null) {
                pendingSave.cancel(false);
            }
            pendingSave = configWriter.schedule(() -> configStore.save(snapshot), 2L, TimeUnit.SECONDS);
        }
    }

    private void flushPendingSave() {
        synchronized (saveLock) {
            if (pendingSave == null) {
                return;
            }
            pendingSave.cancel(false);
            pendingSave = null;
        }
        configStore.save(configStore.snapshot(this));
    }

    public boolean saveConfigAs(String name) {
        return initialized && configStore.saveNamed(name, configStore.snapshot(this));
    }

    public boolean loadConfig(String name) {
        if (!initialized || !configStore.loadNamed(this, name)) {
            return false;
        }
        save();
        return true;
    }

    public boolean deleteConfig(String name) {
        return configStore.deleteNamed(name);
    }

    public List<String> configNames() {
        return configStore.listNamed();
    }

    public void register(Feature feature) {
        String key = normalize(feature.getName());
        if (features.containsKey(key)) {
            throw new IllegalArgumentException("Duplicate feature name: " + feature.getName());
        }

        feature.setStateListener(this::save);
        features.put(key, feature);
        featuresByType.put(feature.getClass(), feature);
    }

    public Collection<Feature> getFeatures() {
        return Collections.unmodifiableCollection(features.values());
    }

    public List<Feature> getFeatures(FeatureCategory category) {
        List<Feature> result = new ArrayList<>();
        for (Feature feature : features.values()) {
            if (feature.getCategory() == category) {
                result.add(feature);
            }
        }
        return result;
    }

    public Feature getFeature(String name) {
        return features.get(normalize(name));
    }

    public <T extends Feature> T getFeature(Class<T> type) {
        return type.cast(featuresByType.get(type));
    }

    public <T extends Feature> T getEnabled(Class<T> type) {
        T feature = getFeature(type);
        return feature != null && feature.isEnabled() ? feature : null;
    }

    @EventTarget
    public void onKeyboardInput(KeyboardInputEvent event) {
        if (event.getAction() == GLFW.GLFW_PRESS && InputGuard.isBindingBlocked()) {
            return;
        }
        dispatchBind(BindSetting.key(event.getKey()), event.getAction());
        dispatchBooleanSettingBind(BindSetting.key(event.getKey()), event.getAction());
    }

    @EventTarget
    public void onMouseInput(MouseInputEvent event) {
        if (event.getAction() == GLFW.GLFW_PRESS && InputGuard.isBindingBlocked()) {
            return;
        }
        dispatchBind(BindSetting.mouse(event.getButton()), event.getAction());
        dispatchBooleanSettingBind(BindSetting.mouse(event.getButton()), event.getAction());
    }

    @EventTarget
    public void onShutdown(ShutdownEvent event) {
        synchronized (saveLock) {
            if (pendingSave != null) {
                pendingSave.cancel(false);
                pendingSave = null;
            }
        }
        configStore.save(configStore.snapshot(this));
        configWriter.shutdown();
        initialized = false;
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private void dispatchBind(int bindCode, int action) {
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) {
            return;
        }
        for (Feature feature : features.values()) {
            if (feature.supportsBinds()
                    && feature.isToggleable()
                    && feature.getBind().matchesCode(bindCode)) {
                handleBindInput(feature, bindCode, action);
            }
        }
    }

    private void dispatchBooleanSettingBind(int bindCode, int action) {
        if (action != GLFW.GLFW_PRESS) {
            return;
        }
        for (Feature feature : this.features.values()) {
            for (Setting<?> setting : feature.getSettings()) {
                if (!(setting instanceof BooleanSetting booleanSetting) || !booleanSetting.isBindable()) {
                    continue;
                }
                var inputBind = booleanSetting.getInputBind();
                if (inputBind.isBound() && inputBind.getValue() == bindCode) {
                    booleanSetting.setValue(!booleanSetting.getValue());
                }
            }
        }
    }

    private void handleBindInput(Feature feature, int bindCode, int action) {
        if (feature.getBindModeForCode(bindCode) == BindMode.HOLD) {
            Set<Integer> activeCodes = this.activeHoldBinds.computeIfAbsent(feature, ignored -> new LinkedHashSet<>());
            if (action == GLFW.GLFW_PRESS) {
                if (activeCodes.add(bindCode)) {
                    feature.setEnabled(true);
                }
                return;
            }

            activeCodes.remove(bindCode);
            if (activeCodes.isEmpty()) {
                this.activeHoldBinds.remove(feature);
                feature.setEnabled(false);
            }
            return;
        }

        if (action == GLFW.GLFW_PRESS) {
            feature.toggle();
        }
    }
}
