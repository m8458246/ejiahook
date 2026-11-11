package com.cycy.ejiahook;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 定位日志：每次 ejia 拿到一次新的定位，就往
 * {@code /sdcard/Download/ejiahook/location.log} 记一行，新的在最上面。
 *
 * <p>写文件放到单线程队列里，不阻塞定位回调。</p>
 */
final class LocationLog {

    static final String DIR = "/sdcard/Download/ejiahook";
    static final String FILE_PATH = DIR + "/location.log";
    private static final int MAX_LINES = 300;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Object LOCK = new Object();

    private LocationLog() {
    }

    static void append(double lat, double lng, double offsetMeters) {
        String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
        String line = String.format(Locale.US, "%s  %.6f,%.6f  偏移 %.1fm", time, lat, lng, offsetMeters);
        try {
            EXEC.execute(() -> write(line));
        } catch (Throwable ignored) {
        }
    }

    private static void write(String line) {
        synchronized (LOCK) {
            try {
                File dir = new File(DIR);
                if (!dir.exists() && !dir.mkdirs()) {
                    return;
                }
                File file = new File(FILE_PATH);
                List<String> lines = new ArrayList<>();
                if (file.isFile()) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
                        String text;
                        while ((text = reader.readLine()) != null && lines.size() < MAX_LINES) {
                            lines.add(text);
                        }
                    }
                }
                lines.add(0, line);
                if (lines.size() > MAX_LINES) {
                    lines = lines.subList(0, MAX_LINES);
                }
                try (BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(file), "UTF-8"))) {
                    for (String text : lines) {
                        writer.write(text);
                        writer.newLine();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
