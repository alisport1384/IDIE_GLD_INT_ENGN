package io.goldintelligence.client

import java.time.Instant

/**
 * SPEC v2 §18 — the normative app surface, expressed as data.
 *
 * Both execution modes produce this same structure: DIRECT builds it on the
 * device from the engine output, REMOTE receives it from the server's REST
 * contract (§17). The presentation layer therefore has exactly one rendering
 * path, and the server and the app can never drift apart in what they show.
 */
enum class BadgeKind { PROXY, GATED, FRESH, STALE, EXPIRED, OK, WARN, ERROR, INFO }

data class Badge(val text: String, val kind: BadgeKind)

data class Row(
    val labelFa: String,
    val labelEn: String,
    val value: String,
    val badges: List<Badge> = emptyList(),
    val noteFa: String? = null,
    val noteEn: String? = null,
    val emphasis: Boolean = false
)

data class Section(
    val titleFa: String,
    val titleEn: String,
    val rows: List<Row>
)

data class Screen(
    val id: String,
    val titleFa: String,
    val titleEn: String,
    val sections: List<Section>
)

data class ScreenModel(
    val generatedAt: Instant,
    val specVersion: String,
    val screens: List<Screen>,
    val attribution: List<String>,
    val degraded: Boolean,
    val errorFa: String? = null,
    val errorEn: String? = null
) {
    companion object {
        const val SCREEN_STATE = "STATE"
        const val SCREEN_FACTORS = "FACTORS"
        const val SCREEN_INDICATORS = "INDICATORS"
        const val SCREEN_HORIZONS = "HORIZONS"
        const val SCREEN_EVENTS = "EVENTS"
        const val SCREEN_DIAGNOSTICS = "DIAGNOSTICS"

        /**
         * SPEC v2.1 §21 — the operational log lives on its own screen.
         * It is built from [DiagnosticLog] alone and shares no row with the
         * analytical screens, so log content can never be mistaken for a
         * market reading.
         */
        const val SCREEN_LOGS = "LOGS"

        fun error(messageFa: String, messageEn: String, now: Instant = Instant.now()): ScreenModel =
            ScreenModel(
                generatedAt = now,
                specVersion = io.goldintelligence.engine.MultiHorizonEngine.SPEC_VERSION,
                screens = emptyList(),
                attribution = emptyList(),
                degraded = true,
                errorFa = messageFa,
                errorEn = messageEn
            )
    }
}

/** JSON codec for the §17 REST payload. */
object ScreenModelCodec {
    fun encode(m: ScreenModel): String = JsonWriter.obj(
        "generatedAt" to JsonWriter.str(m.generatedAt.toString()),
        "specVersion" to JsonWriter.str(m.specVersion),
        "degraded" to JsonWriter.bool(m.degraded),
        "errorFa" to JsonWriter.str(m.errorFa),
        "errorEn" to JsonWriter.str(m.errorEn),
        "attribution" to JsonWriter.arr(m.attribution) { JsonWriter.str(it) },
        "screens" to JsonWriter.arr(m.screens) { encodeScreen(it) }
    )

    fun encodeScreen(s: Screen): String = JsonWriter.obj(
        "id" to JsonWriter.str(s.id),
        "titleFa" to JsonWriter.str(s.titleFa),
        "titleEn" to JsonWriter.str(s.titleEn),
        "sections" to JsonWriter.arr(s.sections) { sec ->
            JsonWriter.obj(
                "titleFa" to JsonWriter.str(sec.titleFa),
                "titleEn" to JsonWriter.str(sec.titleEn),
                "rows" to JsonWriter.arr(sec.rows) { encodeRow(it) }
            )
        }
    )

    private fun encodeRow(r: Row): String = JsonWriter.obj(
        "labelFa" to JsonWriter.str(r.labelFa),
        "labelEn" to JsonWriter.str(r.labelEn),
        "value" to JsonWriter.str(r.value),
        "emphasis" to JsonWriter.bool(r.emphasis),
        "noteFa" to JsonWriter.str(r.noteFa),
        "noteEn" to JsonWriter.str(r.noteEn),
        "badges" to JsonWriter.arr(r.badges) { b ->
            JsonWriter.obj("text" to JsonWriter.str(b.text), "kind" to JsonWriter.str(b.kind.name))
        }
    )

    fun decode(text: String): ScreenModel? {
        val j = Json.parseOrNull(text) ?: return null
        val generatedAt = j["generatedAt"]?.asString?.let {
            try {
                Instant.parse(it)
            } catch (_: Exception) {
                null
            }
        } ?: Instant.now()
        return ScreenModel(
            generatedAt = generatedAt,
            specVersion = j["specVersion"]?.asString ?: "UNKNOWN",
            screens = j["screens"]?.asArray?.mapNotNull { decodeScreen(it) } ?: emptyList(),
            attribution = j["attribution"]?.asArray?.mapNotNull { it.asString } ?: emptyList(),
            degraded = (j["degraded"] as? Json.Bool)?.value ?: false,
            errorFa = j["errorFa"]?.asString,
            errorEn = j["errorEn"]?.asString
        )
    }

    private fun decodeScreen(j: Json): Screen? {
        val id = j["id"]?.asString ?: return null
        return Screen(
            id = id,
            titleFa = j["titleFa"]?.asString ?: id,
            titleEn = j["titleEn"]?.asString ?: id,
            sections = j["sections"]?.asArray?.map { s ->
                Section(
                    titleFa = s["titleFa"]?.asString ?: "",
                    titleEn = s["titleEn"]?.asString ?: "",
                    rows = s["rows"]?.asArray?.map { r ->
                        Row(
                            labelFa = r["labelFa"]?.asString ?: "",
                            labelEn = r["labelEn"]?.asString ?: "",
                            value = r["value"]?.asString ?: "",
                            badges = r["badges"]?.asArray?.mapNotNull { b ->
                                val t = b["text"]?.asString ?: return@mapNotNull null
                                val k = b["kind"]?.asString?.let { k ->
                                    runCatching { BadgeKind.valueOf(k) }.getOrNull()
                                } ?: BadgeKind.INFO
                                Badge(t, k)
                            } ?: emptyList(),
                            noteFa = r["noteFa"]?.asString,
                            noteEn = r["noteEn"]?.asString,
                            emphasis = (r["emphasis"] as? Json.Bool)?.value ?: false
                        )
                    } ?: emptyList()
                )
            } ?: emptyList()
        )
    }
}
