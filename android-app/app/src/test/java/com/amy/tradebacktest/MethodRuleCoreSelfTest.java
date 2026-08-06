package com.amy.tradebacktest;

import java.util.ArrayList;

public final class MethodRuleCoreSelfTest {
    public static void main(String[] args) {
        noEmaMethodMustIgnoreEmaValues();
        emaMethodMustDeclareDependency();
        contradictionAuditMustWarn();
        ictConditionsMustEvaluate();
        System.out.println("MethodRuleCoreSelfTest: PASS");
    }

    private static void noEmaMethodMustIgnoreEmaValues() {
        MethodRuleCore.Config config = new MethodRuleCore.Config();
        config.buy.add(new MethodRuleCore.Condition("CANDLE_BULL", 0,0,0,0,false));
        config.buy.add(new MethodRuleCore.Condition("CLOSE_ABOVE_PREV_HIGH",0,0,0,0,false));
        config.sell.clear();
        config.side = "buy_only";

        MethodRuleCore.Row a = row();
        a.emaFast = Double.NaN;
        a.emaSlow = Double.NaN;
        MethodRuleCore.Row b = row();
        b.emaFast = 10_000;
        b.emaSlow = -10_000;

        MethodRuleCore.Audit auditA = new MethodRuleCore.Audit(config);
        MethodRuleCore.Audit auditB = new MethodRuleCore.Audit(config);
        int signalA = MethodRuleCore.decide(config, a, new ArrayList<>(), auditA).bias;
        int signalB = MethodRuleCore.decide(config, b, new ArrayList<>(), auditB).bias;
        require(signalA == 1 && signalB == 1, "No-EMA method changed when EMA values changed");
        require(!auditA.requirements.ema, "No-EMA method incorrectly declares EMA dependency");
    }

    private static void emaMethodMustDeclareDependency() {
        MethodRuleCore.Config config = new MethodRuleCore.Config();
        config.buy.add(new MethodRuleCore.Condition("EMA_FAST_ABOVE",0,0,0,0,false));
        config.side = "buy_only";
        MethodRuleCore.Audit audit = new MethodRuleCore.Audit(config);
        require(audit.requirements.ema, "EMA condition did not declare EMA dependency");
        MethodRuleCore.Row row = row();
        row.emaFast = Double.NaN;
        row.emaSlow = Double.NaN;
        require(MethodRuleCore.decide(config,row,new ArrayList<>(),audit).bias == 0,
                "EMA method should wait for EMA warmup");
    }

    private static void contradictionAuditMustWarn() {
        MethodRuleCore.Config config = new MethodRuleCore.Config();
        config.buy.add(new MethodRuleCore.Condition("CANDLE_BULL",0,0,0,0,false));
        config.buy.add(new MethodRuleCore.Condition("CANDLE_BEAR",0,0,0,0,false));
        MethodRuleCore.Audit audit = new MethodRuleCore.Audit(config);
        require(!audit.warnings.isEmpty(), "Contradictory method was not warned");
    }

    private static void ictConditionsMustEvaluate() {
        MethodRuleCore.Row row = row();
        row.recentSweepLow = true;
        row.mssUp = true;
        row.lastSwingHigh = 103;
        row.previousSwingHigh = 102;
        row.lastSwingLow = 99;
        row.previousSwingLow = 98;
        require(MethodRuleCore.evaluate(new MethodRuleCore.Condition("LIQUIDITY_SWEEP_MSS_BUY",0,0,0,0,false),row,new ArrayList<>()),
                "ICT liquidity sweep + MSS condition failed");
        require(MethodRuleCore.evaluate(new MethodRuleCore.Condition("SWING_HH_HL",0,0,0,0,false),row,new ArrayList<>()),
                "Swing structure condition failed");
    }

    private static MethodRuleCore.Row row() {
        MethodRuleCore.Row r = new MethodRuleCore.Row();
        r.open = 100;
        r.high = 102;
        r.low = 99;
        r.close = 101.5;
        r.prevHigh = 101;
        r.prevLow = 98.5;
        r.prevClose = 100;
        r.atr = 1;
        r.rollingHigh = 103;
        r.rollingLow = 97;
        r.lastSwingHigh = 103;
        r.lastSwingLow = 97;
        return r;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
