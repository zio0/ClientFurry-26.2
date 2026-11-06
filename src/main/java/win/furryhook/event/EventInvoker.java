package win.furryhook.event;

@FunctionalInterface
public interface EventInvoker {
    void invoke(Object target, Event event);
}
