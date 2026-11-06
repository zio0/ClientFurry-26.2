package win.furryhook.event;

import win.furryhook.event.events.game.ElytraPostTravelEvent;
import win.furryhook.event.events.game.FireworkEvent;
import win.furryhook.event.events.game.AttackEvent;
import win.furryhook.event.events.game.EntityAttackEvent;
import win.furryhook.event.events.game.GameTickEvent;
import win.furryhook.event.events.game.PlayerJumpEvent;
import win.furryhook.event.events.game.PlayerTickEvent;
import win.furryhook.event.events.lifecycle.ClientStartEvent;
import win.furryhook.event.events.lifecycle.DisconnectEvent;
import win.furryhook.event.events.lifecycle.ResourceReloadEvent;
import win.furryhook.event.events.lifecycle.ShutdownEvent;
import win.furryhook.event.events.lifecycle.WorldJoinEvent;
import win.furryhook.event.events.lifecycle.WorldLeaveEvent;
import win.furryhook.event.events.render.FinalGuiRenderEvent;
import win.furryhook.event.events.render.Render2DEvent;
import win.furryhook.event.events.render.Render3DEvent;
import win.furryhook.event.events.screen.ScreenCloseEvent;
import win.furryhook.event.events.screen.ScreenKeyEvent;
import win.furryhook.event.events.screen.ScreenMouseButtonEvent;
import win.furryhook.event.events.screen.ScreenOpenEvent;
import win.furryhook.event.events.screen.ScreenRenderEvent;

public class Events {
    public static final ClientStartEvent CLIENT_START = new ClientStartEvent();
    public static final GameTickEvent GAME_TICK = new GameTickEvent();
    public static final PlayerTickEvent PLAYER_TICK = new PlayerTickEvent();
    public static final AttackEvent ATTACK = new AttackEvent();
    public static final EntityAttackEvent ENTITY_ATTACK = new EntityAttackEvent();
    public static final PlayerJumpEvent PLAYER_JUMP = new PlayerJumpEvent();
    public static final FireworkEvent FIREWORK = new FireworkEvent();
    public static final ElytraPostTravelEvent ELYTRA_POST_TRAVEL = new ElytraPostTravelEvent();
    public static final WorldJoinEvent WORLD_JOIN = new WorldJoinEvent();
    public static final WorldLeaveEvent WORLD_LEAVE = new WorldLeaveEvent();
    public static final DisconnectEvent DISCONNECT = new DisconnectEvent();
    public static final ShutdownEvent SHUTDOWN = new ShutdownEvent();
    public static final ResourceReloadEvent RESOURCE_RELOAD = new ResourceReloadEvent();
    public static final ScreenOpenEvent SCREEN_OPEN = new ScreenOpenEvent();
    public static final ScreenCloseEvent SCREEN_CLOSE = new ScreenCloseEvent();
    public static final ScreenRenderEvent SCREEN_RENDER = new ScreenRenderEvent();
    public static final ScreenKeyEvent SCREEN_KEY = new ScreenKeyEvent();
    public static final ScreenMouseButtonEvent SCREEN_MOUSE_BUTTON = new ScreenMouseButtonEvent();
    public static final FinalGuiRenderEvent FINAL_GUI_RENDER = new FinalGuiRenderEvent();
    public static final Render2DEvent RENDER_2D = new Render2DEvent();
    public static final Render3DEvent RENDER_3D = new Render3DEvent();
}
