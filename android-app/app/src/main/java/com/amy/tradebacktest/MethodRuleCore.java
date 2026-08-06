package com.amy.tradebacktest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure-Java rule core shared by the Android streaming engine and CI self-tests. */
public final class MethodRuleCore {
    private MethodRuleCore() {}

    public static final class Condition {
        public final String type;
        public final double value;
        public final double value2;
        public final int period;
        public final int period2;
        public final boolean negate;

        public Condition(String type, double value, double value2, int period, int period2, boolean negate) {
            this.type = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
            this.value = value;
            this.value2 = value2;
            this.period = period;
            this.period2 = period2;
            this.negate = negate;
        }

        public String fingerprint() {
            return type + ":" + value + ":" + value2 + ":" + period + ":" + period2 + ":" + negate;
        }
    }

    public static final class Config {
        public String name = "Metode tanpa nama";
        public String side = "both";
        public String buyLogic = "all";
        public String sellLogic = "all";
        public List<Condition> buy = new ArrayList<>();
        public List<Condition> sell = new ArrayList<>();
        public String slMode = "signal_extreme";
        public int lookback = 20;
        public int atrPeriod = 14;
        public int emaFast = 9;
        public int emaSlow = 21;
        public int swingLeft = 3;
        public int swingRight = 3;
        public int structureLookback = 50;
        public int fvgLookback = 20;
        public int obLookback = 30;
        public double slAtr = 1.0;
        public double stopBufferAtr = 0.10;
        public double rr = 2.0;
        public int maxHoldBars = 24;
        public int cooldownBars = 0;
        public double costR = 0.03;
        public double initialCapital = 10000.0;
        public double riskPct = 0.01;

        public FeatureRequirements requirements() {
            FeatureRequirements req = new FeatureRequirements();
            for (Condition condition : buy) req.include(condition.type);
            for (Condition condition : sell) req.include(condition.type);
            if ("rolling_level".equals(slMode)) req.rolling = true;
            if ("swing_level".equals(slMode)) req.swing = true;
            if ("order_block".equals(slMode)) req.orderBlock = true;
            if (slAtr > 0 || stopBufferAtr > 0) req.atr = true;
            return req;
        }
    }

    public static final class FeatureRequirements {
        public boolean atr;
        public boolean rolling;
        public boolean ema;
        public boolean swing;
        public boolean structure;
        public boolean fvg;
        public boolean orderBlock;
        public boolean session;
        public boolean history;

        void include(String type) {
            switch (type) {
                case "RANGE_ATR_MIN": case "RANGE_ATR_MAX": case "BODY_ATR_MIN":
                case "DIST_HIGH_ATR_MAX": case "DIST_LOW_ATR_MAX":
                case "SWEEP_HIGH": case "SWEEP_LOW": case "RBS_RETEST": case "SBR_RETEST":
                case "DISPLACEMENT_BULL": case "DISPLACEMENT_BEAR":
                case "EQUAL_HIGHS": case "EQUAL_LOWS": case "FVG_BULL_RETEST":
                case "FVG_BEAR_RETEST": case "OB_BULL_RETEST": case "OB_BEAR_RETEST":
                case "BREAKER_BULL_RETEST": case "BREAKER_BEAR_RETEST":
                    atr = true; break;
                default: break;
            }
            switch (type) {
                case "BREAK_HIGH": case "BREAK_LOW": case "SWEEP_HIGH": case "SWEEP_LOW":
                case "RBS_RETEST": case "SBR_RETEST": case "RANGE_POSITION_MIN":
                case "RANGE_POSITION_MAX": case "DIST_HIGH_ATR_MAX": case "DIST_LOW_ATR_MAX":
                case "PREMIUM_ZONE": case "DISCOUNT_ZONE": case "EQUILIBRIUM_ZONE":
                    rolling = true; break;
                default: break;
            }
            if (type.startsWith("EMA_")) ema = true;
            switch (type) {
                case "BOS_UP": case "BOS_DOWN": case "CHOCH_UP": case "CHOCH_DOWN":
                case "MSS_UP": case "MSS_DOWN": case "SWING_HH_HL": case "SWING_LH_LL":
                case "OTE_BUY_ZONE": case "OTE_SELL_ZONE": case "LIQUIDITY_SWEEP_MSS_BUY":
                case "LIQUIDITY_SWEEP_MSS_SELL": case "PREMIUM_ZONE": case "DISCOUNT_ZONE":
                case "EQUILIBRIUM_ZONE":
                    swing = true; structure = true; history = true; break;
                default: break;
            }
            if (type.startsWith("FVG_")) { fvg = true; history = true; }
            if (type.startsWith("OB_") || type.startsWith("BREAKER_")) {
                orderBlock = true; structure = true; history = true;
            }
            if (type.startsWith("UTC_") || type.startsWith("KILLZONE_")) session = true;
            switch (type) {
                case "CONSECUTIVE_BULL": case "CONSECUTIVE_BEAR":
                case "CLOSE_ABOVE_PREV_HIGH": case "CLOSE_BELOW_PREV_LOW":
                case "HH_HL": case "LH_LL": case "FIB_RETRACE_BUY": case "FIB_RETRACE_SELL":
                    history = true; break;
                default: break;
            }
        }

