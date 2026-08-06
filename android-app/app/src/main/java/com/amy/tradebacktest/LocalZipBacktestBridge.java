package com.amy.tradebacktest;

import android.app.Activity;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Local streaming ZIP/CSV backtest engine for Android. */
public final class LocalZipBacktestBridge {
    private static final int MAX_ZIP_DEPTH = 3;
    private static final long PROGRESS_EVERY = 100_000L;
    private static final String[] TIMEFRAMES = {"M1", "M5", "M15", "H1", "H4", "D1"};

    private final Activity activity;
    private final WebView webView;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Object selectionLock = new Object();
    private final List<SelectedFile> selectedFiles = new ArrayList<>();
    private volatile boolean running = false;

    public LocalZipBacktestBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    public void setSelectedFiles(List<Uri> uris) {
        List<SelectedFile> files = new ArrayList<>();
        for (Uri uri : uris) {
            if (uri != null) files.add(new SelectedFile(uri, displayName(uri)));
        }
        files.sort((a, b) -> naturalCompare(a.name, b.name));
        synchronized (selectionLock) {
            selectedFiles.clear();
            selectedFiles.addAll(files);
        }
        JSONObject payload = new JSONObject();
        try {
            payload.put("count", files.size());
            payload.put("name", selectionSummary(files));
            JSONArray names = new JSONArray();
            for (SelectedFile file : files) names.put(file.name);
            payload.put("names", names);
        } catch (JSONException ignored) {
        }
        emit("onFileSelected", payload);
    }

    @JavascriptInterface
    public String capabilities() {
        JSONObject json = new JSONObject();
        try {
            List<SelectedFile> files = selectionSnapshot();
            json.put("native_streaming", true);
            json.put("multiple_files", true);
            json.put("zip", true);
            json.put("nested_zip", true);
            json.put("timeframe_filter", true);
            json.put("hard_candle_limit", JSONObject.NULL);
            json.put("selected_count", files.size());
            json.put("selected_name", selectionSummary(files));
            json.put("running", running);
        } catch (JSONException ignored) {
        }
        return json.toString();
    }

    /** Old method name retained for packaged dashboard compatibility. */
    @JavascriptInterface
    public void runSelectedFile(String configJson) {
        if (running) {
            emitError("Backtest masih berjalan.");
            return;
        }
        List<SelectedFile> files = selectionSnapshot();
        if (files.isEmpty()) {
            emitError("Pilih satu atau beberapa ZIP/CSV terlebih dahulu.");
            return;
        }

        running = true;
        cancelled.set(false);
        new Thread(() -> runWorker(files, configJson), "sweep-backtest").start();
    }

    @JavascriptInterface
    public void cancel() {
        cancelled.set(true);
    }

    private void runWorker(List<SelectedFile> files, String configJson) {
        List<File> temporary = new ArrayList<>();
        try {
            Config config = Config.fromJson(configJson);
            Engine engine = new Engine(config);
            ArchiveContext context = new ArchiveContext(engine, config.timeframe);

            for (int i = 0; i < files.size(); i++) {
                ensureNotCancelled();
                SelectedFile selected = files.get(i);
                emitProgress(
                        "Menyiapkan " + (i + 1) + "/" + files.size() + ": " + selected.name,
                        engine.candles,
                        context.processedFiles,
                        selected.name
                );

                String lower = selected.name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".zip")) {
                    File copied = copyToCache(selected.uri, i + 1);
                    temporary.add(copied);
                    processZip(copied, context, 0);
                } else if (isCandleFileName(selected.name, config.timeframe)) {
                    try (InputStream input = new BufferedInputStream(
                            activity.getContentResolver().openInputStream(selected.uri), 128 * 1024
                    )) {
                        if (input == null) throw new IOException("File tidak dapat dibuka: " + selected.name);
                        processCsv(input, selected.name, context);
                    }
                } else {
                    context.ignoredFiles++;
                }
            }

