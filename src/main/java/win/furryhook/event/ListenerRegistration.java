package win.furryhook.event;

record ListenerRegistration(
        Class<? extends Event> eventType,
        RegisteredListener listener
) {
}