        public List<String> activeNames() {
            List<String> names = new ArrayList<>();
            if (atr) names.add("ATR");
            if (rolling) names.add("Rolling high/low");
            if (ema) names.add("EMA");
            if (swing) names.add("Swing pivot");
            if (structure) names.add("Market structure");
            if (fvg) names.add("FVG");
            if (orderBlock) names.add("Order block/breaker");
            if (session) names.add("UTC session");
            if (history) names.add("Candle history");
            return names;
        }
    }

    public static final class Row {
        public long index;
        public long epochMillis = Long.MIN_VALUE;
        public String timestamp = "";
        public double open;
        public double high;
        public double low;
        public double close;
        public double prevOpen = Double.NaN;
        public double prevHigh = Double.NaN;
        public double prevLow = Double.NaN;
        public double prevClose = Double.NaN;
        public double atr = Double.NaN;
        public double rollingHigh = Double.NaN;
        public double rollingLow = Double.NaN;
        public double emaFast = Double.NaN;
        public double emaSlow = Double.NaN;
        public double prevEmaFast = Double.NaN;
        public double prevEmaSlow = Double.NaN;
        public int consecutiveBull;
        public int consecutiveBear;
        public double lastSwingHigh = Double.NaN;
        public double previousSwingHigh = Double.NaN;
        public double lastSwingLow = Double.NaN;
        public double previousSwingLow = Double.NaN;
        public int structureBias; // +1 bullish, -1 bearish, 0 unknown
        public boolean bosUp;
        public boolean bosDown;
        public boolean chochUp;
        public boolean chochDown;
        public boolean mssUp;
        public boolean mssDown;
        public boolean recentSweepLow;
        public boolean recentSweepHigh;
        public double bullishFvgLow = Double.NaN;
        public double bullishFvgHigh = Double.NaN;
        public double bearishFvgLow = Double.NaN;
        public double bearishFvgHigh = Double.NaN;
        public double bullishObLow = Double.NaN;
        public double bullishObHigh = Double.NaN;
        public double bearishObLow = Double.NaN;
        public double bearishObHigh = Double.NaN;
        public double bullishBreakerLow = Double.NaN;
        public double bullishBreakerHigh = Double.NaN;
        public double bearishBreakerLow = Double.NaN;
        public double bearishBreakerHigh = Double.NaN;
        public double impulseLow = Double.NaN;
        public double impulseHigh = Double.NaN;
    }

    public static final class SignalDecision {
        public final int bias;
        public final List<String> matchedBuy;
        public final List<String> matchedSell;
        public final boolean conflict;

        SignalDecision(int bias, List<String> matchedBuy, List<String> matchedSell, boolean conflict) {
            this.bias = bias;
            this.matchedBuy = matchedBuy;
            this.matchedSell = matchedSell;
            this.conflict = conflict;
        }
    }

    public static final class ConditionStats {
        public long evaluated;
        public long passed;
    }

    public static final class Audit {
        public final FeatureRequirements requirements;
        public final List<String> warnings = new ArrayList<>();
        public final Map<String, ConditionStats> conditionStats = new LinkedHashMap<>();
        public long buySignals;
        public long sellSignals;
        public long conflicts;
        public long warmupBlocked;
        public long evaluatedBars;

