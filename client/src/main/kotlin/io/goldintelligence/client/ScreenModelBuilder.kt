package io.goldintelligence.client

import io.goldintelligence.engine.CrossMarketConfirmation
import io.goldintelligence.engine.Direction
import io.goldintelligence.engine.FactorCatalog
import io.goldintelligence.engine.HorizonMode
import io.goldintelligence.engine.IntelligenceReport
import io.goldintelligence.engine.Tier
import io.goldintelligence.engine.DiagnosticLog
import io.goldintelligence.engine.FeatureKeys
import io.goldintelligence.engine.LogEntry
import io.goldintelligence.engine.LogLevel
import io.goldintelligence.ingestion.FactorDiagnostic
import io.goldintelligence.ingestion.FeatureBundle
import io.goldintelligence.ingestion.IndicatorCatalog
import io.goldintelligence.ingestion.LicenseClass
import io.goldintelligence.ingestion.MarketUniverse
import io.goldintelligence.ingestion.SpecFeatureEngineer
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
        goldCurve: List<Pair<String, Double>> = emptyList()
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

    private fun eventsScreen(
        report: IntelligenceReport,
        u: MarketUniverse,
        calendar: List<CalendarEvent>,
        now: Instant
    ): Screen {
        val s = report.state
        val newsRows = listOf(
            Row("وضعیت اخبار", "News State", s.newsState),
            Row(
                "تقویم اقتصادی", "Economic Calendar",
                if (calendar.isEmpty()) NA else "${calendar.size} رویداد / events",
                badges = if (calendar.isEmpty()) listOf(Badge("UNAVAILABLE", BadgeKind.ERROR))
                else listOf(Badge("LIVE", BadgeKind.OK)),
                noteFa = "تقویم با پیش‌بینی اجماع",
                noteEn = "Scheduled releases with surveyed consensus forecasts."
            ),
            Row(
                "جریان اخبار", "News Feed", NA,
                badges = listOf(Badge("CIRCUIT_BREAKER", BadgeKind.WARN)),
                noteFa = "GDELT از محدوده IP سرور محدود شده است",
                noteEn = "GDELT is rate-limited from server IP ranges and stays behind a circuit breaker."
            )
        )

        // SPEC v2.1 — the calendar is a real feed now, so it gets its own section.
        val upcoming = calendar.filter { it.time.isAfter(now.minus(Duration.ofHours(6))) }
            .sortedBy { it.time }
            .take(24)
        val calendarRows = upcoming.map { e ->
            val hours = Duration.between(now, e.time).toMinutes() / 60.0
            Row(
                labelFa = e.title,
                labelEn = "${e.country}  ${e.title}",
                value = buildString {
                    append(if (hours < 0) "now" else String.format(Locale.US, "T−%.1fh", hours))
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
                noteEn = "ForexFactory consensus survey",
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
                "Full L2 depth from Kraken PAXG/USD and OKX XAUT/USDT — allocated gold, " +
                    "one token per fine troy ounce. " +
                    books.joinToString(" · ") { "${it.venue} ${it.bidLevels}+${it.askLevels} levels" },
                "عمق کامل دفتر سفارش از کراکن و OKX",
                "Both gold venues failed this cycle; the ETF top-of-book proxy is in use."
            ),
            resolution(
                "اجماع تحلیلگران", "Analyst consensus",
                features[FeatureKeys.CALENDAR_HIGH_IMPACT_24H] != null ||
                    features[FeatureKeys.CONSENSUS_SURPRISE] != null,
                "ForexFactory surveyed consensus for every scheduled release, paired with " +
                    "published actuals from the U.S. Bureau of Labor Statistics.",
                "اجماع نظرسنجی‌شده به‌همراه داده‌های رسمی اداره آمار کار آمریکا",
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
            Row(
                "تقاضای بانک مرکزی", "Central bank demand", "NO_FREE_SOURCE",
                badges = listOf(Badge("UNRESOLVED", BadgeKind.ERROR)),
                noteFa = "آمار فصلی خرید بانک‌های مرکزی به‌صورت رایگان و ماشین‌خوان منتشر نمی‌شود",
                noteEn = "Quarterly central-bank purchase statistics are published only as documents; " +
                    "IMF IFS blocks programmatic access from this network. F10 stays unavailable rather " +
                    "than being approximated."
            ),
            Row(
                "آنالوگ تاریخی", "Historical analogue", "NOT_AVAILABLE",
                badges = listOf(Badge("UNRESOLVED", BadgeKind.ERROR)),
                noteFa = "مجموعه‌داده تاریخی برچسب‌خورده وجود ندارد",
                noteEn = "No labelled episode dataset exists; the layer reports its absence."
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
                Section("وضعیت شکاف‌های داده", "Data Gap Status", gaps)
            )
        )
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
