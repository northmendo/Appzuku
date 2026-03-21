package com.northmendo.Appzuku;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class BackgroundRestrictionLog {
    private static final String LOG_FILE_NAME = "background_restriction.log";
    private static final int MAX_ENTRIES = 200;
    private static final int MAX_DETAIL_LENGTH = 180;
    private static final Object LOCK = new Object();

    private BackgroundRestrictionLog() {
    }

    public static void log(Context context, String packageName, String action, String outcome, String detail) {
        if (context == null) {
            return;
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String safePackage = sanitize(packageName == null || packageName.trim().isEmpty() ? "-" : packageName);
        String safeAction = sanitize(action == null || action.trim().isEmpty() ? "event" : action);
        String safeOutcome = sanitize(outcome == null || outcome.trim().isEmpty() ? "unknown" : outcome);
        String safeDetail = sanitize(detail);

        StringBuilder entry = new StringBuilder()
                .append(timestamp)
                .append(" | ")
                .append(safeAction)
                .append(" | ")
                .append(safePackage)
                .append(" | ")
                .append(safeOutcome);
        if (!safeDetail.isEmpty()) {
            entry.append(" | ").append(safeDetail);
        }

        appendLine(context, entry.toString());
    }

    public static String readDisplayText(Context context) {
        List<String> lines = readLines(context);
        if (lines.isEmpty()) {
            return "No background restriction events logged yet.\n\nEntries are stored temporarily in cache and trimmed automatically.";
        }

        Collections.reverse(lines);
        StringBuilder display = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                display.append('\n');
            }
            display.append(lines.get(i));
        }
        return display.toString();
    }

    public static void clear(Context context) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            File file = getLogFile(context);
            if (file.exists()) {
                file.delete();
            }
        }
    }

    private static void appendLine(Context context, String line) {
        synchronized (LOCK) {
            List<String> lines = readLinesInternal(context);
            lines.add(line);
            int start = Math.max(0, lines.size() - MAX_ENTRIES);
            List<String> trimmed = new ArrayList<>(lines.subList(start, lines.size()));
            writeLines(context, trimmed);
        }
    }

    private static List<String> readLines(Context context) {
        synchronized (LOCK) {
            return new ArrayList<>(readLinesInternal(context));
        }
    }

    private static List<String> readLinesInternal(Context context) {
        List<String> lines = new ArrayList<>();
        File file = getLogFile(context);
        if (!file.exists()) {
            return lines;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lines.add(line);
                }
            }
        } catch (IOException ignored) {
        }
        return lines;
    }

    private static void writeLines(Context context, List<String> lines) {
        File file = getLogFile(context);
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        } catch (IOException ignored) {
        }
    }

    private static File getLogFile(Context context) {
        return new File(context.getCacheDir(), LOG_FILE_NAME);
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() > MAX_DETAIL_LENGTH) {
            return normalized.substring(0, MAX_DETAIL_LENGTH - 3) + "...";
        }
        return normalized;
    }
}