        public Audit(Config config) {
            this.requirements = config.requirements();
            auditSide("BUY", config.buy, config.buyLogic, warnings);
            auditSide("SELL", config.sell, config.sellLogic, warnings);
            if (config.buy.isEmpty() && !"sell_only".equals(config.side)) warnings.add("BUY tidak memiliki kondisi.");
            if (config.sell.isEmpty() && !"buy_only".equals(config.side)) warnings.add("SELL tidak memiliki kondisi.");
            if (!requirements.ema && (config.emaFast != 9 || config.emaSlow != 21)) {
                warnings.add("EMA tidak aktif; periode EMA diabaikan sepenuhnya.");
            }
            if (requirements.ema && config.emaFast >= config.emaSlow) {
                warnings.add("EMA cepat sebaiknya lebih kecil daripada EMA lambat.");
            }
            if ("any".equals(config.buyLogic) && config.buy.size() > 3) {
                warnings.add("BUY memakai ANY dengan banyak blok; sinyal bisa terlalu longgar.");
            }
            if ("any".equals(config.sellLogic) && config.sell.size() > 3) {
                warnings.add("SELL memakai ANY dengan banyak blok; sinyal bisa terlalu longgar.");
            }
        }

        private static void auditSide(String side, List<Condition> conditions, String logic, List<String> warnings) {
            Set<String> exact = new HashSet<>();
            Set<String> types = new HashSet<>();
            for (Condition c : conditions) {
                if (!exact.add(c.fingerprint())) warnings.add(side + " memiliki kondisi duplikat: " + c.type);
                types.add(c.type);
            }
            contradiction(side, types, "CANDLE_BULL", "CANDLE_BEAR", warnings);
            contradiction(side, types, "EMA_FAST_ABOVE", "EMA_FAST_BELOW", warnings);
            contradiction(side, types, "BREAK_HIGH", "BREAK_LOW", warnings);
            contradiction(side, types, "BOS_UP", "BOS_DOWN", warnings);
            contradiction(side, types, "CHOCH_UP", "CHOCH_DOWN", warnings);
            contradiction(side, types, "MSS_UP", "MSS_DOWN", warnings);
            contradiction(side, types, "PREMIUM_ZONE", "DISCOUNT_ZONE", warnings);
            if ("all".equals(logic)) {
                contradiction(side, types, "RANGE_POSITION_MIN", "RANGE_POSITION_MAX", warnings);
            }
        }

        private static void contradiction(String side, Set<String> types, String a, String b, List<String> warnings) {
            if (types.contains(a) && types.contains(b)) warnings.add(side + " memuat kondisi berlawanan: " + a + " dan " + b + ".");
        }

        void recordCondition(String side, Condition condition, boolean passed) {
            String key = side + ":" + condition.type + (condition.negate ? " (NOT)" : "");
            ConditionStats stats = conditionStats.computeIfAbsent(key, ignored -> new ConditionStats());
            stats.evaluated++;
            if (passed) stats.passed++;
        }
    }

    public static SignalDecision decide(Config config, Row row, List<Row> history, Audit audit) {
        FeatureRequirements req = audit.requirements;
        if (!ready(req, row)) {
            audit.warmupBlocked++;
            return new SignalDecision(0, new ArrayList<>(), new ArrayList<>(), false);
        }
        audit.evaluatedBars++;
        boolean allowBuy = !"sell_only".equals(config.side);
        boolean allowSell = !"buy_only".equals(config.side);
        EvalResult buy = allowBuy ? evaluateSide("BUY", config.buy, config.buyLogic, row, history, audit) : EvalResult.falseResult();
        EvalResult sell = allowSell ? evaluateSide("SELL", config.sell, config.sellLogic, row, history, audit) : EvalResult.falseResult();
        if (buy.value && sell.value) {
            audit.conflicts++;
            return new SignalDecision(0, buy.matched, sell.matched, true);
        }
        if (buy.value) {
            audit.buySignals++;
            return new SignalDecision(1, buy.matched, sell.matched, false);
        }
        if (sell.value) {
            audit.sellSignals++;
            return new SignalDecision(-1, buy.matched, sell.matched, false);
        }
        return new SignalDecision(0, buy.matched, sell.matched, false);
    }

    private static boolean ready(FeatureRequirements req, Row row) {
        if (req.atr && (!Double.isFinite(row.atr) || row.atr <= 0)) return false;
        if (req.rolling && (!Double.isFinite(row.rollingHigh) || !Double.isFinite(row.rollingLow))) return false;
        if (req.ema && (!Double.isFinite(row.emaFast) || !Double.isFinite(row.emaSlow))) return false;
        if (req.swing && (!Double.isFinite(row.lastSwingHigh) || !Double.isFinite(row.lastSwingLow))) return false;
        return true;
    }

