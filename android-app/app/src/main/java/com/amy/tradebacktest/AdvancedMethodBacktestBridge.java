package com.amy.tradebacktest;

import android.app.Activity;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Audited, no-code, streaming backtest bridge.
 *
 * This v2 engine deliberately derives feature requirements from active conditions.
 * An EMA period cannot influence a method unless an EMA block is actually present.
 */
public final class AdvancedMethodBacktestBridge {
    private static final int MAX_ZIP_DEPTH = 4;
    private static final long PROGRESS_EVERY = 100_000L;

    private final Activity activity;
    private final WebView webView;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Uri> selectedUris = new ArrayList<>();
    private final List<String> selectedNames = new ArrayList<>();
    private volatile boolean running;

    public AdvancedMethodBacktestBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    public synchronized void setSelectedFiles(List<Uri> uris) {
        selectedUris.clear();
        selectedNames.clear();
        if (uris != null) {
            for (Uri uri : uris) {
                selectedUris.add(uri);
                selectedNames.add(queryDisplayName(uri));
            }
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("count", selectedUris.size());
            payload.put("names", new JSONArray(selectedNames));
        } catch (JSONException ignored) {}
        emit("onFileSelected", payload);
    }

    @JavascriptInterface
    public String capabilities() {
        JSONObject result = new JSONObject();
        try {
            result.put("advanced_builder", true);
            result.put("ict", true);
            result.put("audit", true);
            result.put("ema_isolation", true);
            result.put("multi_select", true);
            result.put("nested_zip", true);
            result.put("streaming", true);
            result.put("selected_count", selectedUris.size());
            result.put("running", running);
            result.put("engine_version", "2.4");
        } catch (JSONException ignored) {}
        return result.toString();
    }

