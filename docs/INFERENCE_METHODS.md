# Inference methods — survey and selection (SPEC v2.1 §26)

Companion to `docs/INDICATOR_RESEARCH.md`. That document surveys **data**;
this one surveys **method**: which published techniques were evaluated for
making the engine's output more decisive and cleaner, which were adopted,
which were rejected, and what each one measurably changed.

Every method below is published, named at its point of use in the source, and
attributed in `NOTICE`. Nothing here is novel and nothing is attributed to this
project. Where a method's assumptions are violated by the data, the violation
is stated and the affected guarantee is measured instead of claimed.

---

## 0. What was wrong before §26

| Site | Before | Why it was a defect |
|---|---|---|
| `MarketContext.calibrationQuality` | always `null` | §D2 called the weights *initial priors, to be re-estimated by walk-forward calibration once a record exists*; §19 withheld every probability until one did. The record was never built, so the clause could never discharge. |
| Score → probability | `1/(1+e^{−bias/25})` | Nobody fitted it. The divisor 25 was a choice, not an estimate. |
| `Calibration` term of Confidence | constant `0.0` | 15 % of the confidence formula was dead weight. |
| `ExpectedMove` band | `point ± 1.645·σ` | The normal quantile, **assumed**. Gold's daily return distribution is not normal. |
| `ModelAgreement` | majority share of weighted evidence | Twelve factors driven by the dollar counted as twelve witnesses. |
| `RegimeStability` | rule-based label | A threshold crossing, not an estimate of how old the regime is. |
| Momentum | asserted at full size | No test of whether the tape trends at all. |
| `brierDriftRatio` (kill-switch input) | always `null` | One of five kill-switch conditions could never fire. |

---

## 1. Calibration of a probability

### 1.1 Adopted — isotonic regression (pool-adjacent-violators)

The monotone step function minimising squared error between score and outcome.
Monotonicity is the correct constraint and not a convenience: a higher
composite must never map to a lower probability, whatever the sample noise
says. Outside the fitted range the value is **clamped, not extrapolated**.

Floor: 120 samples, below which the fit is refused.

### 1.2 Considered and rejected — Platt scaling

A two-parameter logistic fitted by maximum likelihood. Rejected because it
re-imposes a sigmoid shape that there is no reason to believe, which is the
assumption §26 set out to remove. Isotonic makes no shape assumption and the
sample here (thousands of sessions) is far past the size where isotonic's
higher variance matters.

### 1.3 Adopted — Brier score with the Murphy decomposition

`BS = REL − RES + UNC` (Brier 1950; Murphy 1973):

```
REL = Σ nₖ(p̄ₖ − ōₖ)² / n      miscalibration — lower is better, 0 is perfect
RES = Σ nₖ(ōₖ − ō)²  / n      ability to say something other than "average"
UNC = ō(1 − ō)                belongs to the problem, not the model
skill = 1 − BS/UNC             positive ⇒ better than always forecasting ō
```

The decomposition separates the error a recalibration can remove (`REL`) from
the information the forecast actually carries (`RES`). A model can have a
respectable Brier score purely because the problem is easy; `RES` is what says
otherwise.

**Binning.** The identity is exact only when stratifying on every distinct
issued probability; binning introduces two within-bin terms (Ferro & Fricker
2008). Quantile bins are used, not equal-width: equal-width bins collapse when
the probability mass sits in one tail, which is exactly what happens to a
directional model. Ten bins, floor of 60 samples.

**Use.** `calibrationQuality = min(1, n/750) × min(1, skill/0.05)`, zero when
skill is not positive. The ratio `BS / UNC` feeds the existing kill-switch
input `brierDriftRatio`, which had never had a value.

---

## 2. Uncertainty of a magnitude

### 2.1 Adopted — split conformal prediction

Vovk, Gammerman & Shafer (2005); Lei et al. (2018). Given absolute residuals
of a model on data it did not fit, the value at rank

```
⌈(n + 1)(1 − α)⌉
```

