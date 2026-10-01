package io.goldintelligence.client

import io.goldintelligence.engine.CrossMarketConfirmation
import io.goldintelligence.engine.Direction
import io.goldintelligence.engine.FactorCatalog
import io.goldintelligence.engine.HorizonMode
import io.goldintelligence.engine.IntelligenceReport
import io.goldintelligence.engine.Tier
import io.goldintelligence.ingestion.FactorDiagnostic
import io.goldintelligence.ingestion.FeatureBundle
import io.goldintelligence.ingestion.IndicatorCatalog
import io.goldintelligence.ingestion.LicenseClass
import io.goldintelligence.ingestion.MarketUniverse
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Renders the engine output into the six normative screens of SPEC v2 §18.
 *
 * Display rules enforced here, not in the UI layer, so the server and the app
 * cannot disagree:
 *  - a proxy value always carries a PROXY badge and its `proxy_of` target;
 *  - a gated factor always carries a GATED badge naming its minimum horizon;
 *  - staleness is tri-state (fresh / stale / expired) against the series' own
 *    expected refresh period;
 *  - 5m and 15m never render a numeric probability;
 *  - a value under a restricted licence is never rendered raw.
 */
class ScreenModelBuilder {

    fun build(
        report: IntelligenceReport,
        universe: MarketUniverse,
        features: FeatureBundle,
        diagnostics: List<FactorDiagnostic>
    ): ScreenModel {
        val now = report.generatedAt
        return ScreenModel(
            generatedAt = now,
            specVersion = report.specVersion,
            screens = listOf(
                stateScreen(report, universe, now),
                factorsScreen(report, diagnostics),
                indicatorsScreen(features, universe, now),
                horizonsScreen(report),
                eventsScreen(report, universe),
                diagnosticsScreen(report, universe, features, now)
            ),
            attribution = Providers.all.map { "${it.displayName} — ${it.attribution}" },
            degraded = report.factorScores.isEmpty() || report.dataQuality < 0.5
        )
    }

    /* ---------------- Screen 1 — STATE ---------------- */

    private fun stateScreen(report: IntelligenceReport, u: MarketUniverse, now: Instant): Screen {
        val s = report.state
        val daily = report.horizons.firstOrNull { it.horizon.code == "1D" }
        val spot = report.spotPrice ?: u.scalar(MarketUniverse.GOLD_SPOT)
        val spotPoint = u.point(MarketUniverse.GOLD_SPOT)

        val headline = listOf(
            Row(
                "قیمت نقدی طلا", "Gold Spot",
                spot?.let { fmt(it, 2) + " USD/oz" } ?: NA,
                badges = listOfNotNull(stalenessBadge(spotPoint?.observationTimestamp, Duration.ofMinutes(5), now)),
                noteFa = spotPoint?.source, noteEn = spotPoint?.source,
                emphasis = true
            ),
            Row("جهت", "Direction", directionLabel(s.direction), emphasis = true),
            Row(
                "احتمال (۱ روزه)", "Probability (1D)",
                daily?.probability?.let { pct(it) } ?: NA,
                badges = listOf(Badge(daily?.probabilityStatus ?: "UNKNOWN", BadgeKind.WARN))
            ),
            Row("اطمینان", "Confidence", daily?.confidence?.let { pct(it) } ?: NA, emphasis = true),
            Row("سوگیری طلا", "Gold Bias", daily?.goldBias?.let { fmt(it, 1) } ?: NA),
            Row("وضعیت سیگنال", "Signal State", s.signalState.name),
            Row("عدم‌قطعیت", "Uncertainty", s.uncertainty.name)
        )

        val regime = listOf(
            Row("رژیم", "Regime", s.regime.name),
            Row("پایداری رژیم", "Regime Stability", s.regimeStability.name),
            Row("نقدشوندگی", "Liquidity", s.liquidity.name),
            Row("شوک", "Shock", s.shock.name,
                badges = if (s.shockDetail?.active == true) listOf(Badge("ACTIVE", BadgeKind.ERROR)) else emptyList(),
                noteEn = s.shockDetail?.triggeredConditions?.joinToString()?.ifBlank { null }),
            Row("تأیید بین‌بازاری", "Cross-Market Confirmation",
                report.crossMarketConfirmation.value?.let { pct(it) } ?: NA,
                noteEn = confirmationNote(report.crossMarketConfirmation))
        )

        val drivers = s.primaryDrivers.mapIndexed { i, id ->
            val meta = FactorCatalog.byId[id]
            Row("${i + 1}. ${meta?.nameFa ?: id}", "${i + 1}. ${meta?.nameEn ?: id}",
                report.factorScores.firstOrNull { it.factorId == id }?.let { fmt(it.score, 1) } ?: NA)
        }.ifEmpty { listOf(Row("—", "—", NA)) }

        val conflicts = (s.contradictions + s.conflictNotes).distinct().map {
            Row("تضاد", "Contradiction", it)
        }.ifEmpty { listOf(Row("تضادی ثبت نشد", "No contradiction recorded", "—")) }

        val expected = daily?.expectedMove
        val move = listOf(
            Row("حرکت مورد انتظار", "Expected Move",
                expected?.let { "${fmt(it.point, 2)}" } ?: NA),
            Row("بازه P5–P95", "P5–P95 Band",
                expected?.let { "${fmt(it.p5, 2)} … ${fmt(it.p95, 2)}" } ?: NA),
            Row("ابطال", "Invalidation", s.invalidation ?: NA)
        )

        return Screen(
            ScreenModel.SCREEN_STATE, "وضعیت", "State",
            listOf(
                Section("خلاصه", "Headline", headline),
                Section("رژیم بازار", "Market Regime", regime),
                Section("محرک‌های اصلی", "Primary Drivers", drivers),
                Section("حرکت مورد انتظار", "Expected Move", move),
                Section("تضادها", "Contradictions", conflicts)
            )
        )
    }