            ensureNotCancelled();
            if (context.processedFiles == 0) {
                throw new IOException("Tidak ditemukan CSV timeframe " + config.timeframe + " di arsip terpilih.");
            }
            if (context.invalidRows > 0 || context.duplicates > 0 || context.outOfOrder > 0) {
                throw new IOException(
                        "Validasi data gagal: " + context.invalidRows + " baris OHLC invalid, "
                                + context.duplicates + " timestamp duplikat, "
                                + context.outOfOrder + " timestamp mundur. Hasil dibatalkan."
                );
            }

            engine.finish();
            JSONObject result = engine.toJson();
            JSONObject run = new JSONObject();
            run.put("data_source", selectionSummary(files));
            run.put("selected_archives", files.size());
            run.put("files", context.processedFiles);
            run.put("ignored_files", context.ignoredFiles);
            run.put("candles", engine.candles);
            run.put("timeframe", config.timeframe);
            run.put("first_timestamp", engine.firstTimestamp);
            run.put("last_timestamp", engine.lastTimestamp);
            run.put("skipped_rows", 0);
            run.put("invalid_rows", 0);
            run.put("duplicate_rows", 0);
            run.put("out_of_order_rows", 0);
            run.put("validation_ok", true);
            run.put("execution", "native_streaming_next_open");
            run.put("same_bar_policy", "sl_first");
            result.put("run", run);
            emit("onComplete", result);
        } catch (InterruptedIOException error) {
            emitError("Backtest dibatalkan.");
        } catch (Exception error) {
            emitError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        } finally {
            for (File file : temporary) {
                if (file != null && file.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
            running = false;
            cancelled.set(false);
        }
    }

    private List<SelectedFile> selectionSnapshot() {
        synchronized (selectionLock) {
            return new ArrayList<>(selectedFiles);
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = activity.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "candle-data" : last;
    }

    private File copyToCache(Uri uri, int sequence) throws IOException {
        File directory = new File(activity.getCacheDir(), "backtest-archives");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cache aplikasi tidak dapat dibuat.");
        File target = File.createTempFile("archive-" + sequence + "-", ".zip", directory);
        try (InputStream input = new BufferedInputStream(
                activity.getContentResolver().openInputStream(uri), 256 * 1024
        ); FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IOException("ZIP tidak dapat dibuka dari penyimpanan.");
            byte[] buffer = new byte[256 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                ensureNotCancelled();
                output.write(buffer, 0, count);
            }
        }
        return target;
    }

    private void processZip(File zipPath, ArchiveContext context, int depth) throws Exception {
        if (depth > MAX_ZIP_DEPTH) throw new IOException("ZIP bertingkat melebihi batas " + MAX_ZIP_DEPTH + ".");
        ensureNotCancelled();

        try (ZipFile zip = new ZipFile(zipPath)) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            entries.removeIf(entry -> entry.isDirectory() || ignoredEntry(entry.getName()));
            entries.sort((a, b) -> naturalCompare(a.getName(), b.getName()));

            for (ZipEntry entry : entries) {
                ensureNotCancelled();
                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);

                if (lower.endsWith(".zip")) {
                    File nested = File.createTempFile("nested-", ".zip", zipPath.getParentFile());
                    try (InputStream input = new BufferedInputStream(zip.getInputStream(entry), 128 * 1024);
                         FileOutputStream output = new FileOutputStream(nested)) {
                        byte[] buffer = new byte[128 * 1024];
                        int count;
                        while ((count = input.read(buffer)) >= 0) {
                            ensureNotCancelled();
                            output.write(buffer, 0, count);
                        }
                    }
                    try {
                        processZip(nested, context, depth + 1);
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        nested.delete();
                    }
                } else if (isCandleFileName(name, context.timeframe)) {
                    try (InputStream input = new BufferedInputStream(zip.getInputStream(entry), 128 * 1024)) {
                        processCsv(input, name, context);
                    }
                } else {
                    context.ignoredFiles++;
                }
            }
        }
    }

    private void processCsv(InputStream input, String fileName, ArchiveContext context) throws Exception {
        emitProgress("Membaca " + fileName, context.engine.candles, context.processedFiles, fileName);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8), 128 * 1024
        )) {
            String header;
            do {
                header = reader.readLine();
            } while (header != null && header.trim().isEmpty());
            if (header == null) throw new IOException("File kosong: " + fileName);

            header = stripBom(header);
            char delimiter = delimiter(header);
            Schema schema = Schema.fromHeader(split(header, delimiter));
            context.processedFiles++;
            long accepted = 0;

            String line;
            while ((line = reader.readLine()) != null) {
                ensureNotCancelled();
                if (line.trim().isEmpty()) continue;
                Candle candle = schema.parse(split(line, delimiter));
                if (candle == null) {
                    context.invalidRows++;
                    continue;
                }
                int status = context.engine.onCandle(candle);
                if (status == Engine.DUPLICATE) context.duplicates++;
                else if (status == Engine.OUT_OF_ORDER) context.outOfOrder++;
                else accepted++;

                if (context.engine.candles > 0 && context.engine.candles % PROGRESS_EVERY == 0) {
                    emitProgress("Memproses " + fileName, context.engine.candles, context.processedFiles, fileName);
                }
            }
            if (accepted == 0) throw new IOException("Tidak ada candle valid di " + fileName + ".");
        }
    }

    private static boolean isCandleFileName(String name, String timeframe) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".csv") || lower.endsWith(".txt"))) return false;
        if (lower.contains("audit") || lower.contains("manifest") || lower.contains("report")
                || lower.contains("summary") || lower.contains("readme")) return false;

        String normalized = "_" + name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "_";
        boolean hasTimeframe = false;
        for (String candidate : TIMEFRAMES) {
            if (normalized.contains("_" + candidate + "_")) {
                hasTimeframe = true;
                if (candidate.equals(timeframe)) return true;
            }
        }
        return !hasTimeframe;
    }

    private static boolean ignoredEntry(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("__macosx/") || lower.endsWith(".ds_store") || lower.endsWith("thumbs.db");
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static char delimiter(String header) {
        int comma = count(header, ',');
        int semicolon = count(header, ';');
        int tab = count(header, '\t');
        if (tab > 0 && tab >= comma && tab >= semicolon) return '\t';
        return semicolon > comma ? ';' : ',';
    }

    private static int count(String value, char needle) {
        int result = 0;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == needle) result++;
        return result;
    }

    private static List<String> split(String line, char delimiter) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (ch == delimiter && !quoted) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else current.append(ch);
        }
        values.add(current.toString().trim());
        return values;
    }

    private static int naturalCompare(String left, String right) {
        int i = 0, j = 0;
        while (i < left.length() && j < right.length()) {
            char a = Character.toLowerCase(left.charAt(i));
            char b = Character.toLowerCase(right.charAt(j));
            if (Character.isDigit(a) && Character.isDigit(b)) {
                int ai = i, bj = j;
                while (i < left.length() && Character.isDigit(left.charAt(i))) i++;
                while (j < right.length() && Character.isDigit(right.charAt(j))) j++;
                String an = left.substring(ai, i).replaceFirst("^0+(?!$)", "");
                String bn = right.substring(bj, j).replaceFirst("^0+(?!$)", "");
                if (an.length() != bn.length()) return Integer.compare(an.length(), bn.length());
                int compared = an.compareTo(bn);
                if (compared != 0) return compared;
            } else {
                if (a != b) return Character.compare(a, b);
                i++;
                j++;
            }
        }
        return Integer.compare(left.length(), right.length());
    }

    private static String selectionSummary(List<SelectedFile> files) {
        if (files.isEmpty()) return "";
        if (files.size() == 1) return files.get(0).name;
        return files.get(0).name + " + " + (files.size() - 1) + " file lain";
    }

    private void ensureNotCancelled() throws InterruptedIOException {
        if (cancelled.get()) throw new InterruptedIOException("cancelled");
    }

    private void emitProgress(String message, long candles, int files, String currentFile) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("message", message);
            payload.put("candles", candles);
            payload.put("files", files);
            payload.put("current_file", currentFile);
        } catch (JSONException ignored) {
        }
        emit("onProgress", payload);
    }

    private void emitError(String message) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("message", message == null ? "Kesalahan tidak diketahui." : message);
        } catch (JSONException ignored) {
        }
        emit("onError", payload);
    }

    private void emit(String callback, JSONObject payload) {
        String script = "window.NativeBacktest&&window.NativeBacktest." + callback + "(" + payload + ");";
        activity.runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    private static final class SelectedFile {
        final Uri uri;
        final String name;
        SelectedFile(Uri uri, String name) { this.uri = uri; this.name = name; }
    }

    private static final class ArchiveContext {
        final Engine engine;
        final String timeframe;
        int processedFiles;
        int ignoredFiles;
        long invalidRows;
        long duplicates;
        long outOfOrder;
        ArchiveContext(Engine engine, String timeframe) { this.engine = engine; this.timeframe = timeframe; }
    }

    private static final class Config {
        final String timeframe, mode;
        final int lookback, atrPeriod, acceptanceCloses, maxHold;
        final double penetration, wickRatio, bodyRatio, closeLocation, slAtr, stopBuffer, rr, costR, initialCapital, riskFraction;

        Config(JSONObject json) throws JSONException {
            timeframe = json.optString("timeframe", "M5").toUpperCase(Locale.ROOT);
            boolean validTimeframe = false;
            for (String candidate : TIMEFRAMES) if (candidate.equals(timeframe)) validTimeframe = true;
            if (!validTimeframe) throw new JSONException("Timeframe tidak valid.");

            mode = json.optString("mode", "both");
            if (!(mode.equals("both") || mode.equals("sweep_only") || mode.equals("acceptance_only"))) {
                throw new JSONException("Mode tidak valid.");
            }
            lookback = clamp(json.optInt("lookback", 20), 3, 10_000);
            atrPeriod = clamp(json.optInt("atr_period", 14), 2, 10_000);
            acceptanceCloses = clamp(json.optInt("acceptance_closes", 2), 1, 5);
            maxHold = clamp(json.optInt("max_hold_bars", 24), 1, 100_000);
            penetration = Math.max(0, json.optDouble("min_penetration_atr", 0.05));
            wickRatio = clamp01(json.optDouble("min_wick_ratio", 0.35));
            bodyRatio = clamp01(json.optDouble("min_body_ratio", 0.55));
            closeLocation = Math.max(0.5, Math.min(1, json.optDouble("close_location", 0.70)));
            slAtr = positive(json.optDouble("sl_atr", 1.0), "SL ATR");
            stopBuffer = Math.max(0, json.optDouble("stop_buffer_atr", 0.10));
            rr = positive(json.optDouble("rr_ratio", 2.0), "RR");
            costR = Math.max(0, json.optDouble("cost_per_trade_r", 0.03));
            initialCapital = positive(json.optDouble("initial_capital", 10_000), "Modal awal");
            riskFraction = positive(json.optDouble("risk_per_trade_pct", 1.0), "Risiko") / 100.0;
        }

        static Config fromJson(String text) throws JSONException { return new Config(new JSONObject(text == null ? "{}" : text)); }
        static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
        static double clamp01(double value) { return Math.max(0, Math.min(1, value)); }
        static double positive(double value, String name) throws JSONException {
            if (!Double.isFinite(value) || value <= 0) throw new JSONException(name + " harus lebih besar dari nol.");
            return value;
        }
    }

    private static final class Schema {
        final int timestamp, date, time, open, high, low, close;
        Schema(int timestamp, int date, int time, int open, int high, int low, int close) {
            this.timestamp = timestamp; this.date = date; this.time = time;
            this.open = open; this.high = high; this.low = low; this.close = close;
        }

        static Schema fromHeader(List<String> header) throws IOException {
            Map<String, Integer> map = new HashMap<>();
            for (int i = 0; i < header.size(); i++) map.put(normalize(header.get(i)), i);
            int open = find(map, "open", "bidopen", "askopen", "o");
            int high = find(map, "high", "bidhigh", "askhigh", "h");
            int low = find(map, "low", "bidlow", "asklow", "l");
            int close = find(map, "close", "bidclose", "askclose", "c");
            if (open < 0 || high < 0 || low < 0 || close < 0) throw new IOException("Header OHLC tidak lengkap.");
            return new Schema(
                    find(map, "timestamp", "datetime", "dateandtime", "gmttime", "datetimeutc"),
                    find(map, "date", "tradingdate"), find(map, "time", "timeofday"),
                    open, high, low, close
            );
        }

        Candle parse(List<String> values) {
            try {
                double o = number(value(values, open));
                double h = number(value(values, high));
                double l = number(value(values, low));
                double c = number(value(values, close));
                if (!(Double.isFinite(o) && Double.isFinite(h) && Double.isFinite(l) && Double.isFinite(c))) return null;
                if (h < Math.max(o, c) || l > Math.min(o, c) || h < l) return null;
                String ts;
                if (timestamp >= 0) ts = value(values, timestamp);
                else if (date >= 0 && time >= 0) ts = value(values, date) + " " + value(values, time);
                else return null;
                if (ts.trim().isEmpty()) return null;
                return new Candle(ts.trim(), o, h, l, c);
            } catch (Exception error) {
                return null;
            }
        }

        static int find(Map<String, Integer> map, String... names) {
            for (String name : names) if (map.containsKey(name)) return map.get(name);
            return -1;
        }
        static String normalize(String value) {
            return stripBom(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        }
        static String value(List<String> values, int index) { return index >= 0 && index < values.size() ? values.get(index).trim() : ""; }
        static double number(String value) {
            String cleaned = value.replace("\u00A0", "").replace(" ", "");
            if (cleaned.contains(",") && !cleaned.contains(".")) cleaned = cleaned.replace(',', '.');
            return Double.parseDouble(cleaned);
        }
    }

    private static final class Candle {
        final String timestamp;
        final double open, high, low, close;
        Candle(String timestamp, double open, double high, double low, double close) {
            this.timestamp = timestamp; this.open = open; this.high = high; this.low = low; this.close = close;
        }
    }

    private static final class Row {
        final long index;
        final Candle candle;
        final double atr, priorHigh, priorLow, previousClose;
        Row(long index, Candle candle, double atr, double priorHigh, double priorLow, double previousClose) {
            this.index = index; this.candle = candle; this.atr = atr;
            this.priorHigh = priorHigh; this.priorLow = priorLow; this.previousClose = previousClose;
        }
    }

    private static final class Signal {
        final int bias;
        final String label, time;
        final double level, atr, low, high;
        Signal(int bias, String label, Row row, double level) {
            this.bias = bias; this.label = label; this.time = row.candle.timestamp;
            this.level = level; this.atr = row.atr; this.low = row.candle.low; this.high = row.candle.high;
        }
    }

    private static final class Trade {
        final int direction;
        final String setup, signalTime, entryTime;
        final long entryIndex;
        final double entry, sl, tp;
        Trade(int direction, String setup, String signalTime, String entryTime, long entryIndex, double entry, double sl, double tp) {
            this.direction = direction; this.setup = setup; this.signalTime = signalTime; this.entryTime = entryTime;
            this.entryIndex = entryIndex; this.entry = entry; this.sl = sl; this.tp = tp;
        }
    }

    private static final class PricePoint {
        final long index;
        final double price;
        PricePoint(long index, double price) { this.index = index; this.price = price; }
    }

    private static final class Stats {
        long trades, wins;
        double totalR, gains, losses;
        void add(double r) {
            trades++; totalR += r;
            if (r > 0) { wins++; gains += r; }
            else if (r < 0) losses += -r;
        }
        JSONObject json(String name) throws JSONException {
            JSONObject result = new JSONObject();
            result.put("name", name); result.put("trades", trades);
            result.put("winrate", trades == 0 ? 0 : round(100.0 * wins / trades, 2));
            result.put("total_r", round(totalR, 4));
            result.put("avg_r", trades == 0 ? 0 : round(totalR / trades, 5));
            result.put("profit_factor", losses > 0 ? round(gains / losses, 4) : (gains > 0 ? 999 : 0));
            return result;
        }
    }

    private static final class Engine {
        static final int ACCEPTED = 1, DUPLICATE = 0, OUT_OF_ORDER = -1;
        final Config config;
        long candles, index = -1, tradeCount, wins;
        String firstTimestamp = "", lastTimestamp = "";
        double previousClose = Double.NaN, lastClose = Double.NaN;
        final Deque<PricePoint> highs = new ArrayDeque<>(), lows = new ArrayDeque<>();
        final Deque<Double> trueRanges = new ArrayDeque<>();
        final Deque<Row> recent = new ArrayDeque<>();
        double trueRangeSum;
        Signal pending;
        Trade openTrade;
        double capital, peakCapital, maxDrawdownPct, cumulativeR, peakR, maxDrawdownR, gains, losses;
        final Map<String, Stats> setupStats = new LinkedHashMap<>(), directionStats = new LinkedHashMap<>();
        final Deque<JSONObject> recentTrades = new ArrayDeque<>();
        final List<JSONObject> equity = new ArrayList<>();

        Engine(Config config) {
            this.config = config; capital = config.initialCapital; peakCapital = capital; addEquity("", 0);
        }

        int onCandle(Candle candle) throws JSONException {
            if (!lastTimestamp.isEmpty()) {
                int compare = candle.timestamp.compareTo(lastTimestamp);
                if (compare == 0) return DUPLICATE;
                if (compare < 0) return OUT_OF_ORDER;
            }
            index++; candles++;
            if (firstTimestamp.isEmpty()) firstTimestamp = candle.timestamp;
            lastTimestamp = candle.timestamp; lastClose = candle.close;

            if (openTrade == null && pending != null) { openPending(candle); pending = null; }
            if (openTrade != null) evaluateTrade(candle);

            long minIndex = index - config.lookback;
            while (!highs.isEmpty() && highs.peekFirst().index < minIndex) highs.removeFirst();
            while (!lows.isEmpty() && lows.peekFirst().index < minIndex) lows.removeFirst();
            double priorHigh = highs.isEmpty() ? Double.NaN : highs.peekFirst().price;
            double priorLow = lows.isEmpty() ? Double.NaN : lows.peekFirst().price;

            double tr = candle.high - candle.low;
            if (Double.isFinite(previousClose)) tr = Math.max(tr, Math.max(Math.abs(candle.high - previousClose), Math.abs(candle.low - previousClose)));
            trueRanges.addLast(tr); trueRangeSum += tr;
            while (trueRanges.size() > config.atrPeriod) trueRangeSum -= trueRanges.removeFirst();
            double atr = trueRanges.size() == config.atrPeriod ? trueRangeSum / config.atrPeriod : Double.NaN;

            Row row = new Row(index, candle, atr, priorHigh, priorLow, previousClose);
            recent.addLast(row); while (recent.size() > config.acceptanceCloses) recent.removeFirst();
            if (openTrade == null && pending == null) pending = signal(row);

            while (!highs.isEmpty() && highs.peekLast().price <= candle.high) highs.removeLast();
            highs.addLast(new PricePoint(index, candle.high));
            while (!lows.isEmpty() && lows.peekLast().price >= candle.low) lows.removeLast();
            lows.addLast(new PricePoint(index, candle.low));
            previousClose = candle.close;
            return ACCEPTED;
        }

        Signal signal(Row row) {
            if (!(Double.isFinite(row.atr) && Double.isFinite(row.priorHigh) && Double.isFinite(row.priorLow)) || row.atr <= 0) return null;
            if (config.mode.equals("both") || config.mode.equals("sweep_only")) {
                Signal sweep = sweep(row); if (sweep != null) return sweep;
            }
            return config.mode.equals("both") || config.mode.equals("acceptance_only") ? acceptance() : null;
        }

        Signal sweep(Row row) {
            Candle c = row.candle; double range = c.high - c.low; if (range <= 0) return null;
            double lowerWick = (Math.min(c.open, c.close) - c.low) / range;
            double upperWick = (c.high - Math.max(c.open, c.close)) / range;
            double penetration = config.penetration * row.atr;
            boolean buy = c.low < row.priorLow - penetration && c.close > row.priorLow && lowerWick >= config.wickRatio;
            boolean sell = c.high > row.priorHigh + penetration && c.close < row.priorHigh && upperWick >= config.wickRatio;
            if (buy && !sell) return new Signal(1, "SWEEP_LOW_RECLAIM", row, row.priorLow);
            if (sell && !buy) return new Signal(-1, "SWEEP_HIGH_RECLAIM", row, row.priorHigh);
            return null;
        }

        Signal acceptance() {
            if (recent.size() < config.acceptanceCloses) return null;
            Row first = recent.peekFirst(), last = recent.peekLast();
            if (first == null || last == null || !Double.isFinite(first.previousClose)
                    || !Double.isFinite(first.atr) || !Double.isFinite(first.priorHigh) || !Double.isFinite(first.priorLow)) return null;
            double buffer = config.penetration * first.atr;
            boolean above = true, below = true;
            for (Row row : recent) {
                above &= row.candle.close > first.priorHigh + buffer;
                below &= row.candle.close < first.priorLow - buffer;
            }
            Candle c = last.candle; double range = c.high - c.low; if (range <= 0) return null;
            double body = Math.abs(c.close - c.open) / range, location = (c.close - c.low) / range;
            boolean buy = first.previousClose <= first.priorHigh + buffer && above && body >= config.bodyRatio && location >= config.closeLocation;
            boolean sell = first.previousClose >= first.priorLow - buffer && below && body >= config.bodyRatio && location <= 1 - config.closeLocation;
            if (buy && !sell) return new Signal(1, "ACCEPTANCE_ABOVE", last, first.priorHigh);
            if (sell && !buy) return new Signal(-1, "ACCEPTANCE_BELOW", last, first.priorLow);
            return null;
        }

        void openPending(Candle candle) {
            double entry = candle.open, buffer = config.stopBuffer * pending.atr, sl, tp;
            if (pending.bias == 1) {
                double structural = pending.label.startsWith("SWEEP") ? pending.low - buffer : pending.level - buffer;
                sl = Math.min(structural, entry - config.slAtr * pending.atr); tp = entry + config.rr * (entry - sl);
                if (!(sl < entry && entry < tp)) return;
            } else {
                double structural = pending.label.startsWith("SWEEP") ? pending.high + buffer : pending.level + buffer;
                sl = Math.max(structural, entry + config.slAtr * pending.atr); tp = entry - config.rr * (sl - entry);
                if (!(tp < entry && entry < sl)) return;
            }
            openTrade = new Trade(pending.bias, pending.label, pending.time, candle.timestamp, index, entry, sl, tp);
        }

        void evaluateTrade(Candle candle) throws JSONException {
            boolean slHit = openTrade.direction == 1 ? candle.low <= openTrade.sl : candle.high >= openTrade.sl;
            boolean tpHit = openTrade.direction == 1 ? candle.high >= openTrade.tp : candle.low <= openTrade.tp;
            if (slHit) close(openTrade.sl, "SL", candle.timestamp);
            else if (tpHit) close(openTrade.tp, "TP", candle.timestamp);
            else if (index - openTrade.entryIndex + 1 >= config.maxHold) close(candle.close, "TIME", candle.timestamp);
        }

        void close(double exit, String reason, String exitTime) throws JSONException {
            Trade trade = openTrade; if (trade == null) return;
            double risk = trade.direction == 1 ? trade.entry - trade.sl : trade.sl - trade.entry;
            double grossR = trade.direction == 1 ? (exit - trade.entry) / risk : (trade.entry - exit) / risk;
            double netR = grossR - config.costR, pnl = capital * config.riskFraction * netR;
            capital += pnl; tradeCount++; cumulativeR += netR;
            if (netR > 0) { wins++; gains += netR; } else if (netR < 0) losses += -netR;
            peakCapital = Math.max(peakCapital, capital);
            maxDrawdownPct = Math.max(maxDrawdownPct, (peakCapital - capital) / peakCapital * 100);
            peakR = Math.max(peakR, cumulativeR); maxDrawdownR = Math.max(maxDrawdownR, peakR - cumulativeR);
            String direction = trade.direction == 1 ? "Long" : "Short";
            setupStats.computeIfAbsent(trade.setup, ignored -> new Stats()).add(netR);
            directionStats.computeIfAbsent(direction, ignored -> new Stats()).add(netR);

            JSONObject item = new JSONObject();
            item.put("signal_time", trade.signalTime); item.put("entry_time", trade.entryTime); item.put("exit_time", exitTime);
            item.put("direction", direction); item.put("setup", trade.setup); item.put("entry_price", round(trade.entry, 8));
            item.put("exit_price", round(exit, 8)); item.put("sl", round(trade.sl, 8)); item.put("tp", round(trade.tp, 8));
            item.put("bars_held", index - trade.entryIndex + 1); item.put("gross_r", round(grossR, 5));
            item.put("cost_r", round(config.costR, 5)); item.put("r_multiple", round(netR, 5)); item.put("reason", reason);
            recentTrades.addLast(item); while (recentTrades.size() > 100) recentTrades.removeFirst();
            addEquity(exitTime, tradeCount); openTrade = null;
        }

        void addEquity(String timestamp, long tradeNumber) {
            try {
                JSONObject point = new JSONObject(); point.put("trade", tradeNumber); point.put("timestamp", timestamp);
                point.put("equity", round(capital, 2)); point.put("cumulative_r", round(cumulativeR, 4)); equity.add(point);
                if (equity.size() > 2400) {
                    List<JSONObject> compact = new ArrayList<>(); compact.add(equity.get(0));
                    for (int i = 2; i < equity.size() - 1; i += 2) compact.add(equity.get(i));
                    compact.add(equity.get(equity.size() - 1)); equity.clear(); equity.addAll(compact);
                }
            } catch (JSONException ignored) {
            }
        }

        void finish() throws JSONException {
            if (openTrade != null && Double.isFinite(lastClose)) close(lastClose, "DATA_END", lastTimestamp);
            pending = null;
        }

        JSONObject toJson() throws JSONException {
            JSONObject root = new JSONObject(), metrics = new JSONObject();
            metrics.put("total_trades", tradeCount); metrics.put("winrate", tradeCount == 0 ? 0 : round(100.0 * wins / tradeCount, 2));
            metrics.put("profit_factor", losses > 0 ? round(gains / losses, 4) : (gains > 0 ? 999 : 0));
            metrics.put("expectancy_r", tradeCount == 0 ? 0 : round(cumulativeR / tradeCount, 5));
            metrics.put("avg_r_multiple", tradeCount == 0 ? 0 : round(cumulativeR / tradeCount, 5));
            metrics.put("total_r", round(cumulativeR, 4)); metrics.put("total_pnl", round(capital - config.initialCapital, 2));
            metrics.put("ending_equity", round(capital, 2)); metrics.put("max_drawdown", round(maxDrawdownPct, 2));
            metrics.put("max_drawdown_r", round(maxDrawdownR, 4)); root.put("metrics", metrics);
            JSONArray setups = new JSONArray(), directions = new JSONArray(), trades = new JSONArray(), points = new JSONArray();
            for (Map.Entry<String, Stats> entry : setupStats.entrySet()) setups.put(entry.getValue().json(entry.getKey()));
            for (Map.Entry<String, Stats> entry : directionStats.entrySet()) directions.put(entry.getValue().json(entry.getKey()));
            for (JSONObject trade : recentTrades) trades.put(trade); for (JSONObject point : equity) points.put(point);
            root.put("setup_breakdown", setups); root.put("direction_breakdown", directions); root.put("trades", trades);
            root.put("equity_points", points); root.put("chart_base64", ""); return root;
        }
    }

    private static double round(double value, int decimals) {
        if (!Double.isFinite(value)) return 0;
        double scale = Math.pow(10, decimals); return Math.round(value * scale) / scale;
    }
}