in the sorted residuals is a half-width whose coverage is at least `1 − α` in
finite samples, with **no distributional assumption**. The `+1` is the
finite-sample correction and makes the band slightly conservative rather than
slightly short.

Residuals are divided by the session's trailing σ, so the half-width is
dimensionless and drops straight into the slot the assumed `1.645` occupied.

**Stated violation.** The guarantee requires exchangeability, which a daily
price series does not satisfy — volatility clusters and regimes persist. The
guarantee is therefore **not claimed**. The realised coverage is computed on
the held-out sessions and published beside the nominal level, so a reader can
see whether it holds; persistent under-coverage is itself a distribution-shift
signal (Barber et al., *Conformal prediction beyond exchangeability*, Annals of
Statistics 51(2)).

### 2.2 Considered and deferred — adaptive conformal (ACI, PID, AcMCP)

Online variants that adjust `α` from the recent coverage error, designed for
exactly the non-exchangeable case. Deferred: they need a *live* scoring stream
to adapt against, and the app's record is a historical replay recomputed once
per session. The measured-coverage report makes the need for them visible if
it ever arises.

### 2.3 Considered and rejected — GARCH or EWMA volatility models for the band

Rejected as out of scope: the band's width is already σ-scaled and the defect
being fixed was the *multiplier*, not the volatility estimate. Changing the σ
estimator would alter the existing `sigmaByHorizon` contract without evidence
that it is the binding constraint.

---

## 3. Independence of the evidence

### 3.1 Adopted — effective number of bets

Meucci (2009), *Managing Diversification*. Normalise the eigenvalues of the
factor correlation matrix to a distribution and take its exponential entropy:

```
pᵢ = λᵢ / Σλ          ENB = exp(−Σ pᵢ ln pᵢ) ∈ [1, n]
```

1 means every input is one latent factor wearing different hats; `n` means
each is genuinely its own bet. The agreement term is shrunk toward neutral in
proportion:

```
Agreement′ = 0.5 + (Agreement − 0.5) · ENB/n
```

Eigenvalues come from a cyclic Jacobi rotation (~60 lines, no new dependency);
the matrix is small and symmetric, which is the case Jacobi is exact and
stable for.

### 3.2 Considered and rejected — minimum-torsion bets

Meucci, Santangelo & Deguest (2013) refine PCA bets into a minimum-torsion
basis because principal components are unstable and uninterpretable. Rejected
for now: the refinement matters when one wants to *name* the bets, and here
only their **count** is used, which the eigenvalue spectrum already gives. The
eigenvalue-only form is the published PDI / effective-dimensionality variant.

---

## 4. Age of the regime

### 4.1 Adopted — Bayesian online changepoint detection

Adams & MacKay (2007), arXiv:0710.3742. A forward recursion over the run
length — the number of observations since the last changepoint:

```
P(rₜ | x₁:ₜ) ∝ Σ_{r_{t−1}} P(xₜ | r_{t−1}) · P(rₜ | r_{t−1}) · P(r_{t−1} | x₁:_{t−1})
```

with a Normal-Inverse-Gamma conjugate prior, giving a Student-t posterior
predictive with `df = 2α` and `scale = β(κ+1)/(ακ)`, a constant hazard
`h = 1/λ` (λ = 250 sessions), and pruning to the 200 most probable run lengths
so the cost stays linear.

Outputs: the MAP run length, `P(rₜ = 0)`, and the mass on a regime younger than
15 sessions. These replace the rule-based label in `RegimeStability` and add a
posterior-driven trigger to the `Transition` penalty at `P(change) ≥ 0.20`.

It dates the regime; it does not name it. Naming would require labelled
episodes, and no free dataset of those exists — the same finding that shaped
§24.

### 4.2 Considered and rejected — hidden Markov regime switching

A two- or three-state Gaussian HMM fitted by EM. Rejected: the state count is
a free parameter with no principled value here, the fit is not online, and the
states need interpretation the data cannot supply. BOCPD answers the question
actually being asked — *how old is the current regime and how likely is it to
have just ended* — without inventing states.

