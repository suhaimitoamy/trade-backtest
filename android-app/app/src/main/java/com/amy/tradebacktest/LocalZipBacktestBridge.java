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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Native streaming backtest bridge.
 *
 * ZIP/CSV data is read locally through Android's Storage Access Framework. The
 * implementation never loads the full candle archive into memory, so multi-million
 * candle archives can be processed while preserving indicator and trade state across
 * monthly file boundaries.
 */
public final class LocalZipBacktestBridge {
    private static final int MAX_ZIP_DEPTH = 3;
    private static final long PROGRESS_EVERY_CANDLES = 100_000L;

    private final Activity activity;
    private final WebView webView;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private volatile Uri selectedUri;
    private volatile String selectedName = "";
    private volatile boolean running = false;

    public LocalZipBacktestBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    public void setSelectedFile(Uri uri) {
        selectedUri = uri;
        selectedName = queryDisplayName(uri);
        JSONObject payload = new JSONObject();
        try {
            payload.put("name", selectedName);
            payload.put("uri", String.valueOf(uri));
        } catch (JSONException ignored) {
        }
        emit("onFileSelected", payload);
    }

    private String queryDisplayName(Uri uri) {
        if (uri == null) {
            return "";
        }
        ContentResolver resolver = activity.getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "candle-data" : last;
    }

    @JavascriptInterface
    public String capabilities() {
        JSONObject result = new JSONObject();
        try {
            result.put("native_streaming", true);
            result.put("zip", true);
            result.put("nested_zip", true);
            result.put("csv", true);
            result.put("hard_candle_limit", JSONObject.NULL);
            result.put("selected_name", selectedName);
            result.put("running", running);
        } catch (JSONException ignored) {
        }
        return result.toString();
    }

    @JavascriptInterface
    public void runSelectedFile(String configJson) {
        if (running) {
            emitError("Backtest masih berjalan.");
            return;
        }
        Uri uri = selectedUri;
        if (uri == null) {
            emitError("Pilih file CSV atau ZIP terlebih dahulu.");
            return;
        }

        running = true;
        cancelled.set(false);
        Thread worker = new Thread(() -> {
            File copiedFile = null;
            try {
                BacktestConfig config = BacktestConfig.fromJson(configJson);
                emitProgress("Menyiapkan file dari penyimpanan/Google Drive…", 0L, 0, selectedName);

                String lower = selectedName.toLowerCase(Locale.ROOT);
                StreamingBacktestEngine engine = new StreamingBacktestEngine(config);
                ArchiveContext context = new ArchiveContext(engine);

                if (lower.endsWith(".zip")) {
                    copiedFile = copyUriToCache(uri, "selected-archive.zip");
                    processZip(copiedFile, context, 0);
                } else {
                    try (InputStream input = new BufferedInputStream(
                            activity.getContentResolver().openInputStream(uri), 128 * 1024
                    )) {
                        if (input == null) {
                            throw new IOException("File tidak dapat dibuka.");
                        }
                        processCsv(input, selectedName, context);
                    }
                }

                ensureNotCancelled();
                engine.finish();
                JSONObject result = engine.toJson();
                JSONObject run = new JSONObject();
                run.put("data_source", selectedName);
                run.put("candles", engine.candleCount);
                run.put("files", context.processedFiles);
                run.put("skipped_rows", context.skippedRows);
                run.put("first_timestamp", engine.firstTimestamp);
                run.put("last_timestamp", engine.lastTimestamp);
                run.put("execution", "native_streaming_next_open");
                run.put("same_bar_policy", "sl_first");
                run.put("local_native", true);
                run.put("archive_order", "natural_filename_order");
                result.put("run", run);
                emit("onComplete", result);
            } catch (InterruptedIOException cancelledError) {
                emitError("Backtest dibatalkan.");
            } catch (Exception error) {
                emitError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            } finally {
                if (copiedFile != null && copiedFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    copiedFile.delete();
                }
                running = false;
                cancelled.set(false);
            }
        }, "sweep-acceptance-backtest");
        worker.start();
    }

    @JavascriptInterface
    public void cancel() {
        cancelled.set(true);
    }

