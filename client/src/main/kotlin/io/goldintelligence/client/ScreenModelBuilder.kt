package io.goldintelligence.client

import io.goldintelligence.engine.CrossMarketConfirmation
import io.goldintelligence.engine.ChangepointDetector
import io.goldintelligence.engine.Direction
import io.goldintelligence.engine.FactorCatalog
import io.goldintelligence.engine.HorizonMode
import io.goldintelligence.engine.IntelligenceReport
import io.goldintelligence.engine.Tier
import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.ExpectedMoveEngine
import io.goldintelligence.engine.FeatureKeys
import io.goldintelligence.engine.LogEntry
import io.goldintelligence.engine.LogLevel
import io.goldintelligence.engine.RobustAggregate
import io.goldintelligence.engine.Stability
import io.goldintelligence.ingestion.FactorDiagnostic
import io.goldintelligence.ingestion.FeatureBundle
import io.goldintelligence.ingestion.IndicatorCatalog
import io.goldintelligence.ingestion.LicenseClass
import io.goldintelligence.ingestion.MarketUniverse
import io.goldintelligence.ingestion.SeriesAnalogueEngine
import io.goldintelligence.ingestion.SpecFeatureEngineer
import io.goldintelligence.ingestion.WalkForwardCalibration
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
class ScreenModelBuilder(private val log: DiagnosticLog = DiagnosticLog.shared) {