    private static final class EvalResult {
        final boolean value;
        final List<String> matched;
        EvalResult(boolean value, List<String> matched) { this.value = value; this.matched = matched; }
        static EvalResult falseResult() { return new EvalResult(false, new ArrayList<>()); }
    }

    private static EvalResult evaluateSide(String side, List<Condition> conditions, String logic, Row row, List<Row> history, Audit audit) {
        if (conditions.isEmpty()) return EvalResult.falseResult();
        boolean any = "any".equals(logic);
        boolean result = !any;
        List<String> matched = new ArrayList<>();
        for (Condition condition : conditions) {
            boolean raw = evaluate(condition, row, history);
            boolean passed = condition.negate ? !raw : raw;
            audit.recordCondition(side, condition, passed);
            if (passed) matched.add(condition.type + (condition.negate ? " NOT" : ""));
            if (any) result |= passed; else result &= passed;
        }
        return new EvalResult(result, matched);
    }

    public static boolean evaluate(Condition c, Row r, List<Row> history) {
        double range = r.high - r.low;
        double body = range > 0 ? Math.abs(r.close - r.open) / range : 0;
        double upperWick = range > 0 ? (r.high - Math.max(r.open, r.close)) / range : 0;
        double lowerWick = range > 0 ? (Math.min(r.open, r.close) - r.low) / range : 0;
        double closeLoc = range > 0 ? (r.close - r.low) / range : 0.5;
        double rollingPos = Double.isFinite(r.rollingHigh) && Double.isFinite(r.rollingLow) && r.rollingHigh > r.rollingLow
                ? (r.close - r.rollingLow) / (r.rollingHigh - r.rollingLow) : Double.NaN;
        int hour = utcHour(r.epochMillis, r.timestamp);
        double tol = c.value > 0 ? c.value : 0.15;

        switch (c.type) {
            case "CANDLE_BULL": return r.close > r.open;
            case "CANDLE_BEAR": return r.close < r.open;
            case "BODY_RATIO_MIN": return body >= c.value;
            case "BODY_RATIO_MAX": return body <= c.value;
            case "UPPER_WICK_MIN": return upperWick >= c.value;
            case "LOWER_WICK_MIN": return lowerWick >= c.value;
            case "CLOSE_LOCATION_MIN": return closeLoc >= c.value;
            case "CLOSE_LOCATION_MAX": return closeLoc <= c.value;
            case "RANGE_ATR_MIN": return finiteRatio(range, r.atr) >= c.value;
            case "RANGE_ATR_MAX": return finiteRatio(range, r.atr) <= c.value;
            case "BODY_ATR_MIN": return finiteRatio(Math.abs(r.close - r.open), r.atr) >= c.value;
            case "EMA_FAST_ABOVE": return r.emaFast > r.emaSlow;
            case "EMA_FAST_BELOW": return r.emaFast < r.emaSlow;
            case "EMA_FAST_RISING": return r.emaFast > r.prevEmaFast;
            case "EMA_FAST_FALLING": return r.emaFast < r.prevEmaFast;
            case "EMA_SLOW_RISING": return r.emaSlow > r.prevEmaSlow;
            case "EMA_SLOW_FALLING": return r.emaSlow < r.prevEmaSlow;
            case "BREAK_HIGH": return Double.isFinite(r.rollingHigh) && r.close > r.rollingHigh;
            case "BREAK_LOW": return Double.isFinite(r.rollingLow) && r.close < r.rollingLow;
            case "SWEEP_HIGH": return Double.isFinite(r.rollingHigh) && r.high > r.rollingHigh + tol * r.atr && r.close < r.rollingHigh;
            case "SWEEP_LOW": return Double.isFinite(r.rollingLow) && r.low < r.rollingLow - tol * r.atr && r.close > r.rollingLow;
            case "RBS_RETEST": return rbsRetest(r, history, c);
            case "SBR_RETEST": return sbrRetest(r, history, c);
            case "RANGE_POSITION_MIN": return Double.isFinite(rollingPos) && rollingPos >= c.value;
            case "RANGE_POSITION_MAX": return Double.isFinite(rollingPos) && rollingPos <= c.value;
            case "DIST_HIGH_ATR_MAX": return Double.isFinite(r.rollingHigh) && finiteRatio(Math.abs(r.rollingHigh - r.close), r.atr) <= c.value;
            case "DIST_LOW_ATR_MAX": return Double.isFinite(r.rollingLow) && finiteRatio(Math.abs(r.close - r.rollingLow), r.atr) <= c.value;
            case "CONSECUTIVE_BULL": return r.consecutiveBull >= Math.max(1, c.period);
            case "CONSECUTIVE_BEAR": return r.consecutiveBear >= Math.max(1, c.period);
            case "CLOSE_ABOVE_PREV_HIGH": return Double.isFinite(r.prevHigh) && r.close > r.prevHigh;
            case "CLOSE_BELOW_PREV_LOW": return Double.isFinite(r.prevLow) && r.close < r.prevLow;
            case "HH_HL": return Double.isFinite(r.prevHigh) && r.high > r.prevHigh && r.low > r.prevLow;
            case "LH_LL": return Double.isFinite(r.prevHigh) && r.high < r.prevHigh && r.low < r.prevLow;
            case "SWING_HH_HL": return r.lastSwingHigh > r.previousSwingHigh && r.lastSwingLow > r.previousSwingLow;
            case "SWING_LH_LL": return r.lastSwingHigh < r.previousSwingHigh && r.lastSwingLow < r.previousSwingLow;
            case "BOS_UP": return r.bosUp;
            case "BOS_DOWN": return r.bosDown;
            case "CHOCH_UP": return r.chochUp;
            case "CHOCH_DOWN": return r.chochDown;
            case "MSS_UP": return r.mssUp;
            case "MSS_DOWN": return r.mssDown;
            case "LIQUIDITY_SWEEP_MSS_BUY": return r.recentSweepLow && (r.mssUp || r.chochUp);
            case "LIQUIDITY_SWEEP_MSS_SELL": return r.recentSweepHigh && (r.mssDown || r.chochDown);
            case "DISPLACEMENT_BULL": return r.close > r.open && finiteRatio(Math.abs(r.close-r.open), r.atr) >= Math.max(0.5,c.value) && closeLoc >= Math.max(0.65,c.value2);
            case "DISPLACEMENT_BEAR": return r.close < r.open && finiteRatio(Math.abs(r.close-r.open), r.atr) >= Math.max(0.5,c.value) && closeLoc <= 1.0-Math.max(0.65,c.value2);
            case "FVG_BULL_ACTIVE": return Double.isFinite(r.bullishFvgLow) && Double.isFinite(r.bullishFvgHigh);
            case "FVG_BEAR_ACTIVE": return Double.isFinite(r.bearishFvgLow) && Double.isFinite(r.bearishFvgHigh);
            case "FVG_BULL_RETEST": return overlaps(r.low, r.high, r.bullishFvgLow, r.bullishFvgHigh) && r.close >= r.bullishFvgLow;
            case "FVG_BEAR_RETEST": return overlaps(r.low, r.high, r.bearishFvgLow, r.bearishFvgHigh) && r.close <= r.bearishFvgHigh;
            case "OB_BULL_RETEST": return overlaps(r.low, r.high, r.bullishObLow, r.bullishObHigh) && r.close > r.open;
            case "OB_BEAR_RETEST": return overlaps(r.low, r.high, r.bearishObLow, r.bearishObHigh) && r.close < r.open;
            case "BREAKER_BULL_RETEST": return overlaps(r.low, r.high, r.bullishBreakerLow, r.bullishBreakerHigh) && r.close > r.bullishBreakerHigh;
            case "BREAKER_BEAR_RETEST": return overlaps(r.low, r.high, r.bearishBreakerLow, r.bearishBreakerHigh) && r.close < r.bearishBreakerLow;
            case "EQUAL_HIGHS": return equalLevel(r.high, r.lastSwingHigh, r.atr, tol);
            case "EQUAL_LOWS": return equalLevel(r.low, r.lastSwingLow, r.atr, tol);
            case "PREMIUM_ZONE": return Double.isFinite(rollingPos) && rollingPos >= (c.value > 0 ? c.value : 0.5);
            case "DISCOUNT_ZONE": return Double.isFinite(rollingPos) && rollingPos <= (c.value > 0 ? c.value : 0.5);
            case "EQUILIBRIUM_ZONE": return Double.isFinite(rollingPos) && Math.abs(rollingPos - 0.5) <= (c.value > 0 ? c.value : 0.1);
            case "OTE_BUY_ZONE": return fibZone(r.close, r.impulseLow, r.impulseHigh, c.value > 0 ? c.value : 0.62, c.value2 > 0 ? c.value2 : 0.79, true);
            case "OTE_SELL_ZONE": return fibZone(r.close, r.impulseLow, r.impulseHigh, c.value > 0 ? c.value : 0.62, c.value2 > 0 ? c.value2 : 0.79, false);
            case "FIB_RETRACE_BUY": return fibZone(r.close, r.impulseLow, r.impulseHigh, c.value > 0 ? c.value : 0.5, c.value2 > 0 ? c.value2 : 0.618, true);
            case "FIB_RETRACE_SELL": return fibZone(r.close, r.impulseLow, r.impulseHigh, c.value > 0 ? c.value : 0.5, c.value2 > 0 ? c.value2 : 0.618, false);
            case "UTC_HOUR_RANGE": return hourInRange(hour, c.period, c.period2);
            case "KILLZONE_ASIA": return hourInRange(hour, 0, 5);
            case "KILLZONE_LONDON": return hourInRange(hour, 7, 10);
            case "KILLZONE_NEW_YORK": return hourInRange(hour, 12, 16);
            default: return false;
        }
    }

