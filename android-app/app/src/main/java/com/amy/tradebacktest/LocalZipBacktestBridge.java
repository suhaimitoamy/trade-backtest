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

/**
 * Native local streaming engine for very large candle archives.
 *
 * The bridge accepts multiple CSV/ZIP files from Android Storage Access Framework,
 * including Google Drive. Annual ZIP -> monthly ZIP -> timeframe CSV is supported.
 * Only the timeframe selected in the UI is processed; other timeframe files and
 * audit reports are ignored rather than mixed into one candle stream.
 */
public final class LocalZipBacktestBridge {
    private static final int MAX_ZIP_DEPTH = 3;
    private static final long PROGRESS_EVERY_CANDLES = 100_000L;
    private static final String[] KNOWN_TIMEFRAMES = {"M1", "M5", "M15", "H1", "H4", "D1"};

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
        List<SelectedFile> replacement = new ArrayList<>();
        for (Uri uri : uris) {
            if (uri != null) {
                replacement.add(new SelectedFile(uri, queryDisplayName(uri)));
            }
        }
        replacement.sort((left, right) -> naturalCompare(left.name, right.name));

        synchronized (selectionLock) {
            selectedFiles.clear();
            selectedFiles.addAll(replacement);
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("count", replacement.size());
            JSONArray names = new JSONArray();
            for (SelectedFile file : replacement) {
                names.put(file.name);
            }
            payload.put("names", names);
            payload.put("name", selectionSummary(replacement));
        } catch (JSONException ignored) {
        }
        emit("onFileSelected", payload);
    }

    private List<SelectedFile> selectionSnapshot() {
        synchronized (selectionLock) {
            return new ArrayList<>(selectedFiles);
        }
    }

    private String queryDisplayName(Uri uri) {
        ContentResolver resolver = activity.getContentResolver();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) {
                        return value.trim();
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
            List<SelectedFile> snapshot = selectionSnapshot();
            result.put("native_streaming", true);
            result.put("zip", true);
            result.put("nested_zip", true);
            result.put("csv", true);
            result.put("multiple_files", true);
            result.put("timeframe_filter", true);
            result.put("hard_candle_limit", JSONObject.NULL);
            result.put("selected_count", snapshot.size());
            result.put("selected_name", selectionSummary(snapshot));
            result.put("running", running);
        } catch (JSONException ignored) {
        }
        return result.toString();
    }

    /** Kept with the old method name so existing packaged JavaScript remains compatible. */
    @JavascriptInterface
    public void runSelectedFile(String configJson) {
        if (running) {
            emitError("Backtest masih berjalan.");
            return;
        }

        List<SelectedFile> snapshot = selectionSnapshot();
        if (snapshot.isEmpty()) {
            emitError("Pilih satu atau beberapa ZIP/CSV terlebih dahulu.");
            return;
        }

        running = true;
        cancelled.set(false);
        Thread worker = new Thread(() -> {
            List<File> temporaryFiles = new ArrayList<>();
            try {
                BacktestConfig config = BacktestConfig.fromJson(configJson);
                StreamingBacktestEngine engine = new StreamingBacktestEngine(config);
                ArchiveContext context = new ArchiveContext(engine, config.timeframe);

                int archiveNumber = 0;
                for (SelectedFile selected : snapshot) {
                    ensureNotCancelled();
                    archiveNumber += 1;
                    emitProgress(
                            "Menyiapkan " + archiveNumber + "/" + snapshot.size() + ": " + selected.name,
                            engine.candleCount,
                            context.processedFiles,
                            selected.name
                    );

                    String lower = selected.name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".zip")) {
                        File copied = copyUriToCache(selected.uri, archiveNumber);
                        temporaryFiles.add(copied);
                        processZip(copied, context, 0);
                    } else if (isDirectCandleName(lower)) {
                        if (!matchesTimeframe(selected.name, config.timeframe)) {
                            context.ignoredFiles += 1;
                            continue;
                        }
                        try (InputStream input = new BufferedInputStream(
                                activity.getContentResolver().openInputStream(selected.uri), 128 * 1024
                        )) {
                            if (input == null) {
                                throw new IOException("File tidak dapat dibuka: " + selected.name);
                            }
                            processCsv(input, selected.name, context);
                        }
                    } else {
                        context.ignoredFiles += 1;
                    }
                }

                ensureNotCancelled();
                if (context.processedFiles == 0) {
                    throw new IOException(
                            "Tidak ditemukan CSV timeframe " + config.timeframe
                                    + ". Pilih timeframe yang sesuai dengan nama file di dalam ZIP."
                    );
                }
                if (context.invalidRows > 0 || context.duplicateRows > 0 || context.outOfOrderRows > 0) {
                    throw new IOException(
                            "Validasi data gagal: " + context.invalidRows + " baris invalid, "
                                    + context.duplicateRows + " timestamp duplikat, "
                                    + context.outOfOrderRows + " timestamp mundur. "
                                    + "Hasil tidak dihitung agar tidak menyesatkan."
                    );
                }

                engine.finish();
                JSONObject result = engine.toJson();
                JSONObject run = new JSONObject();
                run.put("data_source", selectionSummary(snapshot));
                run.put("selected_archives", snapshot.size());
                run.put("candles", engine.candleCount);
                run.put("files", context.processedFiles);
                run.put("ignored_files", context.ignoredFiles);
                run.put("invalid_rows", context.invalidRows);
                run.put("duplicate_rows", context.duplicateRows);
                run.put("out_of_order_rows", context.outOfOrderRows);
                run.put("skipped_rows", 0);
                run.put("timeframe", config.timeframe);
                run.put("first_timestamp", engine.firstTimestamp);
                run.put("last_timestamp", engine.lastTimestamp);
                run.put("execution", "native_streaming_next_open");
                run.put("same_bar_policy", "sl_first");
                run.put("local_native", true);
                run.put("archive_order", "natural_filename_order");
                run.put("validation_ok", true);
                result.put("run", run);
                emit("onComplete", result);
            } catch (InterruptedIOException cancelledError) {
                emitError("Backtest dibatalkan.");
            } catch (Exception error) {
                emitError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            } finally {
                for (File file : temporaryFiles) {
                    if (file != null && file.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
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

    private File copyUriToCache(Uri uri, int sequence) throws IOException {
        File cacheDir = new File(activity.getCacheDir(), "backtest-archives");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Tidak dapat membuat cache aplikasi.");
        }
        File target = File.createTempFile("archive-" + sequence + "-", ".zip", cacheDir);
        try (
                InputStream input = new BufferedInputStream(
                        activity.getContentResolver().openInputStream(uri), 256 * 1024
                );
                FileOutputStream output = new FileOutputStream(target)
        ) {
            if (input == null) {
                throw new IOException("File ZIP tidak dapat dibuka dari penyimpanan.");
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
            List<ZipEntry> entries = Collections.list(zip.entries());
            entries.removeIf(entry -> entry.isDirectory() || isIgnoredEntry(entry.getName()));
            entries.sort((left, right) -> naturalCompare(left.getName(), right.getName()));

            for (ZipEntry entry : entries) {
                ensureNotCancelled();
                String entryName = entry.getName();
                String lower = entryName.toLowerCase(Locale.ROOT);

                if (lower.endsWith(".zip")) {
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
                    continue;
                }

                if (!lower.endsWith(".csv")) {
                    context.ignoredFiles += 1;
                    continue;
                }
                if (!matchesTimeframe(entryName, context.timeframe)) {
                    context.ignoredFiles += 1;
                    continue;
                }

                try (InputStream input = new BufferedInputStream(zip.getInputStream(entry), 128 * 1024)) {
                    processCsv(input, entryName, context);
                }
            }
        }
    }

    private static boolean isIgnoredEntry(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("__macosx/")
                || lower.endsWith(".ds_store")
                || lower.endsWith("thumbs.db");
    }

    private static boolean isDirectCandleName(String lowerName) {
        return lowerName.endsWith(".csv") || lowerName.endsWith(".txt");
    }

    private static boolean matchesTimeframe(String fileName, String selectedTimeframe) {
        String normalized = "_" + fileName.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_") + "_";
        boolean containsKnownTimeframe = false;
        for (String timeframe : KNOWN_TIMEFRAMES) {
            String token = "_" + timeframe + "_";
            if (normalized.contains(token)) {
                containsKnownTimeframe = true;
                if (timeframe.equals(selectedTimeframe)) {
                    return true;
                }
            }
        }
        // A direct generic OHLC file without a timeframe token is accepted as the
        // timeframe chosen by the user. Audited archives use explicit M1/M5/etc names.
        return !containsKnownTimeframe;
    }

    private void processCsv(InputStream input, String fileName, ArchiveContext context) throws Exception {
        ensureNotCancelled();
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
                throw new IOException("File kosong: " + fileName);
            }
            headerLine = stripBom(headerLine);
            char delimiter = detectDelimiter(headerLine);
            CsvSchema schema = CsvSchema.fromHeader(splitLine(headerLine, delimiter));

            context.processedFiles += 1;
            long acceptedInFile = 0L;
            String line;
            while ((line = reader.readLine()) != null) {
                ensureNotCancelled();
                if (line.trim().isEmpty()) {
                    continue;
                }
                Candle candle = schema.parse(splitLine(line, delimiter));
                if (candle == null) {
                    context.invalidRows += 1;
                    continue;
                }

                int result = context.engine.onCandle(candle);
                if (result == StreamingBacktestEngine.DUPLICATE) {
                    context.duplicateRows += 1;
                    continue;
                }
                if (result == StreamingBacktestEngine.OUT_OF_ORDER) {
                    context.outOfOrderRows += 1;
                    continue;
                }
                acceptedInFile += 1;

                if (context.engine.candleCount % PROGRESS_EVERY_CANDLES == 0) {
                    emitProgress(
                            "Memproses " + fileName,
                            context.engine.candleCount,
                            context.processedFiles,
                            fileName
                    );
                }
            }
            if (acceptedInFile == 0L) {
                throw new IOException("Tidak ada candle valid untuk " + context.timeframe + " dalam " + fileName + ".");
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
                int compare = numberA.compareTo(numberB);
                if (compare != 0) return compare;
            } else {
                if (a != b) return Character.compare(a, b);
                i += 1;
                j += 1;
            }
        }
        return Integer.compare(left.length(), right.length());
    }

    private static String selectionSummary(List<SelectedFile> files) {
        if (files.isEmpty()) return "";
        if (files.size() == 1) return files.get(0).name;
        if (files.size() == 2) return files.get(0).name + " + " + files.get(1).name;
        return files.get(0).name + " + " + (files.size() - 1) + " file lain";
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

    private static final class SelectedFile {
        final Uri uri;
        final String name;

        SelectedFile(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    private static final class ArchiveContext {
        final StreamingBacktestEngine engine;
        final String timeframe;
        int processedFiles = 0;
        int ignoredFiles = 0;
        long invalidRows = 0L;
        long duplicateRows = 0L;
        long outOfOrderRows = 0L;

        ArchiveContext(StreamingBacktestEngine engine, String timeframe) {
            this.engine = engine;
            this.timeframe = timeframe;
        }
    }

    private static final class BacktestConfig {
        final String mode;
        final String timeframe;
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
                String timeframe,
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
            this.timeframe = timeframe;
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

            String timeframe = json.optString("timeframe", "M5").toUpperCase(Locale.ROOT);
            boolean timeframeValid = false;
            for (String candidate : KNOWN_TIMEFRAMES) {
                if (candidate.equals(timeframe)) {
                    timeframeValid = true;
                    break;
                }
            }
            if (!timeframeValid) {
                throw new JSONException("Timeframe harus M1, M5, M15, H1, H4, atau D1.");
            }

            return new BacktestConfig(
                    mode,
                    timeframe,
                    clamp(json.optInt("lookback", 20), 3, 10_000),
                    clamp(json.optInt("atr_period", 14), 2, 10_000),
                    Math.max(0.0, json.optDouble("min_penetration_atr", 0.05)),
                    clamp01(json.optDouble("min_wick_ratio", 0.35)),
                    clamp(json.optInt("acceptance_closes", 2), 1, 5),
                    clamp01(json.optDouble("min_body_ratio", 0.55)),
                    Math.max(0.5, Math.min(1.0, json.optDouble("close_location", 0.70))),
                    positive(json.optDouble("sl_atr", 1.0), "SL ATR"),
                    Math.max(0.0, json.optDouble("stop_buffer_atr", 0.10)),
                    positive(json.optDouble("rr_ratio", 2.0), "RR"),
                    clamp(json.optInt("max_hold_bars", 24), 1, 100_000),
                    Math.max(0.0, json.optDouble("cost_per_trade_r", 0.03)),
                    positive(json.optDouble("initial_capital", 10_000.0), "Modal awal"),
                    positive(json.optDouble("risk_per_trade_pct", 1.0), "Risiko") / 100.0
            );
        }

        private static int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
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
                throw new IOException("Header CSV wajib memiliki open, high, low, close.");
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
                if (!allFinite(openValue, highValue, lowValue, closeValue)) return null;
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
                return new Candle(timestampValue.trim(), openValue, highValue, lowValue, closeValue);
            } catch (Exception ignored) {
                return null;
            }
        }

        private static int find(Map<String, Integer> indexes, String... aliases) {
            for (String alias : aliases) {
                Integer index = indexes.get(alias);
                if (index != null) return index;
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
        static final int ACCEPTED = 1;
        static final int DUPLICATE = 0;
        static final int OUT_OF_ORDER = -1;

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

        int onCandle(Candle candle) throws JSONException {
            if (!candle.timestamp.isEmpty() && !lastTimestamp.isEmpty()) {
                int compare = candle.timestamp.compareTo(lastTimestamp);
                if (compare == 0) return DUPLICATE;
                if (compare < 0) return OUT_OF_ORDER;
            }

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

            double trueRange = candle.high - candle.low;
            if (Double.isFinite(previousClose)) {
                trueRange = Math.max(
                        trueRange,
                        Math.max(Math.abs(candle.high - previousClose), Math.abs(candle.low - previousClose))
                );
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
                if (signal != null) pendingSignal = signal;
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
            return ACCEPTED;
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
            Candle candle = row.candle;
            double range = candle.high - candle.low;
            if (range <= 0.0) return null;
            double lowerWick = (Math.min(candle.open, candle.close) - candle.low) / range;
            double upperWick = (candle.high - Math.max(candle.open, candle.close)) / range;
            double penetration = config.minPenetrationAtr * row.atr;

            boolean bullish = candle.low < row.priorLow - penetration
                    && candle.close > row.priorLow
                    && lowerWick >= config.minWickRatio;
            boolean bearish = candle.high > row.priorHigh + penetration
                    && candle.close < row.priorHigh
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

            Candle candle = last.candle;
            double range = candle.high - candle.low;
            if (range <= 0.0) return null;
            double body = Math.abs(candle.close - candle.open) / range;
            double closeLocation = (candle.close - candle.low) / range;

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