    fun build(
        report: IntelligenceReport,
        universe: MarketUniverse,
        features: FeatureBundle,
        diagnostics: List<FactorDiagnostic>,
        calendar: List<CalendarEvent> = emptyList(),
        books: List<OrderBookDepth> = emptyList(),
        otcTiers: List<SizeTierQuote> = emptyList(),
        goldCurve: List<Pair<String, Double>> = emptyList(),
        chart: ChartPayload? = null
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
                eventsScreen(report, universe, calendar, now),
                diagnosticsScreen(report, universe, features, books, otcTiers, goldCurve, now),
                chartScreen(chart, now),
                logsScreen()
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
                    val display = "${fmt(fv.value, 4)} ${fv.unit}"
                    Row(
                        ind.labelFa, ind.labelEn, display,
                        badges = buildList {
                            if (fv.isProxy) add(Badge("PROXY", BadgeKind.PROXY))
                            add(Badge("T${tierLabel(fv.tier)}", BadgeKind.INFO))
                            add(Badge("q ${pct(fv.quality)}", if (fv.quality >= 0.75) BadgeKind.OK else BadgeKind.WARN))
                            stalenessBadge(fv.asOf, publicationCadence(ind.key), now)?.let { add(it) }
                            // SPEC v2.1 §27 — the standing provenance of the
                            // input, beside the per-observation quality.
                            when (IndicatorCatalog.provenanceOf(ind.key)) {
                                IndicatorCatalog.Provenance.MEASURED -> add(Badge("MEASURED", BadgeKind.OK))
                                IndicatorCatalog.Provenance.DERIVED -> add(Badge("DERIVED", BadgeKind.INFO))
                                IndicatorCatalog.Provenance.PROXY -> add(Badge("STAND-IN", BadgeKind.PROXY))
                            }
                        },
                        noteFa = fv.note ?: fv.source,
                        noteEn = IndicatorCatalog.audit[ind.key]?.note ?: fv.note ?: fv.source
                    )
                }
            }
            Section("${meta.id} · ${meta.nameFa}", "${meta.id} · ${meta.nameEn}", rows)
        }
        // SPEC v2.1 §27 — the provenance audit, stated once at the top so the
        // count is readable without scrolling every factor.
        val counts = IndicatorCatalog.provenanceCounts()
        val measured = counts[IndicatorCatalog.Provenance.MEASURED] ?: 0
        val derived = counts[IndicatorCatalog.Provenance.DERIVED] ?: 0
        val proxy = counts[IndicatorCatalog.Provenance.PROXY] ?: 0
        val auditSection = Section(
            "اصالت داده", "Data provenance",
            listOf(
                Row(
                    "طبقه‌بندی شاخص‌ها", "Indicator classification",
                    "$measured measured  ·  $derived derived  ·  $proxy stand-in",
                    badges = listOf(
                        Badge("MEASURED $measured", BadgeKind.OK),
                        Badge("DERIVED $derived", BadgeKind.INFO),
                        Badge("STAND-IN $proxy", if (proxy == 0) BadgeKind.OK else BadgeKind.PROXY)
                    ),
                    noteEn = "MEASURED is the published series itself, from its publisher. " +
                        "DERIVED is arithmetic on measured series only — a spread, a ratio, a " +
                        "z-score — with the formula stated on the row. STAND-IN is a declared " +
                        "substitute for something no free source publishes; each one names what " +
                        "it replaces and why. No indicator is estimated, modelled or filled in.",
                    noteFa = "اندازه‌گیری‌شده، مشتق‌شده، و جایگزین اعلام‌شده — هیچ عددی تخمین زده نمی‌شود",
                    emphasis = true
                )
            ) + IndicatorCatalog.audit.values
                .filter { it.provenance == IndicatorCatalog.Provenance.PROXY }
                .map { a ->
                    Row(
                        IndicatorCatalog.byKey[a.key]?.labelFa ?: a.key,
                        IndicatorCatalog.byKey[a.key]?.labelEn ?: a.key,
                        "STAND-IN · ${a.source}",
                        badges = listOf(Badge("STAND-IN", BadgeKind.PROXY)),
                        noteEn = a.note
                    )
                }
        )
        return Screen(ScreenModel.SCREEN_INDICATORS, "شاخص‌ها", "Indicators", listOf(auditSection) + sections)
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
                badges = h.expectedMove?.let { listOf(Badge(it.intervalSource, if (it.intervalSource == ExpectedMoveEngine.CONFORMAL) BadgeKind.OK else BadgeKind.INFO)) } ?: emptyList(),
                noteEn = h.expectedMove?.let { m ->
                    "σ = ${fmt(m.sigma, 3)} · half-width ${fmt(m.halfWidthSigma, 3)}σ" +
                        if (m.intervalSource == ExpectedMoveEngine.CONFORMAL) {
                            h.conformal?.let {
                                " · split conformal at ${pct(it.targetCoverage)} nominal, " +
                                    "${it.realisedCoverage?.let { c -> pct(c) } ?: NA} realised on ${it.sampleSize} held-out sessions"
                            } ?: ""
                        } else " · assumed normal quantile; no conformal sample for this horizon"
                }
            )
        }

        return Screen(
            ScreenModel.SCREEN_HORIZONS, "افق‌ها", "Horizons",
            listOf(
                Section("شش افق الزامی", "Six Required Horizons", rows),
                Section("پوشش شواهد", "Evidence Coverage", detail),
                Section("حرکت مورد انتظار", "Expected Move", moves),
                Section("سابقه کالیبراسیون", "Calibration Record", calibrationRows(report))
            )
        )
    }

    /**
     * SPEC v2.1 §26.3 — what the published probability is worth, per horizon.
     *
     * The Murphy decomposition is reported in full because the two halves say
     * different things: reliability is the part a recalibration can remove,
     * resolution is the part that is genuine information. A row only exists
     * for a horizon the walk-forward replay could actually score.
     */
    private fun calibrationRows(report: IntelligenceReport): List<Row> {
        val inference = report.inference
            ?: return listOf(
                Row(
                    "سابقه کالیبراسیون", "Calibration record", "NO_SAMPLE",
                    badges = listOf(Badge("UNCALIBRATED", BadgeKind.WARN)),
                    noteEn = "The walk-forward replay produced no record this cycle; " +
                        "every probability is withheld rather than assumed."
                )
            )

        val rows = ArrayList<Row>()
        val standIns = inference.calibratedOn - inference.measuredOn.toSet()
        rows += Row(
            "پایه کالیبراسیون", "Calibration basis",
            "${inference.calibratedOn.size} legs · " +
                fmt(inference.coveredWeight, 3) + " of normative weight · " +
                fmt(inference.measuredWeight, 3) + " measured",
            badges = listOf(
                Badge("WALK_FORWARD", BadgeKind.OK),
                if (standIns.isEmpty()) Badge("ALL_MEASURED", BadgeKind.OK)
                else Badge("${standIns.size} STAND-IN", BadgeKind.PROXY)
            ),
            noteEn = "Replayed on the published series for " +
                inference.measuredOn.joinToString(", ") +
                (if (standIns.isEmpty()) ""
                else "; stand-ins were used for " + standIns.joinToString(", ")) +
                ". The remaining factors have no free daily history and are not in the sample; " +
                "the mapping is fitted on a composite built on the same scale from a subset of the live inputs.",
            noteFa = "بازپخش روی جایگزین‌های قیمتی عوامل، نه کل بیست‌ودو عامل"
        )

        for (h in report.horizons) {
            val record = h.calibrationRecord ?: continue
            val fit = inference.calibration[h.horizon]
            rows += Row(
                "${h.horizon.code} — امتیاز برایر", "${h.horizon.code} — Brier score",
                "${fmt(record.brier, 4)}  |  skill ${fmt(record.skill, 4)}",
                badges = listOf(
                    Badge(
                        if (record.skill > 0.0) "SKILL_POSITIVE" else "NO_SKILL",
                        if (record.skill > 0.0) BadgeKind.OK else BadgeKind.WARN
                    ),
                    Badge("N=${record.sampleSize}", BadgeKind.INFO)
                ),
                noteEn = "Murphy decomposition: reliability ${fmt(record.reliability, 4)} " +
                    "− resolution ${fmt(record.resolution, 4)} " +
                    "+ uncertainty ${fmt(record.uncertainty, 4)} · " +
                    "base rate ${pct(record.baseRate)} over ${record.bins} quantile bins · " +
                    "fitted on ${fit?.sampleSize ?: 0} earlier sessions, scored out of sample.",
                noteFa = "تجزیه مورفی: اعتمادپذیری، تفکیک‌پذیری و عدم‌قطعیت ذاتی"
            )
            val raw = h.rawProbability
            val published = h.probability
            if (raw != null && published != null) {
                rows += Row(
                    "${h.horizon.code} — تصحیح کالیبراسیون", "${h.horizon.code} — calibration correction",
                    "${pct(raw)} → ${pct(published)}",
                    noteEn = "Logistic link against the isotonic map measured on held-out history; " +
                        "the published figure is the observed frequency for this score, not the curve."
                )
            }
        }
        // SPEC v2.1 §27 — which forecasters were scored, and what each earned.
        for ((horizon, c) in inference.combination) {
            rows += Row(
                "${horizon.code} — ترکیب پیش‌بین‌ها", "${horizon.code} — forecaster combination",
                c.members.joinToString("  |  ") { "${it.name} ${fmt(it.skill, 4)}" },
                badges = listOf(
                    if (c.usedCount > 0) Badge("${c.usedCount} USED", BadgeKind.OK)
                    else Badge("NONE_ADMITTED", BadgeKind.WARN),
                    Badge("N=${c.sampleSize}", BadgeKind.INFO)
                ),
                noteEn = "Each member's out-of-sample Brier skill. A member enters the published " +
                    "probability only on positive skill, pooled in log-odds with weights " +
                    "proportional to that skill (Bates & Granger 1969). " +
                    (if (c.usedCount == 0)
                        "None cleared the bar this cycle, so the probability stays withheld."
                    else "Weights: " + c.members.filter { it.used }
                        .joinToString(", ") { "${it.name} ${fmt(it.weight, 2)}" }) +
                    " ISOTONIC_COMPOSITE reads the normative weighted composite; RIDGE_PANEL is a " +
                    "ridge logistic on the whole standardised panel and does not alter the weight table.",
                noteFa = "مهارت بیرون‌نمونهٔ هر پیش‌بین؛ تنها مهارت مثبت وارد ترکیب می‌شود"
            )
        }

        // SPEC v2.1 §27 — the volatility-conditional bands.
        for ((horizon, m) in inference.mondrian) {
            if (m.buckets.isEmpty()) continue
            rows += Row(
                "${horizon.code} — باند شرطی", "${horizon.code} — conditional band",
                m.buckets.joinToString("  |  ") { "${it.label} ±${fmt(it.band.halfWidth, 2)}σ" },
                badges = listOf(Badge("MONDRIAN", BadgeKind.OK)),
                noteEn = "Split conformal conditioned on the trailing-volatility tercile " +
                    "(Vovk et al. 2005). Realised coverage per bucket: " +
                    m.buckets.joinToString(", ") {
                        "${it.label} ${pct(it.band.realisedCoverage ?: 0.0)} on ${it.band.sampleSize}"
                    } +
                    ". The pooled band is right on average and wrong in both tails; this one is not.",
                noteFa = "باند کانفورمال مشروط بر دهک نوسان، با پوشش محقق‌شدهٔ هر سطل"
            )
        }

        // SPEC v2.1 §27 — which leg actually carried information.
        for ((horizon, legs) in inference.legInformation) {
            val top = legs.take(5)
            if (top.isEmpty()) continue
            rows += Row(
                "${horizon.code} — ضریب اطلاعات", "${horizon.code} — information coefficient",
                top.joinToString("  |  ") { "${it.factorId.take(3)} ${fmt(it.rankCorrelation, 3)}" },
                badges = listOf(
                    Badge("${legs.count { it.significant }}/${legs.size} SIGNIFICANT", BadgeKind.INFO)
                ),
                noteEn = "Spearman rank correlation between each leg's score and the forward " +
                    "return it was meant to anticipate, over ${legs.firstOrNull()?.sampleSize ?: 0} " +
                    "held-out sessions. " + top.joinToString("; ") {
                        "${it.factorId} IC ${fmt(it.rankCorrelation, 4)}, t ${fmt(it.tStatistic, 2)}, " +
                            "hit rate ${pct(it.hitRate)}"
                    } +
                    ". An IC distinguishable from zero is still far too small to move a Brier score; " +
                    "both facts are reported.",
                noteFa = "همبستگی رتبه‌ای هر پایه با بازده آتی، روی داده‌های بیرون‌نمونه"
            )
        }

        if (rows.size == 1) {
            rows += Row(
                "افق‌های کالیبره", "Calibrated horizons", "NONE",
                badges = listOf(Badge("UNCALIBRATED", BadgeKind.WARN)),
                noteEn = "No horizon met the out-of-sample sample-size requirement this cycle."
            )
        }
        return rows
    }

    /* ---------------- Screen 5 — EVENTS & NEWS ---------------- */

    private fun eventsScreen(
        report: IntelligenceReport,
        u: MarketUniverse,
        calendar: List<CalendarEvent>,
        now: Instant
    ): Screen {
        val s = report.state
        val scheduled = calendar.filter { it.time.isAfter(now) }
        val nextHigh = scheduled.filter { it.highImpact }.minByOrNull { it.time }
        val nextAny = scheduled.minByOrNull { it.time }
        val newsRows = listOf(
            Row("وضعیت اخبار", "News State", s.newsState),
            Row(
                "تقویم اقتصادی", "Economic Calendar",
                if (calendar.isEmpty()) NA else "${scheduled.size} رویداد پیش‌رو / scheduled",
                badges = if (calendar.isEmpty()) listOf(Badge("UNAVAILABLE", BadgeKind.ERROR))
                else listOf(Badge("LIVE", BadgeKind.OK)),
                noteFa = "تقویم با پیش‌بینی اجماع و رقم منتشرشده",
                noteEn = "Scheduled releases carrying both the published consensus and the released actual."
            ),
            // SPEC v2.1 §23 — the event clock, which the horizon layer reads.
            Row(
                "تا رویداد پرتأثیر بعدی", "Hours to next high-impact event",
                nextHigh?.let {
                    String.format(Locale.US, "%.1f h", Duration.between(now, it.time).toMinutes() / 60.0)
                } ?: NA,
                badges = buildList {
                    if (nextHigh != null) {
                        val h = Duration.between(now, nextHigh.time).toHours()
                        add(Badge(if (h <= 24) "WITHIN 24H" else "AHEAD",
                            if (h <= 24) BadgeKind.WARN else BadgeKind.OK))
                    } else add(Badge("NONE SCHEDULED", BadgeKind.INFO))
                },
                noteFa = nextHigh?.let { "${it.country} — ${it.title}" },
                noteEn = nextHigh?.let { "${it.country} — ${it.title}" }
                    ?: "No high-impact release is scheduled in the calendar window.",
                emphasis = nextHigh != null &&
                    Duration.between(now, nextHigh.time).toHours() <= 24
            ),
            Row(
                "رویداد بعدی (هر درجه)", "Next scheduled release",
                nextAny?.let {
                    String.format(Locale.US, "%.1f h", Duration.between(now, it.time).toMinutes() / 60.0)
                } ?: NA,
                noteFa = nextAny?.let { "${it.country} — ${it.title}" },
                noteEn = nextAny?.let { "${it.country} — ${it.title}" }
            ),
            Row(
                "جریان اخبار", "News Feed", NA,
                badges = listOf(Badge("CIRCUIT_BREAKER", BadgeKind.WARN)),
                noteFa = "GDELT از محدوده IP سرور محدود شده است",
                noteEn = "GDELT is rate-limited from server IP ranges and stays behind a circuit breaker."
            )
        )

        // SPEC v2.1 — the calendar is a real feed now, so it gets its own section.
        val upcoming = scheduled.sortedBy { it.time }.take(24)
        val calendarRows = upcoming.map { e ->
            val hours = Duration.between(now, e.time).toMinutes() / 60.0
            Row(
                labelFa = e.title,
                labelEn = "${e.country}  ${e.title}",
                value = buildString {
                    append(if (hours < 0.05) "now" else String.format(Locale.US, "T−%.1fh", hours))
                    if (e.forecast != null) append("  |  cons ").append(e.forecast)
                    if (e.previous != null) append("  |  prev ").append(e.previous)
                },
                badges = buildList {
                    add(
                        Badge(
                            e.impact.uppercase(),
                            when {
                                e.highImpact -> BadgeKind.ERROR
                                e.impact.equals("Medium", true) -> BadgeKind.WARN
                                else -> BadgeKind.INFO
                            }
                        )
                    )
                    if (e.forecast != null) add(Badge("CONSENSUS", BadgeKind.OK))
                },
                noteEn = "Published consensus for the scheduled release",
                emphasis = e.highImpact
            )
        }.ifEmpty {
            listOf(
                Row(
                    "رویدادی دریافت نشد", "No calendar events retrieved", NA,
                    badges = listOf(Badge("EMPTY", BadgeKind.WARN)),
                    noteEn = "The calendar feed answered but contained no events for this week."
                )
            )
        }

        val scenarios = report.state.scenarioOutlook.map { sc ->
            Row(sc.name, sc.name, directionLabel(sc.expectedDirection),
                noteFa = sc.trigger, noteEn = "${sc.trigger} — invalidation: ${sc.invalidation}")
        }.ifEmpty { listOf(Row("—", "—", NA)) }

        val divergences = report.state.divergences.map {
            Row(it.type.name, it.type.name, it.description)
        }.ifEmpty { listOf(Row("واگرایی ثبت نشد", "No divergence recorded", "—")) }

        // SPEC v2.1 §24 — matches drawn from the downloaded history itself.
        // The row reports the date, how close it is and what gold did next;
        // nothing is labelled and nothing is extrapolated.
        val matches = report.state.scenarios
        val analogues = if (matches.isEmpty()) {
            listOf(
                Row(
                    "آنالوگ تاریخی", "Historical Analogue", "NOT_AVAILABLE",
                    badges = listOf(Badge("NOT_AVAILABLE", BadgeKind.WARN)),
                    noteFa = "تاریخچهٔ کافی برای تطبیق در این چرخه بارگیری نشد",
                    noteEn = "Not enough history was loaded this cycle to match against; " +
                        "the matcher reports its absence instead of guessing."
                )
            )
        } else {
            val up = matches.count { it.expectedDirection == Direction.BULLISH }
            val down = matches.count { it.expectedDirection == Direction.BEARISH }
            val moves = matches.mapNotNull { it.expectedMagnitude }.sorted()
            val median = if (moves.isEmpty()) null else
                if (moves.size % 2 == 1) moves[moves.size / 2]
                else (moves[moves.size / 2 - 1] + moves[moves.size / 2]) / 2.0
            buildList {
                add(
                    Row(
                        "جمع‌بندی تطبیق", "Match summary",
                        String.format(
                            Locale.US, "%d matches · %d up / %d down · median %s",
                            matches.size, up, down,
                            median?.let { String.format(Locale.US, "%+.2f%%", it) } ?: NA
                        ),
                        badges = listOf(
                            Badge("${SeriesAnalogueEngine.FORWARD_DAYS}D FORWARD", BadgeKind.INFO),
                            Badge(
                                when {
                                    up > down -> "LEANS UP"
                                    down > up -> "LEANS DOWN"
                                    else -> "SPLIT"
                                },
                                when {
                                    up > down -> BadgeKind.OK
                                    down > up -> BadgeKind.WARN
                                    else -> BadgeKind.INFO
                                }
                            )
                        ),
                        noteFa = "نزدیک‌ترین روزهای تاریخی به شرایط امروز، و بازده واقعی طلا پس از آن‌ها",
                        noteEn = "Closest historical sessions to today's conditions, with the gold " +
                            "return actually realised over the " +
                            "${SeriesAnalogueEngine.FORWARD_DAYS} sessions that followed each.",
                        emphasis = true
                    )
                )
                matches.forEach { m ->
                    add(
                        Row(
                            m.name, m.name,
                            m.expectedMagnitude?.let { String.format(Locale.US, "%+.2f%%", it) } ?: NA,
                            badges = listOf(
                                Badge(directionLabel(m.expectedDirection), badgeForDirection(m.expectedDirection)),
                                Badge(
                                    m.probability?.let { String.format(Locale.US, "SIM %.0f%%", it * 100.0) }
                                        ?: "SIM —",
                                    BadgeKind.INFO
                                )
                            ),
                            noteFa = m.trigger,
                            noteEn = m.trigger
                        )
                    )
                }
            }
        }

        return Screen(
            ScreenModel.SCREEN_EVENTS, "رویدادها و اخبار", "Events & News",
            listOf(
                Section("اخبار", "News", newsRows),
                Section("تقویم اقتصادی (اجماع)", "Economic Calendar (consensus)", calendarRows),
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
        books: List<OrderBookDepth>,
        otcTiers: List<SizeTierQuote>,
        goldCurve: List<Pair<String, Double>>,
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

        /* SPEC v2.1 — every entry below was previously OUT_OF_STACK,
         * UNREACHABLE or WEEKLY_ONLY. Each now names the live source that
         * replaced it. A row only claims RESOLVED when the value is actually
         * present in this refresh. */
        fun resolution(
            labelFa: String,
            labelEn: String,
            present: Boolean,
            sourceEn: String,
            sourceFa: String,
            fallbackEn: String
        ) = Row(
            labelFa, labelEn,
            if (present) "RESOLVED" else "DEGRADED",
            badges = listOf(
                if (present) Badge("LIVE", BadgeKind.OK) else Badge("RETRY", BadgeKind.WARN)
            ),
            noteFa = if (present) sourceFa else fallbackEn,
            noteEn = if (present) sourceEn else fallbackEn
        )

        val gaps = listOf(
            resolution(
                "عمق دفتر سفارش طلا", "Gold order book depth",
                books.isNotEmpty(),
                "Full L2 depth from Kraken PAXG/USD, OKX XAUT/USDT and Coinbase PAXG/USD — " +
                    "allocated gold, one token per fine troy ounce. " +
                    books.joinToString(" · ") { "${it.venue} ${it.bidLevels}+${it.askLevels} levels" },
                "عمق کامل دفتر سفارش از کراکن، OKX و کوین‌بیس",
                "Every gold venue failed this cycle; the ETF top-of-book proxy is in use."
            ),
            resolution(
                "اجماع تحلیلگران", "Analyst consensus",
                features[FeatureKeys.CALENDAR_HIGH_IMPACT_24H] != null ||
                    features[FeatureKeys.CONSENSUS_SURPRISE] != null,
                "Scheduled releases for the US, euro area, UK, Japan and China with the published " +
                    "consensus and the released actual on the same row, paired with official prints " +
                    "from the U.S. Bureau of Labor Statistics. " +
                    (features[FeatureKeys.ECONOMIC_SURPRISE_INDEX]?.let {
                        String.format(Locale.US, "Surprise index %+.1f.", it.value)
                    } ?: ""),
                "اجماع و داده واقعی هر انتشار، به‌همراه ارقام رسمی اداره آمار کار آمریکا",
                "The calendar feed did not answer this cycle."
            ),
            resolution(
                "قیمت بنچمارک LBMA", "LBMA benchmark",
                features[FeatureKeys.LBMA_BENCHMARK] != null,
                "LBMA-based benchmark series published by the World Gold Council, cross-checked " +
                    "against " + (features[FeatureKeys.SPOT_SOURCE_DISPERSION]?.let {
                        String.format(Locale.US, "independent spot feeds agreeing to %.1f bp", it.value)
                    } ?: "the independent spot feeds") + ".",
                "سری بنچمارک مبتنی بر LBMA از شورای جهانی طلا",
                "The World Gold Council feed did not answer this cycle."
            ),
            resolution(
                "پریمیوم فیزیکی چین", "China physical premium",
                features[FeatureKeys.CHINA_PREMIUM] != null,
                "Shanghai Gold Exchange Au(T+D) and SHFE front-month, converted at the ECB CNY " +
                    "reference rate. " + (features[FeatureKeys.CHINA_PREMIUM]?.let {
                        String.format(Locale.US, "Current premium %+.2f%%.", it.value)
                    } ?: ""),
                "قرارداد Au(T+D) بورس طلای شانگهای",
                "Neither the Shanghai nor the SHFE quote arrived this cycle."
            ),
            resolution(
                "پریمیوم فیزیکی هند", "India physical premium",
                features[FeatureKeys.INDIA_PREMIUM] != null,
                "MCX front-month gold converted at the ECB INR reference rate. " +
                    (features[FeatureKeys.INDIA_PREMIUM]?.let {
                        String.format(
                            Locale.US,
                            "Current premium %+.2f%%, of which %.1f pp is statutory duty and tax.",
                            it.value, SpecFeatureEngineer.INDIA_STRUCTURAL_WEDGE_PCT
                        )
                    } ?: ""),
                "قرارداد طلای MCX هند",
                "The MCX quote did not arrive this cycle."
            ),
            resolution(
                "بهره باز روزانه", "Daily open interest",
                features[FeatureKeys.OPEN_INTEREST] != null,
                "COMEX front-month open interest, published daily. " +
                    (features[FeatureKeys.OPEN_INTEREST]?.let {
                        String.format(Locale.US, "%,.0f contracts.", it.value)
                    } ?: ""),
                "بهره باز قرارداد نزدیک COMEX، روزانه",
                "The daily print did not arrive; the CFTC weekly report remains available."
            ),
            resolution(
                "منحنی آتی COMEX", "COMEX forward curve",
                goldCurve.isNotEmpty(),
                "Dated COMEX contracts: " + goldCurve.joinToString(" · ") {
                    "${it.first} ${String.format(Locale.US, "%.1f", it.second)}"
                },
                "قراردادهای تاریخ‌دار COMEX",
                "No dated contract quoted this cycle."
            ),
            resolution(
                "تقاضای بانک مرکزی", "Central bank demand",
                features[FeatureKeys.CENTRAL_BANK_NET_BUYING_3M] != null,
                "Monthly gold holdings reported by national authorities under the IMF reserves " +
                    "template (IRFCL line 56, fine troy ounces). " +
                    (features[FeatureKeys.CENTRAL_BANK_NET_BUYING_3M]?.note ?: "") +
                    (features[FeatureKeys.CENTRAL_BANK_PROXY_FLOW]?.let {
                        String.format(Locale.US, " Breadth %+.0f%%.", it.value)
                    } ?: ""),
                "ذخایر طلای ماهانه کشورها بر پایه الگوی ذخایر صندوق بین‌المللی پول",
                "The reserves template did not answer this cycle; F10 reports its absence."
            ),
            resolution(
                "واگرایی نرخ سیاستی", "Policy rate divergence",
                features[FeatureKeys.GLOBAL_CB_POLICY_DIVERGENCE]?.isProxy == false,
                "Policy rates published by the central banks themselves via the BIS: US against the " +
                    "mean of the euro area, UK, Japan, Switzerland and Canada. " +
                    (features[FeatureKeys.GLOBAL_CB_POLICY_DIVERGENCE]?.let {
                        String.format(Locale.US, "Current gap %+.2f pp.", it.value)
                    } ?: ""),
                "نرخ‌های سیاستی بانک‌های مرکزی از طریق بانک تسویه بین‌المللی",
                "The policy rate table did not answer this cycle; the dollar-trend proxy is in use."
            ),
            resolution(
                "آنالوگ تاریخی", "Historical analogue",
                report.state.scenarios.isNotEmpty(),
                "Matched against the stack's own daily history: the closest sessions by " +
                    "gold trend and volatility, the dollar, equities, long bonds and equity fear, " +
                    "each reported with the gold return realised over the " +
                    "${SeriesAnalogueEngine.FORWARD_DAYS} sessions that followed. " +
                    "${report.state.scenarios.size} non-overlapping matches this cycle. " +
                    "No episode is labelled — no free dataset of labelled episodes exists.",
                "تطبیق با تاریخچهٔ روزانهٔ خود برنامه، بدون برچسب‌گذاری رویدادها",
                "Not enough history was loaded this cycle for a match."
            )
        )

        val microstructure = buildList {
            books.forEach { b ->
                add(
                    Row(
                        b.venue, "${b.venue} — ${b.instrument}",
                        String.format(
                            Locale.US,
                            "bid %.2f / ask %.2f  |  %.1f bp  |  %.1f oz bid vs %.1f oz ask",
                            b.bestBid, b.bestAsk, b.spreadBp, b.bidVolume, b.askVolume
                        ),
                        badges = listOf(
                            Badge("${b.bidLevels + b.askLevels} LEVELS", BadgeKind.OK),
                            Badge(
                                String.format(Locale.US, "IMB %+.3f", b.imbalance),
                                if (b.imbalance >= 0) BadgeKind.OK else BadgeKind.WARN
                            )
                        ),
                        noteEn = "Live limit order book"
                    )
                )
            }
            otcTiers.forEach { t ->
                add(
                    Row(
                        "OTC ${t.tier}", "OTC ${t.tier} tier",
                        String.format(Locale.US, "bid %.3f / ask %.3f  |  %.1f bp", t.bid, t.ask, t.spreadBp),
                        badges = listOf(Badge("OTC", BadgeKind.INFO)),
                        noteEn = "Swissquote public best bid/offer by trade size"
                    )
                )
            }
            if (isEmpty()) add(Row("عمق بازار", "Market depth", NA,
                badges = listOf(Badge("NO_BOOK", BadgeKind.ERROR))))
        }

        val curveRows = goldCurve.map { (code, px) ->
            val front = goldCurve.first().second
            Row(
                code, code,
                String.format(Locale.US, "%.1f", px),
                badges = if (code == goldCurve.first().first) listOf(Badge("FRONT", BadgeKind.OK))
                else listOf(
                    Badge(
                        String.format(Locale.US, "%+.2f%%", (px / front - 1.0) * 100.0),
                        if (px >= front) BadgeKind.INFO else BadgeKind.WARN
                    )
                ),
                noteEn = "COMEX dated contract settlement"
            )
        }.ifEmpty { listOf(Row("منحنی آتی", "Forward curve", NA,
            badges = listOf(Badge("NO_CURVE", BadgeKind.ERROR)))) }

        return Screen(
            ScreenModel.SCREEN_DIAGNOSTICS, "تشخیص", "Diagnostics",
            listOf(
                Section("سلامت ارائه‌دهندگان", "Provider Health", providers),
                Section("کیفیت", "Quality", quality),
                Section("تسلط اطلاعاتی", "Information Dominance", dominance),
                Section("عمق بازار", "Market Depth", microstructure),
                Section("منحنی آتی COMEX", "COMEX Forward Curve", curveRows),
                Section("لایه استنتاج", "Inference Layer", inferenceRows(report)),
                Section("وضعیت شکاف‌های داده", "Data Gap Status", gaps)
            )
        )
    }

    /**
     * SPEC v2.1 §26.4–§26.7 — the structural reads that qualify the composite:
     * how many independent bets it really contains, how old the regime is,
     * whether the tape trends at all, and whether the call survives the
     * removal of its loudest contributors.
     */
    private fun inferenceRows(report: IntelligenceReport): List<Row> {
        val inference = report.inference
            ?: return listOf(
                Row(
                    "لایه استنتاج", "Inference layer", "NOT_RUN",
                    badges = listOf(Badge("NO_SAMPLE", BadgeKind.WARN)),
                    noteEn = "No history was available to the walk-forward replay this cycle."
                )
            )

        return buildList {
            inference.effectiveBreadth?.let { enb ->
                val ratio = inference.breadthRatio
                add(
                    Row(
                        "تعداد مؤثر شرط‌ها", "Effective number of bets",
                        fmt(enb, 2) + (ratio?.let { " of ${inference.calibratedOn.size}  (${pct(it)})" } ?: ""),
                        badges = listOf(
                            Badge(
                                if ((ratio ?: 1.0) >= 0.6) "INDEPENDENT" else "REDUNDANT",
                                if ((ratio ?: 1.0) >= 0.6) BadgeKind.OK else BadgeKind.WARN
                            )
                        ),
                        noteEn = "Exponential entropy of the correlation matrix eigenvalues (Meucci 2009). " +
                            "Factors that move together are one witness, not many; the model-agreement term " +
                            "of the confidence formula is shrunk toward neutral by this ratio.",
                        noteFa = "آنتروپی نمایی مقادیر ویژه ماتریس همبستگی عوامل"
                    )
                )
            }
            inference.runLength?.let { r ->
                add(
                    Row(
                        "سن رژیم", "Regime age",
                        "${r.mapRunLength} sessions  |  P(change) ${pct(r.changeProbability)}",
                        badges = listOf(
                            Badge(r.stability.name, when (r.stability) {
                                Stability.HIGH -> BadgeKind.OK
                                Stability.MEDIUM -> BadgeKind.INFO
                                else -> BadgeKind.WARN
                            })
                        ),
                        noteEn = "Bayesian online changepoint detection over ${r.observations} daily returns " +
                            "(Adams & MacKay 2007): Normal-Inverse-Gamma conjugate predictive, constant hazard. " +
                            "P(regime younger than ${ChangepointDetector.YOUNG_RUN} sessions) = " +
                            pct(r.youngRegimeProbability) + ".",
                        noteFa = "تشخیص نقطه تغییر بیزی برخط روی بازده‌های روزانه"
                    )
                )
            }
            inference.trend?.let { t ->
                add(
                    Row(
                        "اعتبار روند", "Trend validity",
                        "VR(${t.lag}) ${fmt(t.varianceRatio, 3)}  |  z ${fmt(t.zStatistic, 2)}" +
                            (t.hurst?.let { "  |  H ${fmt(it, 3)}" } ?: ""),
                        badges = listOf(
                            Badge(t.label, if (t.label == "TRENDING") BadgeKind.OK else BadgeKind.WARN),
                            Badge("CRED ${pct(t.momentumCredibility)}", BadgeKind.INFO)
                        ),
                        noteEn = "Lo & MacKinlay (1988) heteroskedasticity-robust variance ratio over " +
                            "${t.observations} returns, with the rescaled-range Hurst exponent. " +
                            "|z| below 1.96 means the random walk cannot be rejected, and the expected-move " +
                            "point estimate is scaled by the credibility shown rather than asserted at full size.",
                        noteFa = "آزمون نسبت واریانس لو و مک‌کینلی به‌همراه نمای هرست"
                    )
                )
            }
            inference.filtered?.let { f ->
                add(
                    Row(
                        "سوگیری صاف‌شده پنل", "Filtered panel composite",
                        fmt(f.level, 2) + " ± " + fmt(f.standardError, 2),
                        badges = listOf(Badge("KALMAN", BadgeKind.INFO)),
                        noteEn = "One-dimensional local-level Kalman filter over ${f.observations} sessions of " +
                            "the replayed composite, steady-state gain ${fmt(f.gain, 3)}. " +
                            "Reported beside the raw bias so a sign flip inside this band is readable as noise.",
                        noteFa = "فیلتر کالمن سطح محلی روی سوگیری مرکب"
                    )
                )
            }
            inference.robustness?.let { r ->
                add(
                    Row(
                        "شکنندگی پنل", "Panel fragility",
                        "weighted ${fmt(r.weighted, 2)}  |  trimmed ${fmt(r.trimmed, 2)}  |  gap ${fmt(r.fragility, 2)}",
                        badges = listOf(
                            Badge(
                                if (r.fragile) "FRAGILE" else "ROBUST",
                                if (r.fragile) BadgeKind.WARN else BadgeKind.OK
                            )
                        ),
                        noteEn = "The replayed panel composite against the trimmed mean of its ${r.contributors} " +
                            "price-derived legs, with the top and bottom " + pct(RobustAggregate.TRIM_FRACTION) +
                            " removed; median ${fmt(r.median, 2)}. A gap above " +
                            "${fmt(RobustAggregate.FRAGILE_GAP, 0)} points means the panel reading rests on " +
                            "one or two legs rather than on the whole panel.",
                        noteFa = "ترکیب پس از حذف بلندترین و کوتاه‌ترین سهم‌ها"
                    )
                )
            }
            if (isEmpty()) {
                add(
                    Row(
                        "لایه استنتاج", "Inference layer", "NO_OUTPUT",
                        badges = listOf(Badge("NO_SAMPLE", BadgeKind.WARN)),
                        noteEn = "Not enough history for any of the §26 components to answer."
                    )
                )
            }
        }
    }

    /* ---------------- Screen 7 — CHART (SPEC v2.1 §22) ----------------
     * The textual mirror of the chart overlay: the app draws it on the price
     * axis, the API returns the same content as rows so a client that cannot
     * draw still receives every published conclusion.
     */

    private fun chartScreen(chart: ChartPayload?, now: Instant): Screen {
        if (chart == null) {
            return Screen(
                ScreenModel.SCREEN_CHART, "چارت زنده", "Live Chart",
                listOf(
                    Section(
                        "فید", "Feed",
                        listOf(
                            Row(
                                "فید زنده", "Live feed", NA,
                                listOf(Badge("UNAVAILABLE", BadgeKind.ERROR)),
                                noteFa = "فید بروکر در این اجرا ساخته نشد.",
                                noteEn = "The broker feed was not built on this run."
                            )
                        )
                    )
                )
            )
        }

        val q = chart.quote
        val feed = mutableListOf(
            Row(
                "نماد", "Symbol", chart.symbol,
                listOf(
                    Badge(chart.venue, BadgeKind.INFO),
                    Badge(q?.updateMode?.uppercase() ?: "NO_QUOTE",
                        if (q?.updateMode.equals("streaming", true)) BadgeKind.FRESH else BadgeKind.WARN)
                ),
                noteFa = chart.venueLabel, noteEn = chart.venueLabel, emphasis = true
            )
        )
        if (q != null) {
            feed += Row(
                "قیمت زنده", "Live price", "%.3f".format(q.last),
                listOf(Badge(fmtSigned(q.changePct, 2) + "%", if ((q.changePct ?: 0.0) >= 0) BadgeKind.OK else BadgeKind.WARN)),
                noteFa = "دریافت ${fmtAge(q.receivedAt, now)} · مهر جلسه ${q.quotedAt}",
                noteEn = "received ${fmtAge(q.receivedAt, now)} · session stamp ${q.quotedAt}",
                emphasis = true
            )
            feed += Row(
                "خرید / فروش", "Bid / Ask",
                "${fmtOrNa(q.bid, 3)} / ${fmtOrNa(q.ask, 3)}",
                listOfNotNull(
                    q.spreadBp?.let { Badge("%.1f bp".format(it), BadgeKind.INFO) }
                        ?: Badge("DROPPED", BadgeKind.WARN)
                ),
                noteFa = if (q.bid == null) "اسپرد منتشرشدهٔ بروکر با آخرین قیمت هم‌خوان نبود و کنار گذاشته شد." else null,
                noteEn = if (q.bid == null) "The venue's quoted spread disagreed with the last price and was dropped." else null
            )
            feed += Row(
                "بازه روز", "Session range",
                "${fmtOrNa(q.low, 2)} – ${fmtOrNa(q.high, 2)}",
                noteFa = "باز ${fmtOrNa(q.open, 2)}", noteEn = "open ${fmtOrNa(q.open, 2)}"
            )
        }
        feed += Row(
            "سری کندل", "Candle series",
            "${chart.series.candles.size} × ${chart.timeframe.code}",
            listOf(
                Badge("LIVE ${chart.series.liveBars}", if (chart.series.liveBars > 0) BadgeKind.OK else BadgeKind.WARN),
                Badge("SEED ${chart.series.seededBars}", BadgeKind.PROXY)
            ),
            noteFa = chart.series.seedSource?.let {
                "پیش‌بار از $it با ضریب ${"%.6f".format(chart.series.rebaseFactor ?: 1.0)} بازمقیاس شد"
            },
            noteEn = chart.series.seedSource?.let {
                "seeded from $it, rebased by ${"%.6f".format(chart.series.rebaseFactor ?: 1.0)}"
            }
        )

        val h = chart.headline
        val verdict = listOf(
            Row(
                "جهت", "Direction", h.direction,
                listOf(Badge(h.horizon, BadgeKind.INFO), Badge(h.signalState, badgeForState(h.signalState))),
                emphasis = true
            ),
            Row("سوگیری", "Bias", fmtOrNa(h.bias, 1)),
            Row("اطمینان", "Confidence", h.confidence?.let { "%.1f%%".format(it * 100) } ?: NA),
            Row("رژیم", "Regime", h.regime),
            Row(
                "احتمال", "Probability", h.probabilityStatus,
                listOf(Badge(h.probabilityStatus, BadgeKind.INFO))
            )
        ) + listOfNotNull(
            h.killSwitch?.let {
                Row("کلید قطع", "Kill switch", "ENGAGED", listOf(Badge("STOP", BadgeKind.ERROR)),
                    noteFa = it, noteEn = it, emphasis = true)
            }
        )

        val levels = chart.levels.map { l ->
            Row(
                l.labelFa, l.labelEn, "%.3f".format(l.price),
                listOf(Badge(l.kind.name, levelBadge(l.kind)))
            )
        }.ifEmpty { listOf(Row("—", "No level anchored", NA)) }

        val deltas = chart.deltas.map { d ->
            Row(
                d.labelFa, d.labelEn,
                if (d.changed) "${d.previous} → ${d.current}" else d.current,
                listOf(
                    Badge(
                        when {
                            d.firstObservation -> "FIRST"
                            d.changed && d.direction > 0 -> "UP"
                            d.changed && d.direction < 0 -> "DOWN"
                            d.changed -> "CHANGED"
                            else -> "SAME"
                        },
                        when {
                            !d.changed -> BadgeKind.INFO
                            d.direction > 0 -> BadgeKind.OK
                            d.direction < 0 -> BadgeKind.WARN
                            else -> BadgeKind.INFO
                        }
                    )
                ),
                emphasis = d.changed
            )
        }

        val dual = chart.dual.flatMap { d ->
            listOf(
                Row(
                    d.primary.labelFa, d.primary.labelEn, d.primary.direction,
                    listOf(
                        Badge(d.kind.name, BadgeKind.WARN),
                        Badge("قوت %.2f".format(d.primary.strength), BadgeKind.INFO)
                    ),
                    noteFa = d.primary.detailFa, noteEn = d.primary.detailEn, emphasis = true
                ),
                Row(
                    d.secondary.labelFa, d.secondary.labelEn, d.secondary.direction,
                    listOf(
                        Badge("خوانش دوم / second reading", BadgeKind.WARN),
                        Badge("قوت %.2f".format(d.secondary.strength), BadgeKind.INFO)
                    ),
                    noteFa = d.secondary.detailFa, noteEn = d.secondary.detailEn, emphasis = true
                ),
                Row("دلیل", "Reason", "—", noteFa = d.reasonFa, noteEn = d.reasonEn)
            )
        }.ifEmpty {
            listOf(
                Row(
                    "خوانش دوگانه", "Dual reading", "NONE",
                    listOf(Badge("SINGLE", BadgeKind.OK)),
                    noteFa = "افق‌ها و فاکتورها هم‌جهت‌اند.",
                    noteEn = "Horizons and factors agree."
                )
            )
        }

        val exceptions = chart.exceptions.map { e ->
            Row(
                e.code, e.code, e.component,
                listOf(Badge(e.severity, if (e.severity == "ERROR") BadgeKind.ERROR else BadgeKind.WARN)),
                noteFa = e.messageFa, noteEn = e.messageEn, emphasis = e.severity == "ERROR"
            )
        }.ifEmpty { listOf(Row("—", "No exception", "CLEAN", listOf(Badge("OK", BadgeKind.OK)))) }

        return Screen(
            ScreenModel.SCREEN_CHART, "چارت زنده", "Live Chart",
            listOf(
                Section("فید بروکر", "Broker Feed", feed),
                Section("تحلیل نهایی روی چارت", "Final Analysis On Chart", verdict),
                Section("سطوح قفل‌شده به قیمت", "Price-Anchored Levels", levels),
                Section("تغییر نسبت به اجرای قبلی", "Change Since Last Refresh", deltas),
                Section("تحلیل دوگانه", "Dual Analysis", dual),
                Section("اکسپشن‌ها", "Exceptions", exceptions)
            )
        )
    }

    private fun levelBadge(kind: LevelKind): BadgeKind = when (kind) {
        LevelKind.SPOT -> BadgeKind.FRESH
        LevelKind.BID, LevelKind.ASK -> BadgeKind.INFO
        LevelKind.INVALIDATION -> BadgeKind.ERROR
        LevelKind.EXPECTED_MOVE -> BadgeKind.OK
        LevelKind.BAND_HIGH, LevelKind.BAND_LOW -> BadgeKind.INFO
        LevelKind.BENCHMARK -> BadgeKind.PROXY
    }

    private fun fmtOrNa(v: Double?, digits: Int): String =
        if (v == null) NA else "%.${digits}f".format(v)

    private fun fmtSigned(v: Double?, digits: Int): String =
        if (v == null) NA else "%+.${digits}f".format(v)

    private fun fmtAge(at: Instant, now: Instant): String {
        val s = java.time.Duration.between(at, now).seconds
        return when {
            s < 0 -> "now"
            s < 90 -> "${s}s ago"
            s < 5400 -> "${s / 60}m ago"
            else -> "${s / 3600}h ago"
        }
    }

    /* ---------------- Screen 7 — LOGS (SPEC v2.1 §21) ----------------
     * Built only from the operational log. No market value is read here and
     * no log line is written into any other screen, so the two surfaces stay
     * strictly separate.
     */

    private fun logsScreen(): Screen {
        val counts = log.countsByLevel()
        val entries = log.snapshot()
        val summary = listOf(
            Row(
                "وضعیت ضبط", "Recording",
                if (log.isRecording()) "ON" else "OFF",
                badges = listOf(
                    Badge(if (log.isRecording()) "LIVE" else "IDLE",
                        if (log.isRecording()) BadgeKind.OK else BadgeKind.INFO)
                ),
                noteFa = if (log.isRecording()) "گزارش‌گیری روشن است و رویدادها ثبت می‌شوند"
                else "گزارش‌گیری خاموش است؛ هیچ رکوردی نگهداری نمی‌شود",
                noteEn = if (log.isRecording())
                    "Recording; every stage writes here."
                else
                    "Switched off by default — nothing is retained. " +
                        "${log.suppressedCount()} records were not taken.",
                emphasis = true
            ),
            Row("مجموع رکوردها", "Records", "${entries.size}", emphasis = true),
            Row("خطا", "Errors", "${counts[LogLevel.ERROR] ?: 0}",
                badges = listOf(
                    Badge(
                        if ((counts[LogLevel.ERROR] ?: 0) == 0) "CLEAN" else "ATTENTION",
                        if ((counts[LogLevel.ERROR] ?: 0) == 0) BadgeKind.OK else BadgeKind.ERROR
                    )
                )),
            Row("هشدار", "Warnings", "${counts[LogLevel.WARN] ?: 0}",
                badges = listOf(
                    Badge(
                        if ((counts[LogLevel.WARN] ?: 0) == 0) "CLEAN" else "REVIEW",
                        if ((counts[LogLevel.WARN] ?: 0) == 0) BadgeKind.OK else BadgeKind.WARN
                    )
                )),
            Row("اطلاع", "Info", "${counts[LogLevel.INFO] ?: 0}"),
            Row("اشکال‌زدایی", "Debug", "${counts[LogLevel.DEBUG] ?: 0}"),
            Row("حذف‌شده (حلقه پر)", "Dropped (ring full)", "${log.droppedCount()}")
        )

        val byStage = log.countsByStage().map { (stage, n) ->
            Row(stage.name, stage.name, "$n")
        }.ifEmpty { listOf(Row("—", "No stage recorded", NA)) }

        val failures = log.failures().takeLast(120).reversed().map { e ->
            Row(
                labelFa = e.key ?: e.component,
                labelEn = (e.key ?: e.component) + "  ·  " + e.component,
                value = e.code,
                badges = buildList {
                    add(Badge(e.level.name, if (e.level == LogLevel.ERROR) BadgeKind.ERROR else BadgeKind.WARN))
                    add(Badge(e.stage.name, BadgeKind.INFO))
                    e.httpStatus?.let { add(Badge("HTTP $it", BadgeKind.WARN)) }
                },
                noteFa = e.message,
                noteEn = e.message + (e.detail?.let { "  |  $it" } ?: "") +
                    (e.url?.let { "  |  $it" } ?: ""),
                emphasis = e.level == LogLevel.ERROR
            )
        }.ifEmpty {
            listOf(Row("بدون خطا", "No failures", "CLEAN",
                badges = listOf(Badge("OK", BadgeKind.OK)),
                noteEn = "Every indicator resolved in this refresh."))
        }

        val perIndicator = log.indicatorStatuses().map { st ->
            Row(
                labelFa = st.key,
                labelEn = st.key,
                value = if (st.ok) "OK" else st.code,
                badges = listOf(
                    Badge(
                        st.level.name,
                        when (st.level) {
                            LogLevel.ERROR -> BadgeKind.ERROR
                            LogLevel.WARN -> BadgeKind.WARN
                            else -> BadgeKind.OK
                        }
                    ),
                    Badge(st.component, BadgeKind.INFO)
                ),
                noteFa = st.message,
                noteEn = st.message
            )
        }.ifEmpty { listOf(Row("—", "No indicator recorded yet", NA)) }

        val trace = entries.takeLast(400).reversed().map { e ->
            // The timestamp is the label; the value carries what happened, so
            // the two columns never repeat each other.
            Row(
                labelFa = LogEntry.TS.format(e.timestamp),
                labelEn = LogEntry.TS.format(e.timestamp),
                value = buildString {
                    append(e.stage.name).append(" · ").append(e.component)
                    append(" · ").append(e.code)
                    if (e.key != null) append(" [").append(e.key).append(']')
                    append(" — ").append(e.message)
                    if (e.httpStatus != null) append("  HTTP ").append(e.httpStatus)
                    if (e.latencyMillis != null) append("  ").append(e.latencyMillis).append(" ms")
                },
                noteFa = e.detail ?: e.url,
                noteEn = e.detail ?: e.url,
                badges = listOf(
                    Badge(
                        e.level.name,
                        when (e.level) {
                            LogLevel.ERROR -> BadgeKind.ERROR
                            LogLevel.WARN -> BadgeKind.WARN
                            LogLevel.INFO -> BadgeKind.OK
                            else -> BadgeKind.INFO
                        }
                    )
                )
            )
        }

        return Screen(
            ScreenModel.SCREEN_LOGS, "گزارش‌گیر", "Logger",
            listOf(
                Section("خلاصه", "Summary", summary),
                Section("به تفکیک مرحله", "By Stage", byStage),
                Section("خطاها و هشدارها", "Failures", failures),
                Section("وضعیت هر شاخص", "Per-Indicator Status", perIndicator),
                Section("ردیابی کامل", "Full Trace", trace)
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

    /**
     * How often the indicator's own source publishes. Judging a monthly
     * statistic against a daily clock would label a perfectly current reading
     * as expired, which is a presentation error, not a data one.
     */
    private fun publicationCadence(key: String): Duration = when (key) {
        FeatureKeys.CENTRAL_BANK_NET_BUYING_3M,
        FeatureKeys.CENTRAL_BANK_PROXY_FLOW -> Duration.ofDays(45)
        FeatureKeys.COT_NET_POSITION_ZSCORE,
        FeatureKeys.COT_EXTREME_LONG,
        FeatureKeys.COT_EXTREME_SHORT -> Duration.ofDays(8)
        FeatureKeys.INFLATION_SURPRISE -> Duration.ofDays(35)
        // SPEC v2.1 §25 — the published macro series have their own calendars.
        // The Chicago and St. Louis Fed indices are weekly, net liquidity
        // follows the Wednesday balance sheet, and the daily FRED series post
        // one business day in arrears.
        FeatureKeys.FINANCIAL_CONDITIONS,
        FeatureKeys.FINANCIAL_STRESS_SCORE,
        FeatureKeys.FED_NET_LIQUIDITY,
        FeatureKeys.FED_NET_LIQUIDITY_CHANGE -> Duration.ofDays(10)
        FeatureKeys.CREDIT_SPREAD_HY,
        FeatureKeys.POLICY_UNCERTAINTY,
        FeatureKeys.NEWS_UNCERTAINTY,
        FeatureKeys.GEOPOLITICAL_RISK_SCORE,
        FeatureKeys.GEOPOLITICAL_RISK_DELTA,
        FeatureKeys.INFLATION_EXPECTATION_5Y5Y,
        FeatureKeys.REAL_YIELD,
        FeatureKeys.REAL_YIELD_ZSCORE,
        FeatureKeys.REAL_YIELD_TREND,
        FeatureKeys.BREAKEVEN_CHANGE -> Duration.ofDays(4)
        else -> Duration.ofDays(1)
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

    private fun badgeForDirection(d: Direction): BadgeKind = when (d) {
        Direction.BULLISH -> BadgeKind.OK
        Direction.BEARISH -> BadgeKind.WARN
        Direction.NEUTRAL -> BadgeKind.INFO
    }

    private fun fmt(v: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)

    private fun pct(v: Double): String = String.format(Locale.US, "%.1f%%", v * 100.0)

    companion object {
        const val NA = "N/A"
    }
}