    /* ---------------- Screen 2 — FACTORS ---------------- */

    private fun factorsScreen(report: IntelligenceReport, diagnostics: List<FactorDiagnostic>): Screen {
        val daily = report.horizons.firstOrNull { it.horizon.code == "1D" }
        val weightById = daily?.weights?.associate { it.factorId to it.weight } ?: emptyMap()
        val attributionById = daily?.attribution?.associate { it.factorId to it.contribution } ?: emptyMap()

        val rows = FactorCatalog.factors.map { meta ->
            val d = diagnostics.firstOrNull { it.factorId == meta.id }
            val score = d?.score
            val badges = buildList {
                if (d?.isProxy == true) add(Badge("PROXY", BadgeKind.PROXY))
                if (d != null && !d.available) add(Badge("UNAVAILABLE", BadgeKind.ERROR))
                if (daily != null && meta.id in daily.gatedFactorIds) {
                    add(Badge("GATED ≥ ${d?.minHorizon?.code ?: "?"}", BadgeKind.GATED))
                }
            }
            val weight = weightById[meta.id]
            val contribution = attributionById[meta.id]
            Row(
                labelFa = "${meta.id} · ${meta.nameFa}",
                labelEn = "${meta.id} · ${meta.nameEn}",
                value = buildString {
                    append(score?.let { fmt(it, 1) } ?: NA)
                    append("  |  w ")
                    append(weight?.let { pct(it) } ?: fmt(d?.baseWeight ?: 0.0, 3))
                    append("  |  c ")
                    append(contribution?.let { fmt(it, 1) } ?: NA)
                },
                badges = badges,
                noteFa = d?.reason,
                noteEn = d?.reason
            )
        }

        val groups = FactorCatalog.groupWeights.entries.sortedByDescending { it.value }.map {
            Row(it.key, it.key, pct(it.value))
        }

        val interaction = daily?.attribution?.firstOrNull { it.factorId == "INTERACTION_EFFECT" }
        val extras = listOfNotNull(
            interaction?.let { Row("اثر تعامل خوشه‌ای", "Cluster Interaction Effect", fmt(it.contribution, 2)) },
            Row("نسبت تضاد", "Conflict Ratio", daily?.conflictRatio?.let { pct(it) } ?: NA),
            Row("سطح تضاد", "Conflict Level", daily?.conflict?.name ?: NA)
        )

        return Screen(
            ScreenModel.SCREEN_FACTORS, "فاکتورها", "Factors",
            listOf(
                Section("۲۲ فاکتور (امتیاز | وزن مؤثر | سهم)", "22 Factors (score | effective weight | contribution)", rows),
                Section("وزن گروه‌های علّی", "Causal Group Weights", groups),
                Section("ترکیب", "Composition", extras)
            )
        )
    }

