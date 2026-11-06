package win.furryhook.utils.combat.neuro;

import com.google.gson.JsonObject;

/**
 * Подпись к записанному замаху: чем он кончился и в каких условиях снимался.
 *
 * <p>Кадры описывают <i>как</i> игрок вёл мышь; подпись описывает <i>что это было</i>.
 * Без неё датасет — однородная каша, в которой замах по стоящему боту в толпе весит
 * столько же, сколько чистый размен один на один, и обучаться приходится на среднем от
 * всего сразу. С ней выборку можно сузить до того, что игрок действительно считает своей
 * игрой: дуэли, попадания, плавное ведение.
 *
 * @param outcome    {@link #OUTCOME_HIT} — замах закончился ударом, {@link #OUTCOME_TRACK} —
 *                   ведение без удара (цель ушла, сменилась, кулдаун не дошёл),
 *                   {@link #OUTCOME_LOST} — цель пропала посреди замаха
 * @param duel       один на один: рядом ровно один противник
 * @param opponents  сколько чужих игроков было в радиусе {@link #DUEL_RADIUS} блоков
 * @param smoothness 0..1, доля кадров, в которых мышь шла ровно, без рывка
 * @param avgDistance средняя дистанция до цели за замах, блоков
 * @param ping       пинг на момент записи, мс
 * @param weapon     что было в руке
 */
public record NeuroLabel(
        String outcome,
        boolean duel,
        int opponents,
        float smoothness,
        float avgDistance,
        int ping,
        String weapon
) {
    public static final String OUTCOME_HIT = "hit";
    public static final String OUTCOME_TRACK = "track";
    public static final String OUTCOME_LOST = "lost";

    /** В каком радиусе считаются противники, когда решается, дуэль это или свалка. */
    public static final double DUEL_RADIUS = 16.0D;

    public static final NeuroLabel UNKNOWN = new NeuroLabel(OUTCOME_TRACK, false, 0, 0.0F, 0.0F, 0, "");

    public boolean isHit() {
        return OUTCOME_HIT.equals(this.outcome);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("outcome", this.outcome);
        json.addProperty("duel", this.duel);
        json.addProperty("opponents", this.opponents);
        json.addProperty("smoothness", this.smoothness);
        json.addProperty("distance", this.avgDistance);
        json.addProperty("ping", this.ping);
        json.addProperty("weapon", this.weapon);
        return json;
    }

    /** Разбор подписи из файла. Отсутствующая или битая подпись — {@link #UNKNOWN}, не ошибка. */
    public static NeuroLabel fromJson(JsonObject json) {
        if (json == null) {
            return UNKNOWN;
        }
        try {
            return new NeuroLabel(
                    json.has("outcome") ? json.get("outcome").getAsString() : OUTCOME_TRACK,
                    json.has("duel") && json.get("duel").getAsBoolean(),
                    json.has("opponents") ? json.get("opponents").getAsInt() : 0,
                    json.has("smoothness") ? json.get("smoothness").getAsFloat() : 0.0F,
                    json.has("distance") ? json.get("distance").getAsFloat() : 0.0F,
                    json.has("ping") ? json.get("ping").getAsInt() : 0,
                    json.has("weapon") ? json.get("weapon").getAsString() : ""
            );
        } catch (RuntimeException malformed) {
            return UNKNOWN;
        }
    }

    @Override
    public String toString() {
        return this.outcome
                + (this.duel ? " 1v1" : " " + this.opponents + "v")
                + String.format(java.util.Locale.ROOT, " sm%.2f d%.1f", this.smoothness, this.avgDistance);
    }
}