    private static boolean rbsRetest(Row r, List<Row> history, Condition c) {
        int bars = Math.max(2, c.period > 0 ? c.period : 12);
        int start = Math.max(0, history.size() - bars);
        double tolerance = Math.max(0.02, c.value) * r.atr;
        for (int i = start; i < history.size(); i++) {
            Row old = history.get(i);
            if (Double.isFinite(old.rollingHigh) && old.close > old.rollingHigh) {
                double level = old.rollingHigh;
                if (r.low <= level + tolerance && r.high >= level - tolerance && r.close > level) return true;
            }
        }
        return false;
    }

    private static boolean sbrRetest(Row r, List<Row> history, Condition c) {
        int bars = Math.max(2, c.period > 0 ? c.period : 12);
        int start = Math.max(0, history.size() - bars);
        double tolerance = Math.max(0.02, c.value) * r.atr;
        for (int i = start; i < history.size(); i++) {
            Row old = history.get(i);
            if (Double.isFinite(old.rollingLow) && old.close < old.rollingLow) {
                double level = old.rollingLow;
                if (r.high >= level - tolerance && r.low <= level + tolerance && r.close < level) return true;
            }
        }
        return false;
    }

    private static boolean fibZone(double price, double low, double high, double minRetrace, double maxRetrace, boolean buy) {
        if (!Double.isFinite(low) || !Double.isFinite(high) || high <= low) return false;
        double a = Math.min(minRetrace, maxRetrace), b = Math.max(minRetrace, maxRetrace);
        if (buy) {
            double upper = high - a * (high-low), lower = high - b * (high-low);
            return price >= lower && price <= upper;
        }
        double lower = low + a * (high-low), upper = low + b * (high-low);
        return price >= lower && price <= upper;
    }

    private static boolean overlaps(double low1, double high1, double low2, double high2) {
        return Double.isFinite(low2) && Double.isFinite(high2) && high1 >= low2 && low1 <= high2;
    }

    private static boolean equalLevel(double a, double b, double atr, double toleranceAtr) {
        return Double.isFinite(a) && Double.isFinite(b) && Double.isFinite(atr) && atr > 0 && Math.abs(a-b) <= toleranceAtr*atr;
    }

    private static double finiteRatio(double numerator, double denominator) {
        return Double.isFinite(denominator) && denominator > 0 ? numerator / denominator : Double.NaN;
    }

    private static int utcHour(long epochMillis, String timestamp) {
        if (epochMillis != Long.MIN_VALUE) return (int)Math.floorMod(epochMillis / 3_600_000L, 24L);
        if (timestamp != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:T|\\s)(\\d{1,2}):(\\d{2})").matcher(timestamp);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    private static boolean hourInRange(int hour, int start, int end) {
        if (hour < 0) return false;
        start = Math.floorMod(start, 24); end = Math.floorMod(end, 24);
        if (start <= end) return hour >= start && hour <= end;
        return hour >= start || hour <= end;
    }
}
