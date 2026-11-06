package win.furryhook.feature.impl.pve;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

final class ConsumableSelector {
    enum Kind {
        FOOD,
        GOLDEN_APPLE,
        ENCHANTED_GOLDEN_APPLE,
        INVISIBILITY_POTION
    }

    enum Location {
        OFF_HAND,
        MAIN_HAND,
        HOTBAR,
        INVENTORY
    }

    record Candidate<T>(
            T value,
            Kind kind,
            Location location,
            int containerSlot,
            int nutrition,
            float saturation,
            boolean safe
    ) {
        Candidate {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(location, "location");
        }

        double foodValue() {
            return this.nutrition * (double) this.saturation;
        }
    }

    private ConsumableSelector() {
    }

    static <T> Optional<Candidate<T>> selectFood(
            Collection<Candidate<T>> candidates,
            boolean ignoreGoldenApples,
            boolean ignoreEnchantedGoldenApples
    ) {
        Optional<Candidate<T>> held = candidates.stream()
                .filter(candidate -> isAllowedFood(
                        candidate,
                        ignoreGoldenApples,
                        ignoreEnchantedGoldenApples
                ))
                .filter(candidate -> candidate.location() == Location.OFF_HAND
                        || candidate.location() == Location.MAIN_HAND)
                .min(Comparator.comparingInt(candidate ->
                        candidate.location() == Location.OFF_HAND ? 0 : 1));
        if (held.isPresent()) {
            return held;
        }

        return candidates.stream()
                .filter(candidate -> isAllowedFood(
                        candidate,
                        ignoreGoldenApples,
                        ignoreEnchantedGoldenApples
                ))
                .filter(candidate -> candidate.location() == Location.HOTBAR
                        || candidate.location() == Location.INVENTORY)
                .min(foodComparator());
    }

    static <T> Optional<Candidate<T>> selectApple(
            Collection<Candidate<T>> candidates,
            boolean allowGoldenApples,
            boolean allowEnchantedGoldenApples
    ) {
        return candidates.stream()
                .filter(candidate ->
                        candidate.kind() == Kind.GOLDEN_APPLE && allowGoldenApples
                                || candidate.kind() == Kind.ENCHANTED_GOLDEN_APPLE
                                && allowEnchantedGoldenApples)
                .min(Comparator
                        .comparingInt((Candidate<T> candidate) ->
                                candidate.kind() == Kind.ENCHANTED_GOLDEN_APPLE ? 0 : 1)
                        .thenComparingInt(candidate -> locationRank(candidate.location()))
                        .thenComparingInt(Candidate::containerSlot));
    }

    private static <T> Comparator<Candidate<T>> foodComparator() {
        return Comparator
                .comparingInt((Candidate<T> candidate) -> foodRank(candidate))
                .thenComparing(Comparator.comparingDouble(
                        (Candidate<T> candidate) -> candidate.foodValue()
                ).reversed())
                .thenComparingInt(candidate -> locationRank(candidate.location()))
                .thenComparingInt(Candidate::containerSlot);
    }

    private static boolean isAllowedFood(
            Candidate<?> candidate,
            boolean ignoreGoldenApples,
            boolean ignoreEnchantedGoldenApples
    ) {
        return switch (candidate.kind()) {
            case FOOD -> true;
            case GOLDEN_APPLE -> !ignoreGoldenApples;
            case ENCHANTED_GOLDEN_APPLE -> !ignoreEnchantedGoldenApples;
            default -> false;
        };
    }

    private static int foodRank(Candidate<?> candidate) {
        if (candidate.kind() == Kind.FOOD) {
            return candidate.safe() ? 0 : 2;
        }
        return 1;
    }

    private static int locationRank(Location location) {
        return switch (location) {
            case OFF_HAND -> 0;
            case MAIN_HAND -> 1;
            case HOTBAR -> 2;
            case INVENTORY -> 3;
        };
    }
}