---

## 5. Validity of a trend

### 5.1 Adopted — Lo–MacKinlay variance ratio

Lo & MacKinlay (1988). The variance of `q`-period returns over `q` times the
variance of one-period returns. One is a random walk, above one is persistence,
below one is reversal. The heteroskedasticity-robust statistic is used, since
the homoskedastic form rejects the random walk for the wrong reason on any
financial series.

`abs(z) < 1.96` ⇒ the random walk cannot be rejected at 5 % ⇒ the expected-move
**point estimate** is halved. Mean-reverting ⇒ quartered. The **band is not
narrowed**: when the tape is a coin flip the claim shrinks, the uncertainty
does not.

### 5.2 Adopted — rescaled-range Hurst exponent

Published beside the variance ratio as a second, independent read on
persistence. 0.5 is a random walk. Reported, not acted on: R/S is biased in
small samples and the variance-ratio test already carries a significance level.

---

## 6. Cleanliness

### 6.1 Adopted — one-dimensional local-level Kalman filter

Kalman (1960); the random-walk-plus-noise state-space model. Unlike a moving
average it does not lag by half its window — the gain adapts to how much of the
observed variation is signal. Published beside the raw composite with its
standard error, so a sign flip inside that band is readable as noise rather
than as news.

### 6.2 Adopted — symmetric trimmed mean as a fragility probe

The composite against the trimmed mean of its own legs, top and bottom 20 %
removed. A gap above 20 points on the ±100 scale means the reading rests on
one or two legs rather than on the whole panel, and is flagged `FRAGILE`.

Not a robust *estimator* replacing the weighted mean — the weight table is
normative and unchanged. It is a published diagnostic of how much of the call
survives removing its loudest contributors.

---

## 7. What the methods measured on the live stack

Replay basis: 5,505 daily sessions; seven price-derived stand-ins reproducing
**0.440** of the normative factor weight (F01 `TIP`, F02 `UUP`, F08 `VIX`,
F13 `GLD`, F16 `SPY`, F18 `HYG`, F21 `USO`). Anchored walk-forward: 1,250-session
warm-up, refit every 125 sessions on everything known at that origin, scored on
the 125 sessions after it. 29 refits, **3,625 sessions scored forward**.

| | 1D | 1W |
|---|---|---|
| Brier | 0.2503 | 0.2507 |
| reliability | 0.0013 | 0.0036 |
| resolution | 0.0005 | 0.0010 |
| uncertainty | 0.2494 | 0.2484 |
| Brier skill | −0.0033 | −0.0093 |
| base rate | 52.4 % | 54.0 % |
| conformal half-width | ±1.773σ | ±1.889σ |
| nominal / realised coverage | 90 % / **90.0 %** | 90 % / **90.0 %** |

Structural reads: ENB **5.56 of 7** (79.4 %) · regime age **199 sessions**,
`P(change)` 0.4 %, `HIGH` · `VR(5)` **0.908**, `z` −0.62, `H` 0.600 ⇒
`RANDOM_WALK`, credibility 50 % · filtered panel composite −66.65 ± 14.74 ·
fragility gap 48.7 ⇒ `FRAGILE`.

### The result that matters

**The price-derived panel has no positive out-of-sample directional skill on
gold at one day or one week.** Brier sits on top of the base-rate variance; the
resolution is two orders of magnitude below the uncertainty.

That is the honest finding, and it is published rather than papered over. The
probability stays withheld — but the status is now `UNCALIBRATED_NO_SKILL`,
which is a different statement from `UNCALIBRATED_NO_SAMPLE`. Before §26 the
engine did not know. It now knows, and says so.

What did improve and is now published as measured rather than assumed:

- the expected-move band, from an assumed normal quantile to an empirical one
  with **verified 90.0 % coverage** on 3,625 held-out sessions;