    private File copyUriToCache(Uri uri, String fallbackName) throws IOException {
        File cacheDir = new File(activity.getCacheDir(), "backtest-archives");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Tidak dapat membuat cache aplikasi.");
        }
        File target = File.createTempFile("archive-", "-" + fallbackName, cacheDir);
        try (
                InputStream input = new BufferedInputStream(
                        activity.getContentResolver().openInputStream(uri), 256 * 1024
                );
                FileOutputStream output = new FileOutputStream(target)
        ) {
            if (input == null) {
                throw new IOException("File tidak dapat dibuka dari penyimpanan.");
            }
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                ensureNotCancelled();
                output.write(buffer, 0, read);
            }
        }
        return target;
    }

    private void processZip(File zipPath, ArchiveContext context, int depth) throws Exception {
        ensureNotCancelled();
        if (depth > MAX_ZIP_DEPTH) {
            throw new IOException("ZIP bertingkat melebihi batas kedalaman " + MAX_ZIP_DEPTH + ".");
        }

        try (ZipFile zip = new ZipFile(zipPath)) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            entries.removeIf(entry -> entry.isDirectory() || isIgnoredEntry(entry.getName()));
            entries.sort((left, right) -> naturalCompare(left.getName(), right.getName()));

            boolean foundData = false;
            for (ZipEntry entry : entries) {
                ensureNotCancelled();
                String lower = entry.getName().toLowerCase(Locale.ROOT);
                if (isCsvName(lower)) {
                    foundData = true;
                    try (InputStream input = new BufferedInputStream(zip.getInputStream(entry), 128 * 1024)) {
                        processCsv(input, entry.getName(), context);
                    }
                } else if (lower.endsWith(".zip")) {
                    foundData = true;
                    File nested = File.createTempFile("nested-", ".zip", zipPath.getParentFile());
                    try (
                            InputStream input = new BufferedInputStream(zip.getInputStream(entry), 128 * 1024);
                            FileOutputStream output = new FileOutputStream(nested)
                    ) {
                        byte[] buffer = new byte[128 * 1024];
                        int read;
                        while ((read = input.read(buffer)) >= 0) {
                            ensureNotCancelled();
                            output.write(buffer, 0, read);
                        }
                    }
                    try {
                        processZip(nested, context, depth + 1);
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        nested.delete();
                    }
                }
            }
            if (!foundData && depth == 0) {
                throw new IOException("ZIP tidak berisi CSV/TXT candle atau ZIP bulanan.");
            }
        }
    }

    private static boolean isIgnoredEntry(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("__macosx/")
                || lower.endsWith(".ds_store")
                || lower.endsWith("thumbs.db");
    }

    private static boolean isCsvName(String lowerName) {
        return lowerName.endsWith(".csv") || lowerName.endsWith(".txt");
    }

    private void processCsv(InputStream input, String fileName, ArchiveContext context) throws Exception {
        ensureNotCancelled();
        context.processedFiles += 1;
        emitProgress(
                "Membaca " + fileName,
                context.engine.candleCount,
                context.processedFiles,
                fileName
        );

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8), 128 * 1024
        )) {
            String headerLine;
            do {
                headerLine = reader.readLine();
            } while (headerLine != null && headerLine.trim().isEmpty());

            if (headerLine == null) {
                return;
            }
            headerLine = stripBom(headerLine);
            char delimiter = detectDelimiter(headerLine);
            CsvSchema schema = CsvSchema.fromHeader(splitLine(headerLine, delimiter));

            String line;
            long localRows = 0L;
            while ((line = reader.readLine()) != null) {
                ensureNotCancelled();
                if (line.trim().isEmpty()) {
                    continue;
                }
                List<String> values = splitLine(line, delimiter);
                Candle candle = schema.parse(values);
                if (candle == null) {
                    context.skippedRows += 1;
                    continue;
                }
                context.engine.onCandle(candle);
                localRows += 1;
                if (context.engine.candleCount % PROGRESS_EVERY_CANDLES == 0) {
                    emitProgress(
                            "Memproses " + fileName,
                            context.engine.candleCount,
                            context.processedFiles,
                            fileName
                    );
                }
            }
            if (localRows == 0L) {
                throw new IOException("Tidak ada candle valid dalam " + fileName + ".");
            }
        }
    }

    private static String stripBom(String value) {
        return value != null && value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static char detectDelimiter(String header) {
        int commas = countChar(header, ',');
        int semicolons = countChar(header, ';');
        int tabs = countChar(header, '\t');
        if (tabs >= commas && tabs >= semicolons && tabs > 0) {
            return '\t';
        }
        if (semicolons > commas) {
            return ';';
        }
        return ',';
    }

    private static int countChar(String value, char needle) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == needle) {
                count += 1;
            }
        }
        return count;
    }

    private static List<String> splitLine(String line, char delimiter) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i += 1;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == delimiter && !quoted) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString().trim());
        return values;
    }

    private static int naturalCompare(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            char a = Character.toLowerCase(left.charAt(i));
            char b = Character.toLowerCase(right.charAt(j));
            if (Character.isDigit(a) && Character.isDigit(b)) {
                int startI = i;
                int startJ = j;
                while (i < left.length() && Character.isDigit(left.charAt(i))) i++;
                while (j < right.length() && Character.isDigit(right.charAt(j))) j++;
                String numberA = left.substring(startI, i).replaceFirst("^0+(?!$)", "");
                String numberB = right.substring(startJ, j).replaceFirst("^0+(?!$)", "");
                if (numberA.length() != numberB.length()) {
                    return Integer.compare(numberA.length(), numberB.length());
                }
                int cmp = numberA.compareTo(numberB);
                if (cmp != 0) return cmp;
            } else {
                if (a != b) return Character.compare(a, b);
                i++;
                j++;
            }
        }
        return Integer.compare(left.length(), right.length());
    }

    private void ensureNotCancelled() throws InterruptedIOException {
        if (cancelled.get()) {
            throw new InterruptedIOException("cancelled");
        }
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
        String script = "window.NativeBacktest && window.NativeBacktest."
                + callback + "(" + payload.toString() + ");";
        activity.runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    private static final class ArchiveContext {
        final StreamingBacktestEngine engine;
        int processedFiles = 0;
        long skippedRows = 0L;

        ArchiveContext(StreamingBacktestEngine engine) {
            this.engine = engine;
        }
    }

    private static final class BacktestConfig {
        final String mode;
        final int lookback;
        final int atrPeriod;
        final double minPenetrationAtr;
        final double minWickRatio;
        final int acceptanceCloses;
        final double minBodyRatio;
        final double closeLocation;
        final double slAtr;
        final double stopBufferAtr;
        final double rrRatio;
        final int maxHoldBars;
        final double costR;
        final double initialCapital;
        final double riskPct;

        BacktestConfig(
                String mode,
                int lookback,
                int atrPeriod,
                double minPenetrationAtr,
                double minWickRatio,
                int acceptanceCloses,
                double minBodyRatio,
                double closeLocation,
                double slAtr,
                double stopBufferAtr,
                double rrRatio,
                int maxHoldBars,
                double costR,
                double initialCapital,
                double riskPct
        ) {
            this.mode = mode;
            this.lookback = lookback;
            this.atrPeriod = atrPeriod;
            this.minPenetrationAtr = minPenetrationAtr;
            this.minWickRatio = minWickRatio;
            this.acceptanceCloses = acceptanceCloses;
            this.minBodyRatio = minBodyRatio;
            this.closeLocation = closeLocation;
            this.slAtr = slAtr;
            this.stopBufferAtr = stopBufferAtr;
            this.rrRatio = rrRatio;
            this.maxHoldBars = maxHoldBars;
            this.costR = costR;
            this.initialCapital = initialCapital;
            this.riskPct = riskPct;
        }

        static BacktestConfig fromJson(String value) throws JSONException {
            JSONObject json = new JSONObject(value == null ? "{}" : value);
            String mode = json.optString("mode", "both");
            if (!mode.equals("both") && !mode.equals("sweep_only") && !mode.equals("acceptance_only")) {
                throw new JSONException("Mode tidak valid.");
            }
            int lookback = clamp(json.optInt("lookback", 20), 3, 10_000);
            int atrPeriod = clamp(json.optInt("atr_period", 14), 2, 10_000);
            int acceptanceCloses = clamp(json.optInt("acceptance_closes", 2), 1, 5);
            int maxHoldBars = clamp(json.optInt("max_hold_bars", 24), 1, 100_000);
            double rr = positive(json.optDouble("rr_ratio", 2.0), "RR");
            double slAtr = positive(json.optDouble("sl_atr", 1.0), "SL ATR");
            return new BacktestConfig(
                    mode,
                    lookback,
                    atrPeriod,
                    Math.max(0.0, json.optDouble("min_penetration_atr", 0.05)),
                    clamp01(json.optDouble("min_wick_ratio", 0.35)),
                    acceptanceCloses,
                    clamp01(json.optDouble("min_body_ratio", 0.55)),
                    Math.max(0.5, Math.min(1.0, json.optDouble("close_location", 0.70))),
                    slAtr,
                    Math.max(0.0, json.optDouble("stop_buffer_atr", 0.10)),
                    rr,
                    maxHoldBars,
                    Math.max(0.0, json.optDouble("cost_per_trade_r", 0.03)),
                    positive(json.optDouble("initial_capital", 10_000.0), "Modal awal"),
                    positive(json.optDouble("risk_per_trade_pct", 1.0), "Risiko") / 100.0
            );
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private static double clamp01(double value) {
            return Math.max(0.0, Math.min(1.0, value));
        }

        private static double positive(double value, String name) throws JSONException {
            if (!Double.isFinite(value) || value <= 0.0) {
                throw new JSONException(name + " harus lebih besar dari nol.");
            }
            return value;
        }
    }

    private static final class CsvSchema {
        final int timestamp;
        final int date;
        final int time;
        final int open;
        final int high;
        final int low;
        final int close;

        CsvSchema(int timestamp, int date, int time, int open, int high, int low, int close) {
            this.timestamp = timestamp;
            this.date = date;
            this.time = time;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
        }

        static CsvSchema fromHeader(List<String> header) throws IOException {
            Map<String, Integer> indexes = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                indexes.put(normalizeHeader(stripBom(header.get(i))), i);
            }
            int open = find(indexes, "open", "bidopen", "askopen", "o");
            int high = find(indexes, "high", "bidhigh", "askhigh", "h");
            int low = find(indexes, "low", "bidlow", "asklow", "l");
            int close = find(indexes, "close", "bidclose", "askclose", "c");
            if (open < 0 || high < 0 || low < 0 || close < 0) {
                throw new IOException("Header CSV wajib memiliki kolom open, high, low, close.");
            }
            int timestamp = find(indexes, "timestamp", "datetime", "dateandtime", "gmttime", "datetimeutc");
            int date = find(indexes, "date", "tradingdate");
            int time = find(indexes, "time", "timeofday");
            return new CsvSchema(timestamp, date, time, open, high, low, close);
        }

        Candle parse(List<String> values) {
            try {
                double openValue = parseNumber(value(values, open));
                double highValue = parseNumber(value(values, high));
                double lowValue = parseNumber(value(values, low));
                double closeValue = parseNumber(value(values, close));
                if (!allFinite(openValue, highValue, lowValue, closeValue)) {
                    return null;
                }
                if (highValue < Math.max(openValue, closeValue)
                        || lowValue > Math.min(openValue, closeValue)
                        || highValue < lowValue) {
                    return null;
                }
                String timestampValue;
                if (timestamp >= 0) {
                    timestampValue = value(values, timestamp);
                } else if (date >= 0 && time >= 0) {
                    timestampValue = value(values, date) + " " + value(values, time);
                } else if (date >= 0) {
                    timestampValue = value(values, date);
                } else if (time >= 0) {
                    timestampValue = value(values, time);
                } else {
                    timestampValue = "";
                }
                return new Candle(timestampValue, openValue, highValue, lowValue, closeValue);
            } catch (Exception ignored) {
                return null;
            }
        }

        private static int find(Map<String, Integer> indexes, String... aliases) {
            for (String alias : aliases) {
                Integer value = indexes.get(alias);
                if (value != null) return value;
            }
            return -1;
        }

        private static String normalizeHeader(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT)
                    .replace(" ", "")
                    .replace("_", "")
                    .replace("-", "")
                    .replace(".", "")
                    .replace("/", "");
        }

        private static String value(List<String> values, int index) {
            if (index < 0 || index >= values.size()) return "";
            return values.get(index).trim();
        }

        private static double parseNumber(String value) {
            String cleaned = value.trim().replace("\u00A0", "").replace(" ", "");
            if (cleaned.indexOf(',') >= 0 && cleaned.indexOf('.') < 0) {
                cleaned = cleaned.replace(',', '.');
            }
            return Double.parseDouble(cleaned);
        }

        private static boolean allFinite(double... values) {
            for (double value : values) {
                if (!Double.isFinite(value)) return false;
            }
            return true;
        }
    }

    private static final class Candle {
        final String timestamp;
        final double open;
        final double high;
        final double low;
        final double close;

        Candle(String timestamp, double open, double high, double low, double close) {
            this.timestamp = timestamp;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
        }
    }

    private static final class FeatureRow {
        final long index;
        final Candle candle;
        final double atr;
        final double priorHigh;
        final double priorLow;
        final double previousClose;

        FeatureRow(long index, Candle candle, double atr, double priorHigh, double priorLow, double previousClose) {
            this.index = index;
            this.candle = candle;
            this.atr = atr;
            this.priorHigh = priorHigh;
            this.priorLow = priorLow;
            this.previousClose = previousClose;
        }
    }

    private static final class Signal {
        final int bias;
        final String label;
        final String signalTime;
        final double level;
        final double atr;
        final double signalLow;
        final double signalHigh;

        Signal(int bias, String label, FeatureRow row, double level) {
            this.bias = bias;
            this.label = label;
            this.signalTime = row.candle.timestamp;
            this.level = level;
            this.atr = row.atr;
            this.signalLow = row.candle.low;
            this.signalHigh = row.candle.high;
        }
    }

    private static final class OpenTrade {
        final int direction;
        final String setup;
        final String signalTime;
        final String entryTime;
        final long entryIndex;
        final double entry;
        final double sl;
        final double tp;

        OpenTrade(
                int direction,
                String setup,
                String signalTime,
                String entryTime,
                long entryIndex,
                double entry,
                double sl,
                double tp
        ) {
            this.direction = direction;
            this.setup = setup;
            this.signalTime = signalTime;
            this.entryTime = entryTime;
            this.entryIndex = entryIndex;
            this.entry = entry;
            this.sl = sl;
            this.tp = tp;
        }
    }

    private static final class IndexedPrice {
        final long index;
        final double price;

        IndexedPrice(long index, double price) {
            this.index = index;
            this.price = price;
        }
    }

    private static final class GroupStats {
        long trades = 0L;
        long wins = 0L;
        double totalR = 0.0;
        double gains = 0.0;
        double losses = 0.0;

        void add(double r) {
            trades += 1;
            totalR += r;
            if (r > 0.0) {
                wins += 1;
                gains += r;
            } else if (r < 0.0) {
                losses += -r;
            }
        }

        JSONObject toJson(String name) throws JSONException {
            JSONObject row = new JSONObject();
            row.put("name", name);
            row.put("trades", trades);
            row.put("winrate", trades == 0 ? 0.0 : round(100.0 * wins / trades, 2));
            row.put("total_r", round(totalR, 4));
            row.put("avg_r", trades == 0 ? 0.0 : round(totalR / trades, 5));
            row.put("profit_factor", losses > 0.0 ? round(gains / losses, 4) : (gains > 0.0 ? 999.0 : 0.0));
            return row;
        }
    }

    private static final class StreamingBacktestEngine {
        final BacktestConfig config;

        long candleCount = 0L;
        long currentIndex = -1L;
        String firstTimestamp = "";
        String lastTimestamp = "";
        double lastClose = Double.NaN;
        double previousClose = Double.NaN;

        final Deque<IndexedPrice> highDeque = new ArrayDeque<>();
        final Deque<IndexedPrice> lowDeque = new ArrayDeque<>();
        final Deque<Double> trWindow = new ArrayDeque<>();
        double trSum = 0.0;
        final Deque<FeatureRow> recentRows = new ArrayDeque<>();

        Signal pendingSignal = null;
        OpenTrade openTrade = null;

        double capital;
        double peakCapital;
        double maxDrawdownPct = 0.0;
        double cumulativeR = 0.0;
        double peakR = 0.0;
        double maxDrawdownR = 0.0;
        double gains = 0.0;
        double losses = 0.0;
        long tradeCount = 0L;
        long winCount = 0L;

        final Map<String, GroupStats> setupStats = new LinkedHashMap<>();
        final Map<String, GroupStats> directionStats = new LinkedHashMap<>();
        final Deque<JSONObject> recentTrades = new ArrayDeque<>();
        final List<JSONObject> equityPoints = new ArrayList<>();

        StreamingBacktestEngine(BacktestConfig config) {
            this.config = config;
            this.capital = config.initialCapital;
            this.peakCapital = config.initialCapital;
            addEquityPoint("", 0L);
        }

        void onCandle(Candle candle) throws JSONException {
            currentIndex += 1L;
            candleCount += 1L;
            if (firstTimestamp.isEmpty()) firstTimestamp = candle.timestamp;
            lastTimestamp = candle.timestamp;
            lastClose = candle.close;

            if (openTrade == null && pendingSignal != null) {
                openPending(candle);
                pendingSignal = null;
            }

            if (openTrade != null) {
                evaluateOpenTrade(candle);
            }

            long minimumIndex = currentIndex - config.lookback;
            while (!highDeque.isEmpty() && highDeque.peekFirst().index < minimumIndex) {
                highDeque.removeFirst();
            }
            while (!lowDeque.isEmpty() && lowDeque.peekFirst().index < minimumIndex) {
                lowDeque.removeFirst();
            }
            double priorHigh = highDeque.isEmpty() ? Double.NaN : highDeque.peekFirst().price;
            double priorLow = lowDeque.isEmpty() ? Double.NaN : lowDeque.peekFirst().price;

            double trueRange;
            if (Double.isFinite(previousClose)) {
                trueRange = Math.max(
                        candle.high - candle.low,
                        Math.max(Math.abs(candle.high - previousClose), Math.abs(candle.low - previousClose))
                );
            } else {
                trueRange = candle.high - candle.low;
            }
            trWindow.addLast(trueRange);
            trSum += trueRange;
            while (trWindow.size() > config.atrPeriod) {
                trSum -= trWindow.removeFirst();
            }
            double atr = trWindow.size() == config.atrPeriod ? trSum / config.atrPeriod : Double.NaN;

            FeatureRow row = new FeatureRow(
                    currentIndex,
                    candle,
                    atr,
                    priorHigh,
                    priorLow,
                    previousClose
            );
            recentRows.addLast(row);
            while (recentRows.size() > config.acceptanceCloses) {
                recentRows.removeFirst();
            }

            if (openTrade == null && pendingSignal == null) {
                Signal signal = detectSignal(row);
                if (signal != null) {
                    pendingSignal = signal;
                }
            }

            while (!highDeque.isEmpty() && highDeque.peekLast().price <= candle.high) {
                highDeque.removeLast();
            }
            highDeque.addLast(new IndexedPrice(currentIndex, candle.high));
            while (!lowDeque.isEmpty() && lowDeque.peekLast().price >= candle.low) {
                lowDeque.removeLast();
            }
            lowDeque.addLast(new IndexedPrice(currentIndex, candle.low));
            previousClose = candle.close;
        }

        private Signal detectSignal(FeatureRow row) {
            if (!Double.isFinite(row.atr) || row.atr <= 0.0
                    || !Double.isFinite(row.priorHigh) || !Double.isFinite(row.priorLow)) {
                return null;
            }

            if (config.mode.equals("both") || config.mode.equals("sweep_only")) {
                Signal sweep = detectSweep(row);
                if (sweep != null) return sweep;
            }
            if (config.mode.equals("both") || config.mode.equals("acceptance_only")) {
                return detectAcceptance();
            }
            return null;
        }

        private Signal detectSweep(FeatureRow row) {
            Candle c = row.candle;
            double range = c.high - c.low;
            if (range <= 0.0) return null;
            double lowerWick = (Math.min(c.open, c.close) - c.low) / range;
            double upperWick = (c.high - Math.max(c.open, c.close)) / range;
            double penetration = config.minPenetrationAtr * row.atr;

            boolean bullish = c.low < row.priorLow - penetration
                    && c.close > row.priorLow
                    && lowerWick >= config.minWickRatio;
            boolean bearish = c.high > row.priorHigh + penetration
                    && c.close < row.priorHigh
                    && upperWick >= config.minWickRatio;
            if (bullish && !bearish) {
                return new Signal(1, "SWEEP_LOW_RECLAIM", row, row.priorLow);
            }
            if (bearish && !bullish) {
                return new Signal(-1, "SWEEP_HIGH_RECLAIM", row, row.priorHigh);
            }
            return null;
        }

        private Signal detectAcceptance() {
            if (recentRows.size() < config.acceptanceCloses) return null;
            FeatureRow first = recentRows.peekFirst();
            FeatureRow last = recentRows.peekLast();
            if (first == null || last == null
                    || !Double.isFinite(first.atr) || first.atr <= 0.0
                    || !Double.isFinite(first.priorHigh) || !Double.isFinite(first.priorLow)
                    || !Double.isFinite(first.previousClose)) {
                return null;
            }

            double buffer = config.minPenetrationAtr * first.atr;
            boolean allAbove = true;
            boolean allBelow = true;
            for (FeatureRow row : recentRows) {
                allAbove &= row.candle.close > first.priorHigh + buffer;
                allBelow &= row.candle.close < first.priorLow - buffer;
            }

            Candle c = last.candle;
            double range = c.high - c.low;
            if (range <= 0.0) return null;
            double body = Math.abs(c.close - c.open) / range;
            double closeLocation = (c.close - c.low) / range;

            boolean bullish = first.previousClose <= first.priorHigh + buffer
                    && allAbove
                    && body >= config.minBodyRatio
                    && closeLocation >= config.closeLocation;
            boolean bearish = first.previousClose >= first.priorLow - buffer
                    && allBelow
                    && body >= config.minBodyRatio
                    && closeLocation <= 1.0 - config.closeLocation;

            if (bullish && !bearish) {
                return new Signal(1, "ACCEPTANCE_ABOVE", last, first.priorHigh);
            }
            if (bearish && !bullish) {
                return new Signal(-1, "ACCEPTANCE_BELOW", last, first.priorLow);
            }
            return null;
        }

        private void openPending(Candle candle) {
            Signal signal = pendingSignal;
            double entry = candle.open;
            double buffer = config.stopBufferAtr * signal.atr;
            double sl;
            double tp;
            if (signal.bias == 1) {
                double structural = signal.label.startsWith("SWEEP")
                        ? signal.signalLow - buffer
                        : signal.level - buffer;
                sl = Math.min(structural, entry - config.slAtr * signal.atr);
                double risk = entry - sl;
                tp = entry + config.rrRatio * risk;
                if (!(sl < entry && entry < tp)) return;
            } else {
                double structural = signal.label.startsWith("SWEEP")
                        ? signal.signalHigh + buffer
                        : signal.level + buffer;
                sl = Math.max(structural, entry + config.slAtr * signal.atr);
                double risk = sl - entry;
                tp = entry - config.rrRatio * risk;
                if (!(tp < entry && entry < sl)) return;
            }
            openTrade = new OpenTrade(
                    signal.bias,
                    signal.label,
                    signal.signalTime,
                    candle.timestamp,
                    currentIndex,
                    entry,
                    sl,
                    tp
            );
        }

        private void evaluateOpenTrade(Candle candle) throws JSONException {
            OpenTrade trade = openTrade;
            boolean hitSl;
            boolean hitTp;
            if (trade.direction == 1) {
                hitSl = candle.low <= trade.sl;
                hitTp = candle.high >= trade.tp;
            } else {
                hitSl = candle.high >= trade.sl;
                hitTp = candle.low <= trade.tp;
            }
            if (hitSl) {
                closeTrade(trade.sl, "SL", candle.timestamp);
                return;
            }
            if (hitTp) {
                closeTrade(trade.tp, "TP", candle.timestamp);
                return;
            }
            long barsHeld = currentIndex - trade.entryIndex + 1L;
            if (barsHeld >= config.maxHoldBars) {
                closeTrade(candle.close, "TIME", candle.timestamp);
            }
        }

        private void closeTrade(double exitPrice, String reason, String exitTime) throws JSONException {
            OpenTrade trade = openTrade;
            if (trade == null) return;
            double risk = trade.direction == 1 ? trade.entry - trade.sl : trade.sl - trade.entry;
            double grossR = trade.direction == 1
                    ? (exitPrice - trade.entry) / risk
                    : (trade.entry - exitPrice) / risk;
            double netR = grossR - config.costR;
            double riskAmount = capital * config.riskPct;
            double pnl = riskAmount * netR;
            capital += pnl;
            tradeCount += 1L;
            cumulativeR += netR;
            if (netR > 0.0) {
                winCount += 1L;
                gains += netR;
            } else if (netR < 0.0) {
                losses += -netR;
            }
            peakCapital = Math.max(peakCapital, capital);
            if (peakCapital > 0.0) {
                maxDrawdownPct = Math.max(maxDrawdownPct, (peakCapital - capital) / peakCapital * 100.0);
            }
            peakR = Math.max(peakR, cumulativeR);
            maxDrawdownR = Math.max(maxDrawdownR, peakR - cumulativeR);

            setupStats.computeIfAbsent(trade.setup, ignored -> new GroupStats()).add(netR);
            String direction = trade.direction == 1 ? "Long" : "Short";
            directionStats.computeIfAbsent(direction, ignored -> new GroupStats()).add(netR);

            JSONObject item = new JSONObject();
            item.put("signal_time", trade.signalTime);
            item.put("entry_time", trade.entryTime);
            item.put("exit_time", exitTime);
            item.put("direction", direction);
            item.put("setup", trade.setup);
            item.put("entry_price", round(trade.entry, 8));
            item.put("exit_price", round(exitPrice, 8));
            item.put("sl", round(trade.sl, 8));
            item.put("tp", round(trade.tp, 8));
            item.put("bars_held", currentIndex - trade.entryIndex + 1L);
            item.put("gross_r", round(grossR, 5));
            item.put("cost_r", round(config.costR, 5));
            item.put("r_multiple", round(netR, 5));
            item.put("pnl_amount", round(pnl, 2));
            item.put("reason", reason);
            recentTrades.addLast(item);
            while (recentTrades.size() > 100) recentTrades.removeFirst();

            addEquityPoint(exitTime, tradeCount);
            openTrade = null;
        }

        private void addEquityPoint(String timestamp, long tradeNumber) {
            try {
                JSONObject point = new JSONObject();
                point.put("trade", tradeNumber);
                point.put("timestamp", timestamp);
                point.put("equity", round(capital, 2));
                point.put("cumulative_r", round(cumulativeR, 4));
                equityPoints.add(point);
                if (equityPoints.size() > 2400) {
                    List<JSONObject> compacted = new ArrayList<>();
                    compacted.add(equityPoints.get(0));
                    for (int i = 2; i < equityPoints.size() - 1; i += 2) {
                        compacted.add(equityPoints.get(i));
                    }
                    compacted.add(equityPoints.get(equityPoints.size() - 1));
                    equityPoints.clear();
                    equityPoints.addAll(compacted);
                }
            } catch (JSONException ignored) {
            }
        }

        void finish() throws JSONException {
            if (openTrade != null && Double.isFinite(lastClose)) {
                closeTrade(lastClose, "DATA_END", lastTimestamp);
            }
            pendingSignal = null;
        }

        JSONObject toJson() throws JSONException {
            JSONObject root = new JSONObject();
            JSONObject metrics = new JSONObject();
            metrics.put("total_trades", tradeCount);
            metrics.put("winrate", tradeCount == 0 ? 0.0 : round(100.0 * winCount / tradeCount, 2));
            metrics.put("profit_factor", losses > 0.0 ? round(gains / losses, 4) : (gains > 0.0 ? 999.0 : 0.0));
            metrics.put("expectancy_r", tradeCount == 0 ? 0.0 : round(cumulativeR / tradeCount, 5));
            metrics.put("avg_r_multiple", tradeCount == 0 ? 0.0 : round(cumulativeR / tradeCount, 5));
            metrics.put("total_r", round(cumulativeR, 4));
            metrics.put("total_pnl", round(capital - config.initialCapital, 2));
            metrics.put("ending_equity", round(capital, 2));
            metrics.put("max_drawdown", round(maxDrawdownPct, 2));
            metrics.put("max_drawdown_r", round(maxDrawdownR, 4));
            root.put("metrics", metrics);

            JSONArray setups = new JSONArray();
            for (Map.Entry<String, GroupStats> entry : setupStats.entrySet()) {
                setups.put(entry.getValue().toJson(entry.getKey()));
            }
            root.put("setup_breakdown", setups);

            JSONArray directions = new JSONArray();
            for (Map.Entry<String, GroupStats> entry : directionStats.entrySet()) {
                directions.put(entry.getValue().toJson(entry.getKey()));
            }
            root.put("direction_breakdown", directions);

            JSONArray trades = new JSONArray();
            for (JSONObject trade : recentTrades) trades.put(trade);
            root.put("trades", trades);

            JSONArray points = new JSONArray();
            for (JSONObject point : equityPoints) points.put(point);
            root.put("equity_points", points);
            root.put("chart_base64", "");
            return root;
        }
    }

    private static double round(double value, int decimals) {
        if (!Double.isFinite(value)) return 0.0;
        double scale = Math.pow(10.0, decimals);
        return Math.round(value * scale) / scale;
    }
}