    /* ---------------- Screen 3 — INDICATORS ---------------- */

    private fun indicatorsScreen(features: FeatureBundle, u: MarketUniverse, now: Instant): Screen {
        val sections = FactorCatalog.factors.mapNotNull { meta ->
            val catalogRows = IndicatorCatalog.byFactor[meta.id].orEmpty()
            if (catalogRows.isEmpty()) return@mapNotNull null
            val rows = catalogRows.map { ind ->
                val fv = features[ind.key]
                if (fv == null) {
                    Row(ind.labelFa, ind.labelEn, NA,
                        badges = listOf(Badge("NO_DATA", BadgeKind.ERROR)),
                        noteFa = "ورودی در لایه رایگان موجود نیست",
                        noteEn = "Not available in the free data layer")
                } else {
                    val restricted = u.points.values.firstOrNull { it.source == fv.source }?.rawPublishable == false
                    val display = if (restricted) "derived" else "${fmt(fv.value, 4)} ${fv.unit}"
                    Row(
                        ind.labelFa, ind.labelEn, display,
                        badges = buildList {
                            if (fv.isProxy) add(Badge("PROXY", BadgeKind.PROXY))
                            add(Badge("T${tierLabel(fv.tier)}", BadgeKind.INFO))
                            add(Badge("q ${pct(fv.quality)}", if (fv.quality >= 0.75) BadgeKind.OK else BadgeKind.WARN))
                            stalenessBadge(fv.asOf, Duration.ofDays(1), now)?.let { add(it) }
                        },
                        noteFa = fv.note ?: fv.source,
                        noteEn = fv.note ?: fv.source
                    )
                }
            }
            Section("${meta.id} · ${meta.nameFa}", "${meta.id} · ${meta.nameEn}", rows)
        }
        return Screen(ScreenModel.SCREEN_INDICATORS, "شاخص‌ها", "Indicators", sections)
    }

    /* ---------------- Screen 4 — HORIZONS ---------------- */

    private fun horizonsScreen(report: IntelligenceReport): Screen {
        val rows = report.horizons.map { h ->
            val probability = if (h.mode == HorizonMode.DIRECTIONAL_ONLY) "—" else h.probability?.let { pct(it) } ?: NA
            Row(
                labelFa = h.horizon.code,
                labelEn = h.horizon.code,
                value = buildString {
                    append(directionLabel(h.direction))
                    append("  |  p ").append(probability)
                    append("  |  conf ").append(h.confidence?.let { pct(it) } ?: NA)
                    append("  |  cov ").append(pct(h.coverage))
                },
                badges = buildList {
                    add(Badge(h.mode.name, if (h.mode == HorizonMode.CALIBRATED) BadgeKind.OK else BadgeKind.WARN))
                    if (h.killSwitch.engaged) add(Badge("KILL_SWITCH", BadgeKind.ERROR))
                    add(Badge(h.signalState.name, badgeForState(h.signalState.name)))
                },
                noteFa = h.probabilityStatus,
                noteEn = h.probabilityStatus + (if (h.killSwitch.reasons.isNotEmpty()) " · " + h.killSwitch.reasons.joinToString() else "")
            )
        }

        val detail = report.horizons.map { h ->
            Row(
                "${h.horizon.code} — فاکتورهای فعال", "${h.horizon.code} — active factors",
                "${h.activeFactorIds.size}/${h.activeFactorIds.size + h.gatedFactorIds.size}",
                noteEn = if (h.gatedFactorIds.isEmpty()) "none gated" else "gated: " + h.gatedFactorIds.joinToString()
            )
        }

        val moves = report.horizons.map { h ->
            Row(
                "${h.horizon.code} — حرکت مورد انتظار", "${h.horizon.code} — expected move",
                h.expectedMove?.let { "${fmt(it.point, 2)}  (${fmt(it.p5, 2)} … ${fmt(it.p95, 2)})" } ?: NA,
                noteEn = h.expectedMove?.let { "σ = ${fmt(it.sigma, 3)}" }
            )
        }

        return Screen(
            ScreenModel.SCREEN_HORIZONS, "افق‌ها", "Horizons",
            listOf(
                Section("شش افق الزامی", "Six Required Horizons", rows),
                Section("پوشش شواهد", "Evidence Coverage", detail),
                Section("حرکت مورد انتظار", "Expected Move", moves)
            )
        )
    }