- the agreement term, corrected for the redundancy of the evidence;
- regime stability, from a label to a posterior with a dated run length;
- the point estimate, scaled down on a tape that tests as a random walk;
- the kill-switch's Brier-drift input, which now has a value;
- the fragility of the composite and its de-jittered level, both on screen.

---

## 8. Rejected for this phase

| Candidate | Reason |
|---|---|
| Platt scaling | re-imposes the sigmoid §26 set out to remove |
| Adaptive conformal (ACI / PID / AcMCP) | needs a live scoring stream; the record is a per-session replay |
| GARCH / EWMA for the band's σ | out of scope; the defect was the multiplier, not the σ estimator |
| Minimum-torsion bets | only the count of bets is used, which the spectrum already gives |
| Hidden Markov regime switching | free state count, offline fit, states need interpretation the data cannot supply |
| Gradient boosting / neural ensembles on the factor panel | no out-of-sample skill is available to exploit at these horizons, and an unexplainable model would violate the project's provenance rule |
| Re-estimating the factor weights from the replay | the weight table is **normative**; changing it is a separate decision |


---

# Part II — SPEC v2.1 §27

## 9. Replacing the stand-ins

§26's panel was seven ETFs chosen to behave like the factors they replaced. §27 reads the published series instead. The switch is not cosmetic: it raises the panel from 0.440 to **0.740** of the normative weight, adds the two largest remaining factors (F03 at .100 and F04 at .040) and removes the tracking error of every fund that was standing in.

Two corrections were required and are worth recording, because both were silent errors in §26 that only the measured series exposed.

### 9.1 The transform must match the series

`(a/b − 1)·100` is a return. Applied to a **yield** it is meaningless: a move from 0.10 % to 0.20 % is a 100 % "return" and a move from 4.10 % to 4.20 % is a 2.4 % one, though both are ten basis points. Rates, spreads and breakevens now enter as a **level difference** in percentage points; level indices such as policy uncertainty enter as a **z-score against their own trailing window**; only genuine price series keep the return.

### 9.2 Alignment must be by date, not by position

§26 right-aligned each series onto the gold history by position. Every Cboe series shares one exchange calendar, so this happened to be correct. FRED series do not: they carry federal holidays, publication gaps and a different week. Positional alignment silently shifted macro observations by a few sessions and grew worse the further back the replay went.

The join is now **as-of**: each gold session takes the last observation published on or before its own date, capped at seven days of staleness, never a later one. Taking a later observation is the definition of look-ahead; carrying an arbitrarily old one is the definition of a stale input.

## 10. A second forecaster

### 10.1 Adopted — ridge logistic on the panel

Hoerl & Kennard (1970) for the penalty; le Cessie & van Houwelingen (1992) for its use in logistic regression. Fitted by IRLS on the standardised design matrix with a Tikhonov penalty on the slopes and none on the intercept, `λ = 8`, convergence at `1e-7` or forty iterations.

The isotonic map of §26 reads **one number**. It cannot discover that two factors matter jointly, or that one of them has been carrying the whole composite. The ridge model reads the panel as a vector.

**It does not re-weight the published composite.** The §2 table is normative. The ridge is a separate forecaster whose probability is pooled with the isotonic one, and only when its own out-of-sample skill is positive. That boundary is tested.

### 10.2 Why ridge and not something larger

Gradient boosting or a small network would fit this panel in seconds and would be indefensible here: with thirteen columns, a near-zero signal and twenty years of regime change, the flexible model's out-of-sample skill is the only thing that matters and it is reliably worse. The ridge penalty is strong enough that on a pure-noise panel every slope shrinks below 0.35 and the intercept returns the base rate — a property that is asserted in the test suite rather than assumed.

## 11. Combining them

Bates & Granger (1969); Timmermann (2006). Members are pooled in **log-odds** — the logarithmic opinion pool — which keeps the result a proper probability and cannot produce a figure outside the members' range.

Two rules guard against the combination overfitting in its turn:

1. a member enters only on **positive out-of-sample Brier skill**;
2. weights are proportional to that skill and are not re-estimated per period.

