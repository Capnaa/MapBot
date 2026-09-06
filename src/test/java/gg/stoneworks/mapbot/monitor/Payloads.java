package gg.stoneworks.mapbot.monitor;

/** Builds markers payloads shaped like the ones the map serves. */
final class Payloads {

    private Payloads() {
    }

    /** A payload carrying {@code count} single-ring claims, named Land0 upward. */
    static String withClaims(int count) {
        StringBuilder markers = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                markers.append(',');
            }
            int x = i * 64;
            markers.append("""
                    {"type":"polygon","color":"#009933","fillColor":"#00ff00",
                     "points":[[{"x":%d,"z":0},{"x":%d,"z":0},{"x":%d,"z":16},{"x":%d,"z":16}]],
                     "popup":"<div><span style=\\"font-size: 200%%;\\"><span style=\\"color: {land_color};\\">Land%d</span><br /></span>Settlement of Owner%d.</div><ul><li>Balance: $100.00</li><li>Chunks: 1</li><li>Created at: 01/01/2026 00:00</li><li>Players (1): Owner%d</li></ul>"}
                    """.formatted(x, x + 16, x + 16, x, i, i, i));
        }
        return """
                [{"id":"squaremap-spawn_icon","name":"Spawn","markers":[]},
                 {"id":"lands_world","name":"Lands","markers":[%s]}]
                """.formatted(markers);
    }
}