    /* ---------------- Screen 5 — EVENTS & NEWS ---------------- */

    private fun eventsScreen(report: IntelligenceReport, u: MarketUniverse): Screen {
        val s = report.state
        val newsRows = listOf(
            Row("وضعیت اخبار", "News State", s.newsState),
            Row(
                "تقویم اقتصادی", "Economic Calendar", NA,
                badges = listOf(Badge("NO_FREE_SOURCE", BadgeKind.ERROR)),
                noteFa = "تقویم با پیش‌بینی اجماع نیازمند مجوز تجاری است",
                noteEn = "A consensus-bearing calendar requires a commercial licence; the free layer cannot supply expected values."
            ),
            Row(
                "جریان اخبار", "News Feed", NA,
                badges = listOf(Badge("CIRCUIT_BREAKER", BadgeKind.WARN)),
                noteFa = "GDELT از محدوده IP سرور محدود شده است",
                noteEn = "GDELT is rate-limited from server IP ranges and stays behind a circuit breaker."
            )
        )

        val scenarios = report.state.scenarioOutlook.map { sc ->
            Row(sc.name, sc.name, directionLabel(sc.expectedDirection),
                noteFa = sc.trigger, noteEn = "${sc.trigger} — invalidation: ${sc.invalidation}")
        }.ifEmpty { listOf(Row("—", "—", NA)) }

        val divergences = report.state.divergences.map {
            Row(it.type.name, it.type.name, it.description)
        }.ifEmpty { listOf(Row("واگرایی ثبت نشد", "No divergence recorded", "—")) }

        val analogues = listOf(
            Row(
                "آنالوگ تاریخی", "Historical Analogue", "NOT_AVAILABLE",
                badges = listOf(Badge("NOT_AVAILABLE", BadgeKind.ERROR)),
                noteFa = "مجموعه‌داده تاریخی برچسب‌خورده وجود ندارد",
                noteEn = "No labelled historical episode dataset exists; the layer reports its absence instead of guessing."
            )
        )

        return Screen(
            ScreenModel.SCREEN_EVENTS, "رویدادها و اخبار", "Events & News",
            listOf(
                Section("اخبار", "News", newsRows),
                Section("سناریوها", "Scenarios", scenarios),
                Section("واگرایی‌ها", "Divergences", divergences),
                Section("آنالوگ تاریخی", "Historical Analogue", analogues)
            )
        )
    }

    /* ---------------- Screen 6 — DIAGNOSTICS ---------------- */