With no admissible member the combination reports itself empty and the probability stays withheld. That is what happens on the live stack today, and the engine says so rather than publishing the better of two bad members.

## 12. A conditional interval

### 12.1 Adopted — Mondrian conformal

Vovk, Gammerman & Shafer (2005), ch. 4. The pooled band of §26 is **marginally** valid: it covers 90 % of sessions on average, which means it is too wide in a calm tape and too narrow in a violent one. A Mondrian taxonomy conditions the quantile on information available before the outcome; the taxonomy here is the trailing-volatility tercile, which is known at forecast time and therefore preserves the guarantee inside each bucket.

Measured on the live stack, at one day: **CALM ±2.05σ, NORMAL ±1.81σ, STRESSED ±1.55σ**, each at 90.1 % realised coverage on about 1,083 held-out sessions. The pooled band was ±1.78σ — 13 % too narrow in the calm bucket and 15 % too wide in the stressed one. At one week the spread is wider still: ±2.34σ against ±1.39σ.

This is the clearest decisiveness gain in §27: in a stressed tape the published interval is now **a seventh narrower** than the pooled one, and it is narrower because it was measured to be, not because the model was asked to be more confident.

A bucket with fewer than 120 residuals falls back to the pooled band rather than reporting a quantile it cannot support.

## 13. What each factor actually knows

### 13.1 Adopted — information coefficient with a significance test

The Spearman rank correlation between a leg's score and the forward return it was meant to anticipate — the information coefficient of Grinold & Kahn, *Active Portfolio Management*. Rank rather than Pearson because the scores are bounded and saturate.

Published with `t = IC·√(n−2)/√(1−IC²)`, because the two facts are different and both matter. Measured over 3,250 held-out sessions:

| Horizon | legs with a significant IC | strongest |
|---|---|---|
| 1D | **1 of 13** | F07 policy uncertainty, IC +0.053, t 3.01 |
| 1W | **7 of 13** | F07 policy uncertainty, IC +0.064, t 3.68; F03 two-year yield, t 3.53; F16 equities, t 3.04; F15 gold implied vol, t 2.70 |

An IC of 0.064 is comfortably distinguishable from zero at this sample size and corresponds to a Brier skill of roughly `IC²/4 ≈ 0.001` — an order of magnitude below the noise in the Brier estimate itself. **The information is real and it is too small.** Reporting only the Brier score would have rounded that to "no signal"; reporting only the IC would have overstated it. The engine reports both.

## 14. The provenance audit

`IndicatorCatalog.Provenance` classifies every published indicator as `MEASURED` (the published series itself), `DERIVED` (arithmetic on measured series, formula on the row) or `PROXY` (a declared stand-in, naming what it replaces and why). The counts lead the Indicators screen.

Live: **81 measured, 12 derived, 4 stand-in**, of 97. The four stand-ins are the reconstructed dollar index, the two ETF-flow readings and the intraday real-yield read; each is a case where no free source publishes the thing itself, and each says so where it is displayed.

## 15. Rejected in §27

| Candidate | Reason |
|---|---|
| Gradient boosting / small networks on the panel | reliably worse out of sample on a near-zero signal across twenty years of regime change |
| Re-estimating the factor weights from the replay | the weight table is **normative**; changing it is a separate decision |
| Adaptive conformal (ACI / PID) | still needs a live scoring stream; the Mondrian conditioning addresses the heteroskedasticity that motivated it here |
| `prices.lbma.org.uk`, Nasdaq Data Link LBMA/GOLD | 403 from this network; both are behind an access layer |
| SPDR holdings JSON | 404; no machine-readable holdings file exists, so F09 stays a stand-in |
| Shanghai Gold Exchange `graph/Dailyhq` | returns 200 with every field zero |
| FRED `SKEWCLS` | not published; the endpoint returns an error page |
| ICE BofA OAS as a deep replay leg | FRED redistributes about three years of these series, which is too short for the replay; they inform the live reading only |