    @JavascriptInterface
    public void runSelectedFiles(String configJson) {
        final List<Uri> uris;
        final List<String> names;
        synchronized (this) {
            if (running) { emitError("Backtest masih berjalan."); return; }
            if (selectedUris.isEmpty()) { emitError("Pilih arsip candle terlebih dahulu."); return; }
            running = true;
            cancelled.set(false);
            uris = new ArrayList<>(selectedUris);
            names = new ArrayList<>(selectedNames);
        }

        Thread worker = new Thread(() -> {
            List<File> cached = new ArrayList<>();
            try {
                ParsedConfig parsed = ParsedConfig.fromJson(configJson);
                StreamingEngine engine = new StreamingEngine(parsed);
                ArchiveContext context = new ArchiveContext(engine, parsed.timeframe);

                List<Source> sources = new ArrayList<>();
                for (int i = 0; i < uris.size(); i++) sources.add(new Source(uris.get(i), names.get(i)));
                sources.sort((a,b) -> naturalCompare(a.name,b.name));

                for (Source source : sources) {
                    ensureNotCancelled();
                    emitProgress("Menyiapkan " + source.name, engine.candleCount, context.candleFiles, source.name);
                    String lower = source.name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".zip")) {
                        File file = copyUriToCache(source.uri, ".zip");
                        cached.add(file);
                        processZip(file, source.name, context, 0);
                    } else {
                        try (InputStream input = new BufferedInputStream(open(source.uri), 256 * 1024)) {
                            processCsv(input, source.name, context);
                        }
                    }
                }
                ensureNotCancelled();
                engine.finish();
                if (engine.candleCount == 0) {
                    throw new IOException("Tidak ditemukan file candle timeframe " + parsed.timeframe + ".");
                }

                JSONObject result = engine.toJson();
                JSONObject run = new JSONObject();
                run.put("method_name", parsed.core.name);
                run.put("timeframe", parsed.timeframe);
                run.put("archives", uris.size());
                run.put("files", context.candleFiles);
                run.put("ignored_files", context.ignoredFiles);
                run.put("candles", engine.candleCount);
                run.put("first_timestamp", engine.firstTimestamp);
                run.put("last_timestamp", engine.lastTimestamp);
                run.put("execution", "signal_close_entry_next_open");
                run.put("same_bar_policy", "sl_first");
                run.put("engine_version", "2.4-audited");
                run.put("method_fingerprint", parsed.fingerprint);
                result.put("run", run);
                emit("onComplete", result);
            } catch (InterruptedIOException e) {
                emitError("Backtest dibatalkan.");
            } catch (Exception e) {
                emitError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } finally {
                for (File file : cached) if (file != null && file.exists()) file.delete();
                running = false;
                cancelled.set(false);
            }
        }, "advanced-method-backtest");
        worker.start();
    }

    @JavascriptInterface public void cancel() { cancelled.set(true); }

    @JavascriptInterface
    public String auditMethod(String configJson) {
        try {
            ParsedConfig parsed = ParsedConfig.fromJson(configJson);
            return auditConfigJson(parsed).toString();
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            try { error.put("error", e.getMessage()); } catch (JSONException ignored) {}
            return error.toString();
        }
    }

    private static JSONObject auditConfigJson(ParsedConfig parsed) throws JSONException {
        MethodRuleCore.Audit audit = new MethodRuleCore.Audit(parsed.core);
        JSONObject json = new JSONObject();
        json.put("method_fingerprint", parsed.fingerprint);
        json.put("ema_active", audit.requirements.ema);
        json.put("rolling_active", audit.requirements.rolling);
        json.put("swing_active", audit.requirements.swing);
        json.put("fvg_active", audit.requirements.fvg);
        json.put("order_block_active", audit.requirements.orderBlock);
        json.put("features", new JSONArray(audit.requirements.activeNames()));
        json.put("warnings", new JSONArray(audit.warnings));
        json.put("buy_conditions", parsed.core.buy.size());
        json.put("sell_conditions", parsed.core.sell.size());
        return json;
    }

    private static final class ParsedConfig {
        final MethodRuleCore.Config core;
        final String timeframe;
        final String fingerprint;

        ParsedConfig(MethodRuleCore.Config core, String timeframe, String fingerprint) {
            this.core = core; this.timeframe = timeframe; this.fingerprint = fingerprint;
        }

        static ParsedConfig fromJson(String source) throws Exception {
            JSONObject json = new JSONObject(source == null ? "{}" : source);
            MethodRuleCore.Config c = new MethodRuleCore.Config();
            c.name = json.optString("method_name", "Metode tanpa nama").trim();
            if (c.name.isEmpty()) c.name = "Metode tanpa nama";
            c.side = enumValue(json.optString("side", "both"), "side", "both","buy_only","sell_only");
            c.buyLogic = enumValue(json.optString("buy_logic", "all"), "buy_logic", "all","any");
            c.sellLogic = enumValue(json.optString("sell_logic", "all"), "sell_logic", "all","any");
            c.buy = parseConditions(json.optJSONArray("buy_conditions"));
            c.sell = parseConditions(json.optJSONArray("sell_conditions"));
            c.slMode = enumValue(json.optString("sl_mode","signal_extreme"), "sl_mode",
                    "atr","signal_extreme","rolling_level","swing_level","order_block");
            c.lookback = clamp(json.optInt("lookback",20),3,10_000);
            c.atrPeriod = clamp(json.optInt("atr_period",14),2,10_000);
            c.emaFast = clamp(json.optInt("ema_fast",9),1,10_000);
            c.emaSlow = clamp(json.optInt("ema_slow",21),2,20_000);
            c.swingLeft = clamp(json.optInt("swing_left",3),1,20);
            c.swingRight = clamp(json.optInt("swing_right",3),1,20);
            c.structureLookback = clamp(json.optInt("structure_lookback",50),5,10_000);
            c.fvgLookback = clamp(json.optInt("fvg_lookback",20),3,2_000);
            c.obLookback = clamp(json.optInt("ob_lookback",30),3,2_000);
            c.slAtr = positive(json.optDouble("sl_atr",1.0),"SL ATR");
            c.stopBufferAtr = Math.max(0,json.optDouble("stop_buffer_atr",0.10));
            c.rr = positive(json.optDouble("rr_ratio",2.0),"RR");
            c.maxHoldBars = clamp(json.optInt("max_hold_bars",24),1,100_000);
            c.cooldownBars = clamp(json.optInt("cooldown_bars",0),0,100_000);
            c.costR = Math.max(0,json.optDouble("cost_per_trade_r",0.03));
            c.initialCapital = positive(json.optDouble("initial_capital",10_000),"Modal awal");
            c.riskPct = positive(json.optDouble("risk_per_trade_pct",1.0),"Risiko")/100.0;
            String timeframe = enumValue(json.optString("timeframe","M5").toUpperCase(Locale.ROOT),
                    "timeframe","M1","M5","M15","H1","H4","D1");
            validateConditions(c.buy);
            validateConditions(c.sell);
            if (c.buy.size() > 30 || c.sell.size() > 30) throw new JSONException("Maksimal 30 kondisi per arah.");
            String canonical = canonicalConfig(c,timeframe);
            return new ParsedConfig(c,timeframe,sha256(canonical).substring(0,16));
        }

        private static List<MethodRuleCore.Condition> parseConditions(JSONArray array) throws JSONException {
            List<MethodRuleCore.Condition> result = new ArrayList<>();
            if (array == null) return result;
            for (int i=0;i<array.length();i++) {
                JSONObject item = array.getJSONObject(i);
                result.add(new MethodRuleCore.Condition(
                        item.optString("type",""), item.optDouble("value",0), item.optDouble("value2",0),
                        item.optInt("period",0), item.optInt("period2",0), item.optBoolean("negate",false)
                ));
            }
            return result;
        }

        private static void validateConditions(List<MethodRuleCore.Condition> conditions) throws JSONException {
            for (MethodRuleCore.Condition condition : conditions) {
                if (!SUPPORTED.containsKey(condition.type)) throw new JSONException("Kondisi tidak dikenal: " + condition.type);
                if (!Double.isFinite(condition.value) || !Double.isFinite(condition.value2)) throw new JSONException("Nilai kondisi tidak valid: " + condition.type);
            }
        }
    }

    private static final Map<String,Boolean> SUPPORTED = new HashMap<>();
    static {
        String[] names = {
                "CANDLE_BULL","CANDLE_BEAR","BODY_RATIO_MIN","BODY_RATIO_MAX","UPPER_WICK_MIN","LOWER_WICK_MIN",
                "CLOSE_LOCATION_MIN","CLOSE_LOCATION_MAX","RANGE_ATR_MIN","RANGE_ATR_MAX","BODY_ATR_MIN",
                "EMA_FAST_ABOVE","EMA_FAST_BELOW","EMA_FAST_RISING","EMA_FAST_FALLING","EMA_SLOW_RISING","EMA_SLOW_FALLING",
                "BREAK_HIGH","BREAK_LOW","SWEEP_HIGH","SWEEP_LOW","RBS_RETEST","SBR_RETEST","RANGE_POSITION_MIN","RANGE_POSITION_MAX",
                "DIST_HIGH_ATR_MAX","DIST_LOW_ATR_MAX","CONSECUTIVE_BULL","CONSECUTIVE_BEAR","CLOSE_ABOVE_PREV_HIGH","CLOSE_BELOW_PREV_LOW",
                "HH_HL","LH_LL","SWING_HH_HL","SWING_LH_LL","BOS_UP","BOS_DOWN","CHOCH_UP","CHOCH_DOWN","MSS_UP","MSS_DOWN",
                "LIQUIDITY_SWEEP_MSS_BUY","LIQUIDITY_SWEEP_MSS_SELL","DISPLACEMENT_BULL","DISPLACEMENT_BEAR",
                "FVG_BULL_ACTIVE","FVG_BEAR_ACTIVE","FVG_BULL_RETEST","FVG_BEAR_RETEST","OB_BULL_RETEST","OB_BEAR_RETEST",
                "BREAKER_BULL_RETEST","BREAKER_BEAR_RETEST","EQUAL_HIGHS","EQUAL_LOWS","PREMIUM_ZONE","DISCOUNT_ZONE","EQUILIBRIUM_ZONE",
                "OTE_BUY_ZONE","OTE_SELL_ZONE","FIB_RETRACE_BUY","FIB_RETRACE_SELL","UTC_HOUR_RANGE","KILLZONE_ASIA","KILLZONE_LONDON","KILLZONE_NEW_YORK"
        };
        for (String name : names) SUPPORTED.put(name,true);
    }

    private static final class StreamingEngine {
        final ParsedConfig parsed;
        final MethodRuleCore.Config config;
        final MethodRuleCore.FeatureRequirements requirements;
        final MethodRuleCore.Audit audit;
        final RingHistory history;
        final Deque<IndexedPrice> highDeque = new ArrayDeque<>();
        final Deque<IndexedPrice> lowDeque = new ArrayDeque<>();
        final Deque<Double> trWindow = new ArrayDeque<>();
        final Deque<MethodRuleCore.Row> pivotWindow = new ArrayDeque<>();
        double trSum;
        double emaFast = Double.NaN, emaSlow = Double.NaN;
        double previousEmaFast = Double.NaN, previousEmaSlow = Double.NaN;
        MethodRuleCore.Row previous;
        long candleCount;
        long currentIndex = -1;
        long lastEpoch = Long.MIN_VALUE;
        String firstTimestamp="", lastTimestamp="";
        double lastClose = Double.NaN;
        int consecutiveBull, consecutiveBear;
        double lastSwingHigh=Double.NaN, previousSwingHigh=Double.NaN;
        double lastSwingLow=Double.NaN, previousSwingLow=Double.NaN;
        int structureBias;
        long recentSweepLowIndex=Long.MIN_VALUE, recentSweepHighIndex=Long.MIN_VALUE;
        Zone bullFvg, bearFvg, bullOb, bearOb, bullBreaker, bearBreaker;
        Signal pending;
        Trade openTrade;
        long cooldownUntil=-1;

        double capital, peakCapital, maxDdPct, cumulativeR, peakR, maxDdR, gains, losses;
        long tradeCount, winCount;
        final Map<String,GroupStats> setupStats = new LinkedHashMap<>();
        final Map<String,GroupStats> directionStats = new LinkedHashMap<>();
        final Deque<JSONObject> recentTrades = new ArrayDeque<>();
        final List<JSONObject> equity = new ArrayList<>();

        StreamingEngine(ParsedConfig parsed) {
            this.parsed=parsed; this.config=parsed.core; this.requirements=config.requirements(); this.audit=new MethodRuleCore.Audit(config);
            int conditionPeriod = 0;
            for (MethodRuleCore.Condition c : config.buy) conditionPeriod=Math.max(conditionPeriod,c.period);
            for (MethodRuleCore.Condition c : config.sell) conditionPeriod=Math.max(conditionPeriod,c.period);
            int capacity=Math.max(100,Math.max(config.structureLookback,Math.max(config.obLookback,Math.max(config.fvgLookback,conditionPeriod)))+20);
            this.history=new RingHistory(Math.min(20_000,capacity));
            this.capital=config.initialCapital; this.peakCapital=capital; addEquity("",0);
        }

        void onCandle(Candle candle) throws Exception {
            currentIndex++; candleCount++; lastClose=candle.close;
            if (firstTimestamp.isEmpty()) firstTimestamp=candle.timestamp;
            lastTimestamp=candle.timestamp;
            if (candle.epochMillis != Long.MIN_VALUE) {
                if (lastEpoch != Long.MIN_VALUE && candle.epochMillis <= lastEpoch) {
                    throw new IOException("Timestamp tidak naik: " + candle.timestamp + " setelah " + lastTimestamp + ". Periksa urutan arsip/timeframe.");
                }
                lastEpoch=candle.epochMillis;
            }

            if (openTrade==null && pending!=null) { openPending(candle); pending=null; }
            if (openTrade!=null) evaluateTrade(candle);

            long minimum=currentIndex-config.lookback;
            while(!highDeque.isEmpty()&&highDeque.peekFirst().index<minimum) highDeque.removeFirst();
            while(!lowDeque.isEmpty()&&lowDeque.peekFirst().index<minimum) lowDeque.removeFirst();
            double rollingHigh=highDeque.isEmpty()?Double.NaN:highDeque.peekFirst().price;
            double rollingLow=lowDeque.isEmpty()?Double.NaN:lowDeque.peekFirst().price;

            double tr=previous==null?candle.high-candle.low:Math.max(candle.high-candle.low,Math.max(Math.abs(candle.high-previous.close),Math.abs(candle.low-previous.close)));
            trWindow.addLast(tr); trSum+=tr;
            while(trWindow.size()>config.atrPeriod) trSum-=trWindow.removeFirst();
            double atr=trWindow.size()==config.atrPeriod?trSum/config.atrPeriod:Double.NaN;

            if (requirements.ema) {
                previousEmaFast=emaFast; previousEmaSlow=emaSlow;
                emaFast=nextEma(emaFast,candle.close,config.emaFast);
                emaSlow=nextEma(emaSlow,candle.close,config.emaSlow);
            } else {
                // Explicit isolation: inactive EMA is not computed and remains NaN.
                previousEmaFast=previousEmaSlow=emaFast=emaSlow=Double.NaN;
            }

            if(candle.close>candle.open){consecutiveBull++;consecutiveBear=0;}
            else if(candle.close<candle.open){consecutiveBear++;consecutiveBull=0;} else {consecutiveBull=consecutiveBear=0;}

            MethodRuleCore.Row row=new MethodRuleCore.Row();
            row.index=currentIndex;row.epochMillis=candle.epochMillis;row.timestamp=candle.timestamp;
            row.open=candle.open;row.high=candle.high;row.low=candle.low;row.close=candle.close;
            if(previous!=null){row.prevOpen=previous.open;row.prevHigh=previous.high;row.prevLow=previous.low;row.prevClose=previous.close;}
            row.atr=atr;row.rollingHigh=rollingHigh;row.rollingLow=rollingLow;
            row.emaFast=emaFast;row.emaSlow=emaSlow;row.prevEmaFast=previousEmaFast;row.prevEmaSlow=previousEmaSlow;
            row.consecutiveBull=consecutiveBull;row.consecutiveBear=consecutiveBear;

            updatePivots(row);
            row.lastSwingHigh=lastSwingHigh;row.previousSwingHigh=previousSwingHigh;
            row.lastSwingLow=lastSwingLow;row.previousSwingLow=previousSwingLow;
            int biasBefore=structureBias;
            row.bosUp=Double.isFinite(lastSwingHigh)&&candle.close>lastSwingHigh&&(previous==null||previous.close<=lastSwingHigh);
            row.bosDown=Double.isFinite(lastSwingLow)&&candle.close<lastSwingLow&&(previous==null||previous.close>=lastSwingLow);
            row.chochUp=row.bosUp&&biasBefore<0; row.chochDown=row.bosDown&&biasBefore>0;
            row.mssUp=row.chochUp; row.mssDown=row.chochDown;
            if(row.bosUp) structureBias=1; if(row.bosDown) structureBias=-1;
            row.structureBias=structureBias;

            if(Double.isFinite(atr)&&Double.isFinite(rollingLow)&&candle.low<rollingLow-0.05*atr&&candle.close>rollingLow) recentSweepLowIndex=currentIndex;
            if(Double.isFinite(atr)&&Double.isFinite(rollingHigh)&&candle.high>rollingHigh+0.05*atr&&candle.close<rollingHigh) recentSweepHighIndex=currentIndex;
            row.recentSweepLow=currentIndex-recentSweepLowIndex<=Math.max(3,config.structureLookback/4);
            row.recentSweepHigh=currentIndex-recentSweepHighIndex<=Math.max(3,config.structureLookback/4);

            updateFvg(row);
            updateOrderBlocks(row);
            row.bullishFvgLow=zoneLow(bullFvg);row.bullishFvgHigh=zoneHigh(bullFvg);
            row.bearishFvgLow=zoneLow(bearFvg);row.bearishFvgHigh=zoneHigh(bearFvg);
            row.bullishObLow=zoneLow(bullOb);row.bullishObHigh=zoneHigh(bullOb);
            row.bearishObLow=zoneLow(bearOb);row.bearishObHigh=zoneHigh(bearOb);
            row.bullishBreakerLow=zoneLow(bullBreaker);row.bullishBreakerHigh=zoneHigh(bullBreaker);
            row.bearishBreakerLow=zoneLow(bearBreaker);row.bearishBreakerHigh=zoneHigh(bearBreaker);
            row.impulseLow=lastSwingLow;row.impulseHigh=lastSwingHigh;

            if(openTrade==null&&pending==null&&currentIndex>=cooldownUntil){
                MethodRuleCore.SignalDecision decision=MethodRuleCore.decide(config,row,history,audit);
                if(decision.bias!=0) pending=new Signal(decision.bias,row,decision.bias>0?decision.matchedBuy:decision.matchedSell);
            }

            history.add(row); previous=row;
            while(!highDeque.isEmpty()&&highDeque.peekLast().price<=candle.high)highDeque.removeLast();
            highDeque.addLast(new IndexedPrice(currentIndex,candle.high));
            while(!lowDeque.isEmpty()&&lowDeque.peekLast().price>=candle.low)lowDeque.removeLast();
            lowDeque.addLast(new IndexedPrice(currentIndex,candle.low));
        }

        private void updatePivots(MethodRuleCore.Row row) {
            pivotWindow.addLast(row);
            int size=config.swingLeft+config.swingRight+1;
            while(pivotWindow.size()>size)pivotWindow.removeFirst();
            if(pivotWindow.size()<size)return;
            List<MethodRuleCore.Row> list=new ArrayList<>(pivotWindow);
            MethodRuleCore.Row candidate=list.get(config.swingLeft);
            boolean high=true,low=true;
            for(int i=0;i<list.size();i++) if(i!=config.swingLeft){ high&=candidate.high>list.get(i).high; low&=candidate.low<list.get(i).low; }
            if(high){previousSwingHigh=lastSwingHigh;lastSwingHigh=candidate.high;}
            if(low){previousSwingLow=lastSwingLow;lastSwingLow=candidate.low;}
        }

        private void updateFvg(MethodRuleCore.Row row) {
            if(history.size()>=2){
                MethodRuleCore.Row two=history.get(history.size()-2);
                if(row.low>two.high)bullFvg=new Zone(two.high,row.low,currentIndex);
                if(row.high<two.low)bearFvg=new Zone(row.high,two.low,currentIndex);
            }
            if(bullFvg!=null&&(row.close<bullFvg.low||currentIndex-bullFvg.created>config.fvgLookback))bullFvg=null;
            if(bearFvg!=null&&(row.close>bearFvg.high||currentIndex-bearFvg.created>config.fvgLookback))bearFvg=null;
        }

        private void updateOrderBlocks(MethodRuleCore.Row row) {
            if(row.bosUp){ MethodRuleCore.Row source=findLastOpposite(false); if(source!=null)bullOb=new Zone(source.low,source.high,currentIndex); }
            if(row.bosDown){ MethodRuleCore.Row source=findLastOpposite(true); if(source!=null)bearOb=new Zone(source.low,source.high,currentIndex); }
            if(bearOb!=null&&row.close>bearOb.high){bullBreaker=new Zone(bearOb.low,bearOb.high,currentIndex);bearOb=null;}
            if(bullOb!=null&&row.close<bullOb.low){bearBreaker=new Zone(bullOb.low,bullOb.high,currentIndex);bullOb=null;}
            if(bullOb!=null&&currentIndex-bullOb.created>config.obLookback)bullOb=null;
            if(bearOb!=null&&currentIndex-bearOb.created>config.obLookback)bearOb=null;
            if(bullBreaker!=null&&currentIndex-bullBreaker.created>config.obLookback)bullBreaker=null;
            if(bearBreaker!=null&&currentIndex-bearBreaker.created>config.obLookback)bearBreaker=null;
        }

        private MethodRuleCore.Row findLastOpposite(boolean bullish) {
            int start=Math.max(0,history.size()-config.obLookback);
            for(int i=history.size()-1;i>=start;i--){MethodRuleCore.Row r=history.get(i);if(bullish?r.close>r.open:r.close<r.open)return r;}
            return null;
        }

        private void openPending(Candle candle) {
            Signal s=pending;double entry=candle.open,atr=s.row.atr,buffer=config.stopBufferAtr*(Double.isFinite(atr)?atr:0),sl;
            if(s.bias>0){
                double structural;
                switch(config.slMode){
                    case "atr":structural=entry-config.slAtr*atr;break;
                    case "rolling_level":structural=s.row.rollingLow-buffer;break;
                    case "swing_level":structural=s.row.lastSwingLow-buffer;break;
                    case "order_block":structural=Double.isFinite(s.row.bullishObLow)?s.row.bullishObLow-buffer:s.row.low-buffer;break;
                    default:structural=s.row.low-buffer;
                }
                sl=Math.min(structural,entry-config.slAtr*atr);double risk=entry-sl;if(!(risk>0))return;
                openTrade=new Trade(s,candle.timestamp,currentIndex,entry,sl,entry+config.rr*risk);
            }else{
                double structural;
                switch(config.slMode){
                    case "atr":structural=entry+config.slAtr*atr;break;
                    case "rolling_level":structural=s.row.rollingHigh+buffer;break;
                    case "swing_level":structural=s.row.lastSwingHigh+buffer;break;
                    case "order_block":structural=Double.isFinite(s.row.bearishObHigh)?s.row.bearishObHigh+buffer:s.row.high+buffer;break;
                    default:structural=s.row.high+buffer;
                }
                sl=Math.max(structural,entry+config.slAtr*atr);double risk=sl-entry;if(!(risk>0))return;
                openTrade=new Trade(s,candle.timestamp,currentIndex,entry,sl,entry-config.rr*risk);
            }
        }

        private void evaluateTrade(Candle c)throws JSONException{
            Trade t=openTrade;boolean sl=t.direction>0?c.low<=t.sl:c.high>=t.sl;boolean tp=t.direction>0?c.high>=t.tp:c.low<=t.tp;
            if(sl){closeTrade(t.sl,"SL",c.timestamp);return;} if(tp){closeTrade(t.tp,"TP",c.timestamp);return;}
            if(currentIndex-t.entryIndex+1>=config.maxHoldBars)closeTrade(c.close,"TIME",c.timestamp);
        }

        private void closeTrade(double exit,String reason,String time)throws JSONException{
            Trade t=openTrade;if(t==null)return;double risk=t.direction>0?t.entry-t.sl:t.sl-t.entry;
            double gross=t.direction>0?(exit-t.entry)/risk:(t.entry-exit)/risk;double net=gross-config.costR;
            double pnl=capital*config.riskPct*net;capital+=pnl;tradeCount++;cumulativeR+=net;if(net>0){winCount++;gains+=net;}else if(net<0)losses+=-net;
            peakCapital=Math.max(peakCapital,capital);maxDdPct=Math.max(maxDdPct,(peakCapital-capital)/peakCapital*100);peakR=Math.max(peakR,cumulativeR);maxDdR=Math.max(maxDdR,peakR-cumulativeR);
            String direction=t.direction>0?"Long":"Short";setupStats.computeIfAbsent(t.label,k->new GroupStats()).add(net);directionStats.computeIfAbsent(direction,k->new GroupStats()).add(net);
            JSONObject item=new JSONObject();item.put("entry_time",t.entryTime);item.put("exit_time",time);item.put("direction",direction);item.put("setup",t.label);item.put("matched",new JSONArray(t.matched));item.put("entry_price",round(t.entry,8));item.put("exit_price",round(exit,8));item.put("sl",round(t.sl,8));item.put("tp",round(t.tp,8));item.put("bars_held",currentIndex-t.entryIndex+1);item.put("gross_r",round(gross,5));item.put("cost_r",round(config.costR,5));item.put("r_multiple",round(net,5));item.put("reason",reason);
            recentTrades.addLast(item);while(recentTrades.size()>100)recentTrades.removeFirst();addEquity(time,tradeCount);openTrade=null;cooldownUntil=currentIndex+config.cooldownBars;
        }

        void finish()throws JSONException{if(openTrade!=null&&Double.isFinite(lastClose))closeTrade(lastClose,"DATA_END",lastTimestamp);pending=null;}

        JSONObject toJson()throws JSONException{
            JSONObject root=new JSONObject(),metrics=new JSONObject();metrics.put("total_trades",tradeCount);metrics.put("winrate",tradeCount==0?0:round(100.0*winCount/tradeCount,2));metrics.put("profit_factor",losses>0?round(gains/losses,4):(gains>0?999:0));metrics.put("expectancy_r",tradeCount==0?0:round(cumulativeR/tradeCount,5));metrics.put("total_r",round(cumulativeR,4));metrics.put("total_pnl",round(capital-config.initialCapital,2));metrics.put("ending_equity",round(capital,2));metrics.put("max_drawdown",round(maxDdPct,2));metrics.put("max_drawdown_r",round(maxDdR,4));root.put("metrics",metrics);
            JSONArray setups=new JSONArray();for(Map.Entry<String,GroupStats>e:setupStats.entrySet())setups.put(e.getValue().json(e.getKey()));root.put("setup_breakdown",setups);
            JSONArray dirs=new JSONArray();for(Map.Entry<String,GroupStats>e:directionStats.entrySet())dirs.put(e.getValue().json(e.getKey()));root.put("direction_breakdown",dirs);
            JSONArray trades=new JSONArray();for(JSONObject t:recentTrades)trades.put(t);root.put("trades",trades);root.put("equity_points",new JSONArray(equity));root.put("audit",auditJson());return root;
        }

        private JSONObject auditJson()throws JSONException{
            JSONObject j=auditConfigJson(parsed);j.put("evaluated_bars",audit.evaluatedBars);j.put("warmup_blocked",audit.warmupBlocked);j.put("buy_signals",audit.buySignals);j.put("sell_signals",audit.sellSignals);j.put("signal_conflicts",audit.conflicts);
            JSONArray stats=new JSONArray();for(Map.Entry<String,MethodRuleCore.ConditionStats>e:audit.conditionStats.entrySet()){JSONObject s=new JSONObject();s.put("condition",e.getKey());s.put("evaluated",e.getValue().evaluated);s.put("passed",e.getValue().passed);s.put("pass_rate",e.getValue().evaluated==0?0:round(100.0*e.getValue().passed/e.getValue().evaluated,2));stats.put(s);}j.put("condition_stats",stats);
            if(audit.conflicts>0){JSONArray w=j.getJSONArray("warnings");w.put("Ada "+audit.conflicts+" candle ketika BUY dan SELL aktif bersamaan; semua konflik dibatalkan.");}
            return j;
        }

        private void addEquity(String time,long n){try{JSONObject p=new JSONObject();p.put("trade",n);p.put("timestamp",time);p.put("equity",round(capital,2));p.put("cumulative_r",round(cumulativeR,4));equity.add(p);if(equity.size()>2400){List<JSONObject> compact=new ArrayList<>();compact.add(equity.get(0));for(int i=2;i<equity.size()-1;i+=2)compact.add(equity.get(i));compact.add(equity.get(equity.size()-1));equity.clear();equity.addAll(compact);}}catch(Exception ignored){}}
    }

    private static final class RingHistory extends AbstractList<MethodRuleCore.Row>{
        private final MethodRuleCore.Row[] data;private int start,size;
        RingHistory(int capacity){data=new MethodRuleCore.Row[Math.max(10,capacity)];}
        @Override public MethodRuleCore.Row get(int index){if(index<0||index>=size)throw new IndexOutOfBoundsException();return data[(start+index)%data.length];}
        @Override public int size(){return size;}
        @Override public boolean add(MethodRuleCore.Row row){if(size<data.length){data[(start+size)%data.length]=row;size++;}else{data[start]=row;start=(start+1)%data.length;}return true;}
    }

    private static final class Signal{final int bias;final MethodRuleCore.Row row;final List<String>matched;Signal(int b,MethodRuleCore.Row r,List<String>m){bias=b;row=r;matched=new ArrayList<>(m);}}
    private static final class Trade{final int direction;final String label,entryTime;final long entryIndex;final double entry,sl,tp;final List<String>matched;Trade(Signal s,String time,long idx,double e,double sl,double tp){direction=s.bias;label=s.bias>0?"CUSTOM_BUY":"CUSTOM_SELL";entryTime=time;entryIndex=idx;entry=e;this.sl=sl;this.tp=tp;matched=s.matched;}}
    private static final class Zone{final double low,high;final long created;Zone(double l,double h,long c){low=Math.min(l,h);high=Math.max(l,h);created=c;}}
    private static final class IndexedPrice{final long index;final double price;IndexedPrice(long i,double p){index=i;price=p;}}
    private static final class GroupStats{long trades,wins;double total,gains,losses;void add(double r){trades++;total+=r;if(r>0){wins++;gains+=r;}else if(r<0)losses+=-r;}JSONObject json(String name)throws JSONException{JSONObject j=new JSONObject();j.put("name",name);j.put("trades",trades);j.put("winrate",trades==0?0:round(100.0*wins/trades,2));j.put("total_r",round(total,4));j.put("avg_r",trades==0?0:round(total/trades,5));j.put("profit_factor",losses>0?round(gains/losses,4):(gains>0?999:0));return j;}}
    private static final class Source{final Uri uri;final String name;Source(Uri u,String n){uri=u;name=n;}}
    private static final class ArchiveContext{final StreamingEngine engine;final String timeframe;int candleFiles,ignoredFiles;ArchiveContext(StreamingEngine e,String t){engine=e;timeframe=t;}}
    private static final class Candle{final String timestamp;final long epochMillis;final double open,high,low,close;Candle(String t,long epoch,double o,double h,double l,double c){timestamp=t;epochMillis=epoch;open=o;high=h;low=l;close=c;}}

    private void processZip(File path,String sourceName,ArchiveContext context,int depth)throws Exception{
        ensureNotCancelled();if(depth>MAX_ZIP_DEPTH)throw new IOException("ZIP bertingkat melebihi "+MAX_ZIP_DEPTH+" tingkat.");
        try(ZipFile zip=new ZipFile(path)){
            List<? extends ZipEntry> entries=Collections.list(zip.entries());entries.removeIf(e->e.isDirectory()||ignoredName(e.getName()));entries.sort((a,b)->naturalCompare(a.getName(),b.getName()));
            for(ZipEntry entry:entries){ensureNotCancelled();String lower=entry.getName().toLowerCase(Locale.ROOT);
                if(lower.endsWith(".zip")){File nested=File.createTempFile("nested-",".zip",path.getParentFile());try(InputStream in=new BufferedInputStream(zip.getInputStream(entry),128*1024);FileOutputStream out=new FileOutputStream(nested)){copy(in,out);}try{processZip(nested,sourceName+"/"+entry.getName(),context,depth+1);}finally{nested.delete();}}
                else if(isCandleFile(lower)){if(matchesTimeframe(entry.getName(),context.timeframe)){try(InputStream in=new BufferedInputStream(zip.getInputStream(entry),256*1024)){processCsv(in,sourceName+"/"+entry.getName(),context);}}else context.ignoredFiles++;}
                else context.ignoredFiles++;
            }
        }
    }

    private void processCsv(InputStream input,String name,ArchiveContext context)throws Exception{
        context.candleFiles++;emitProgress("Membaca "+name,context.engine.candleCount,context.candleFiles,name);
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8),256*1024)){
            String header;do{header=reader.readLine();}while(header!=null&&header.trim().isEmpty());if(header==null)return;
            header=stripBom(header);char delimiter=delimiter(header);CsvSchema schema=CsvSchema.from(split(header,delimiter));
            String line;long rows=0;while((line=reader.readLine())!=null){ensureNotCancelled();if(line.trim().isEmpty())continue;Candle candle=schema.parse(split(line,delimiter));if(candle==null)throw new IOException("Baris candle tidak valid di "+name+" setelah "+rows+" baris.");context.engine.onCandle(candle);rows++;if(context.engine.candleCount%PROGRESS_EVERY==0)emitProgress("Memproses "+name,context.engine.candleCount,context.candleFiles,name);}if(rows==0)throw new IOException("Tidak ada candle di "+name);}
    }

    private static final class CsvSchema{
        final int timestamp,date,time,open,high,low,close;final List<SimpleDateFormat>formats;
        CsvSchema(int ts,int d,int t,int o,int h,int l,int c){timestamp=ts;date=d;time=t;open=o;high=h;low=l;close=c;formats=formats();}
        static CsvSchema from(List<String>header)throws IOException{Map<String,Integer>m=new HashMap<>();for(int i=0;i<header.size();i++)m.put(norm(stripBom(header.get(i))),i);int o=find(m,"open","bidopen","askopen","o"),h=find(m,"high","bidhigh","askhigh","h"),l=find(m,"low","bidlow","asklow","l"),c=find(m,"close","bidclose","askclose","c");if(o<0||h<0||l<0||c<0)throw new IOException("CSV wajib memiliki open, high, low, close.");return new CsvSchema(find(m,"timestamp","datetime","dateandtime","gmttime","datetimeutc"),find(m,"date","tradingdate"),find(m,"time","timeofday"),o,h,l,c);}
        Candle parse(List<String>v){try{double o=num(val(v,open)),h=num(val(v,high)),l=num(val(v,low)),c=num(val(v,close));if(!Double.isFinite(o)||!Double.isFinite(h)||!Double.isFinite(l)||!Double.isFinite(c)||h<Math.max(o,c)||l>Math.min(o,c)||h<l)return null;String ts=timestamp>=0?val(v,timestamp):(date>=0?(val(v,date)+(time>=0?" "+val(v,time):"")):"");long epoch=parseTime(ts,formats);return new Candle(ts,epoch,o,h,l,c);}catch(Exception e){return null;}}
        static int find(Map<String,Integer>m,String...keys){for(String k:keys)if(m.containsKey(k))return m.get(k);return-1;}static String val(List<String>v,int i){return i>=0&&i<v.size()?v.get(i).trim():"";}static String norm(String s){return s.toLowerCase(Locale.ROOT).replaceAll("[ _./-]","");}static double num(String s){String x=s.trim().replace("\u00a0","").replace(" ","");if(x.contains(",")&&!x.contains("."))x=x.replace(',','.');return Double.parseDouble(x);}
    }

    private static List<SimpleDateFormat> formats(){String[]patterns={"yyyy.MM.dd HH:mm:ss.SSS","yyyy.MM.dd HH:mm:ss","yyyy.MM.dd HH:mm","yyyy-MM-dd HH:mm:ss.SSS","yyyy-MM-dd HH:mm:ss","yyyy-MM-dd HH:mm","yyyy-MM-dd'T'HH:mm:ss.SSSX","yyyy-MM-dd'T'HH:mm:ssX","MM/dd/yyyy HH:mm:ss","dd/MM/yyyy HH:mm:ss"};List<SimpleDateFormat>list=new ArrayList<>();for(String p:patterns){SimpleDateFormat f=new SimpleDateFormat(p,Locale.US);f.setLenient(false);f.setTimeZone(TimeZone.getTimeZone("UTC"));list.add(f);}return list;}
    private static long parseTime(String s,List<SimpleDateFormat>formats){if(s==null||s.trim().isEmpty())return Long.MIN_VALUE;String x=s.trim();if(x.matches("\\d{10,13}")){long n=Long.parseLong(x);return x.length()==10?n*1000:n;}for(SimpleDateFormat f:formats){ParsePosition p=new ParsePosition(0);Date d=f.parse(x,p);if(d!=null&&p.getIndex()==x.length())return d.getTime();}return Long.MIN_VALUE;}

    private InputStream open(Uri uri)throws IOException{InputStream input=activity.getContentResolver().openInputStream(uri);if(input==null)throw new IOException("File tidak dapat dibuka.");return input;}
    private File copyUriToCache(Uri uri,String suffix)throws Exception{File dir=new File(activity.getCacheDir(),"method-lab");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Tidak dapat membuat cache.");File target=File.createTempFile("archive-",suffix,dir);try(InputStream in=new BufferedInputStream(open(uri),256*1024);FileOutputStream out=new FileOutputStream(target)){copy(in,out);}return target;}
    private void copy(InputStream in,FileOutputStream out)throws Exception{byte[]b=new byte[256*1024];int n;while((n=in.read(b))>=0){ensureNotCancelled();out.write(b,0,n);}}
    private String queryDisplayName(Uri uri){try(Cursor c=activity.getContentResolver().query(uri,null,null,null,null)){if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0){String n=c.getString(i);if(n!=null&&!n.trim().isEmpty())return n;}}}catch(Exception ignored){}String last=uri.getLastPathSegment();return last==null?"archive":last;}
    private void ensureNotCancelled()throws InterruptedIOException{if(cancelled.get())throw new InterruptedIOException("cancelled");}
    private void emitProgress(String message,long candles,int files,String current){JSONObject p=new JSONObject();try{p.put("message",message);p.put("candles",candles);p.put("files",files);p.put("current_file",current);}catch(Exception ignored){}emit("onProgress",p);}
    private void emitError(String message){JSONObject p=new JSONObject();try{p.put("message",message==null?"Kesalahan tidak diketahui":message);}catch(Exception ignored){}emit("onError",p);}
    private void emit(String callback,JSONObject payload){String script="window.NativeMethodBuilder&&window.NativeMethodBuilder."+callback+"("+payload.toString()+");";activity.runOnUiThread(()->webView.evaluateJavascript(script,null));}

    private static String enumValue(String value,String field,String...allowed)throws JSONException{for(String a:allowed)if(a.equals(value))return value;throw new JSONException(field+" tidak valid: "+value);}
    private static int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));}private static double positive(double v,String name)throws JSONException{if(!Double.isFinite(v)||v<=0)throw new JSONException(name+" harus lebih besar dari nol.");return v;}
    private static double nextEma(double previous,double close,int period){return Double.isFinite(previous)?previous+(2.0/(period+1.0))*(close-previous):close;}
    private static double zoneLow(Zone z){return z==null?Double.NaN:z.low;}private static double zoneHigh(Zone z){return z==null?Double.NaN:z.high;}
    private static boolean isCandleFile(String n){return n.endsWith(".csv")||n.endsWith(".txt");}
    private static boolean ignoredName(String n){String x=n.toLowerCase(Locale.ROOT);return x.startsWith("__macosx/")||x.endsWith(".ds_store")||x.endsWith("thumbs.db")||x.contains("manifest")||x.contains("audit")||x.contains("report");}
    private static boolean matchesTimeframe(String name,String timeframe){String u=name.toUpperCase(Locale.ROOT).replace('\\','/');return u.matches(".*(^|[/_.-])"+timeframe+"([/_.-]|$).*")||u.endsWith(timeframe+".CSV")||u.endsWith(timeframe+".TXT");}
    private static String stripBom(String s){return s!=null&&s.startsWith("\ufeff")?s.substring(1):s;}private static char delimiter(String h){int c=count(h,','),s=count(h,';'),t=count(h,'\t');return t>=c&&t>=s&&t>0?'\t':s>c?';':',';}private static int count(String s,char c){int n=0;for(int i=0;i<s.length();i++)if(s.charAt(i)==c)n++;return n;}
    private static List<String> split(String line,char d){List<String>out=new ArrayList<>();StringBuilder b=new StringBuilder();boolean q=false;for(int i=0;i<line.length();i++){char c=line.charAt(i);if(c=='"'){if(q&&i+1<line.length()&&line.charAt(i+1)=='"'){b.append('"');i++;}else q=!q;}else if(c==d&&!q){out.add(b.toString().trim());b.setLength(0);}else b.append(c);}out.add(b.toString().trim());return out;}
    private static int naturalCompare(String a,String b){int i=0,j=0;while(i<a.length()&&j<b.length()){char x=Character.toLowerCase(a.charAt(i)),y=Character.toLowerCase(b.charAt(j));if(Character.isDigit(x)&&Character.isDigit(y)){int si=i,sj=j;while(i<a.length()&&Character.isDigit(a.charAt(i)))i++;while(j<b.length()&&Character.isDigit(b.charAt(j)))j++;String na=a.substring(si,i).replaceFirst("^0+(?!$)",""),nb=b.substring(sj,j).replaceFirst("^0+(?!$)","");if(na.length()!=nb.length())return Integer.compare(na.length(),nb.length());int c=na.compareTo(nb);if(c!=0)return c;}else{if(x!=y)return Character.compare(x,y);i++;j++;}}return Integer.compare(a.length(),b.length());}
    private static String canonicalConfig(MethodRuleCore.Config c,String timeframe){StringBuilder b=new StringBuilder();b.append(c.name).append('|').append(timeframe).append('|').append(c.side).append('|').append(c.buyLogic).append('|').append(c.sellLogic).append('|').append(c.slMode).append('|').append(c.lookback).append('|').append(c.atrPeriod).append('|').append(c.emaFast).append('|').append(c.emaSlow).append('|').append(c.swingLeft).append('|').append(c.swingRight).append('|').append(c.slAtr).append('|').append(c.rr);for(MethodRuleCore.Condition x:c.buy)b.append("|B:").append(x.fingerprint());for(MethodRuleCore.Condition x:c.sell)b.append("|S:").append(x.fingerprint());return b.toString();}
    private static String sha256(String text)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");byte[]d=md.digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format(Locale.US,"%02x",x));return b.toString();}
    private static double round(double v,int decimals){if(!Double.isFinite(v))return 0;double s=Math.pow(10,decimals);return Math.round(v*s)/s;}
}