    private fun diagnosticsScreen(
        report: IntelligenceReport,
        u: MarketUniverse,
        features: FeatureBundle,
        now: Instant
    ): Screen {
        val providers = Providers.all.map { spec ->
            val h = u.providerHealth[spec.id]
            Row(
                spec.displayName, spec.displayName,
                h?.let { "${it.state} · ${it.httpStatus ?: "-"} · ${it.latencyMillis ?: "-"} ms" } ?: "NOT_CALLED",
                badges = listOf(
                    Badge(spec.license.name, if (spec.license == io.goldintelligence.ingestion.LicenseClass.RESTRICTED) BadgeKind.WARN else BadgeKind.OK),
                    Badge("T${tierLabel(spec.tier)}", BadgeKind.INFO)
                ),
                noteEn = h?.message
            )
        }

        val quality = listOf(
            Row("کیفیت کل داده", "Aggregate Data Quality", pct(report.dataQuality), emphasis = true),
            Row("تعداد فاکتور فعال", "Active Factors", "${report.factorScores.size}/22"),
            Row("تعداد شاخص دریافتی", "Ingested Indicators", "${features.values.size}/${IndicatorCatalog.indicators.size}"),
            Row("تعداد سری زمانی", "Time Series Loaded", "${u.series.size}"),
            Row("نسخه مشخصات", "Spec Version", report.specVersion),
            Row("زمان تولید", "Generated At", report.generatedAt.toString())
        )

        val dominance = report.dominance.entries.sortedByDescending { it.value }.take(10).map {
            val meta = FactorCatalog.byId[it.key]
            Row(meta?.nameFa ?: it.key, meta?.nameEn ?: it.key, fmt(it.value, 3))
        }.ifEmpty {
            listOf(Row("تسلط اطلاعاتی", "Information Dominance", "1.000 (neutral)",
                noteEn = "No aligned factor/gold history yet; dominance stays neutral rather than being inferred from the current score."))
        }

        val gaps = listOf(
            Row("دفترچه سفارش COMEX", "COMEX order book", "OUT_OF_STACK",
                badges = listOf(Badge("PAID", BadgeKind.ERROR)),
                noteEn = "cmegroup.com answers 403 on every path; replaced by top-of-book and intrabar proxies."),
            Row("اجماع بلومبرگ", "Bloomberg consensus", "OUT_OF_STACK",
                badges = listOf(Badge("PAID", BadgeKind.ERROR)),
                noteEn = "Replaced by market-implied revisions; true surprise versus consensus is not computable."),
            Row("قیمت بنچمارک LBMA", "LBMA benchmark", "OUT_OF_STACK",
                badges = listOf(Badge("PAID", BadgeKind.ERROR)),
                noteEn = "Replaced by a spot reference with cross-source agreement checks."),
            Row("پریمیوم فیزیکی چین/هند", "China / India physical premium", "OUT_OF_STACK",
                badges = listOf(Badge("UNREACHABLE", BadgeKind.ERROR)),
                noteEn = "SGE did not connect and MCX returns 403; replaced by CNY and INR strength."),
            Row("بهره باز روزانه", "Daily open interest", "WEEKLY_ONLY",
                badges = listOf(Badge("DEGRADED", BadgeKind.WARN)),
                noteEn = "Only the CFTC weekly report is free; no free venue publishes daily gold OI.")
        )

        return Screen(
            ScreenModel.SCREEN_DIAGNOSTICS, "تشخیص", "Diagnostics",
            listOf(
                Section("سلامت ارائه‌دهندگان", "Provider Health", providers),
                Section("کیفیت", "Quality", quality),
                Section("تسلط اطلاعاتی", "Information Dominance", dominance),
                Section("شکاف‌های شناخته‌شده", "Known Gaps", gaps)
            )
        )
    }

    /* ---------------- helpers ---------------- */

    private fun confirmationNote(r: CrossMarketConfirmation.Result): String = buildString {
        if (r.agreeing.isNotEmpty()) append("agree: ").append(r.agreeing.joinToString())
        if (r.disagreeing.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append("disagree: ").append(r.disagreeing.joinToString())
        }
        if (r.missing.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append("missing: ").append(r.missing.joinToString())
        }
    }

    private fun badgeForState(state: String): BadgeKind = when (state) {
        "VALID" -> BadgeKind.OK
        "WEAK" -> BadgeKind.WARN
        "INVALID" -> BadgeKind.ERROR
        else -> BadgeKind.INFO
    }

    private fun stalenessBadge(asOf: Instant?, expected: Duration, now: Instant): Badge? {
        if (asOf == null) return null
        val age = Duration.between(asOf, now)
        return when {
            age <= expected -> Badge("FRESH", BadgeKind.FRESH)
            age <= expected.multipliedBy(3) -> Badge("STALE ${humanize(age)}", BadgeKind.STALE)
            else -> Badge("EXPIRED ${humanize(age)}", BadgeKind.EXPIRED)
        }
    }

    private fun humanize(d: Duration): String = when {
        d.toMinutes() < 60 -> "${d.toMinutes()}m"
        d.toHours() < 48 -> "${d.toHours()}h"
        else -> "${d.toDays()}d"
    }

    private fun tierLabel(t: Tier): String = if (t == Tier.PROXY) "P" else t.name

    private fun directionLabel(d: Direction): String = when (d) {
        Direction.BULLISH -> "BULLISH ▲"
        Direction.BEARISH -> "BEARISH ▼"
        Direction.NEUTRAL -> "NEUTRAL ■"
    }

    private fun fmt(v: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)

    private fun pct(v: Double): String = String.format(Locale.US, "%.1f%%", v * 100.0)

    companion object {
        const val NA = "N/A"
    }
}
