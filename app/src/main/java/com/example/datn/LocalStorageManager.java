package com.example.datn;

import android.content.Context;
import android.util.Log;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class LocalStorageManager {
    private static final String TAG = "LocalStorageManager";
    private static final String LOCATION_LOG_FILE = "location_logs.txt";
    private static final String STATUS_LOG_FILE = "status_logs.txt";
    private Context context;

    public LocalStorageManager(Context context) {
        this.context = context;
    }

    // Lưu log dữ liệu định vị
    public void logLocationData(double lat, double lon, long timestamp) {
        String logEntry = timestamp + "," + lat + "," + lon + "\n";
        appendToFile(LOCATION_LOG_FILE, logEntry);
    }

    // Lưu log trạng thái hệ thống
    public void logSystemStatus(String status) {
        String logEntry = System.currentTimeMillis() + "," + status + "\n";
        appendToFile(STATUS_LOG_FILE, logEntry);
    }

    private void appendToFile(String filename, String data) {
        try {
            FileOutputStream fos = context.openFileOutput(filename, Context.MODE_APPEND);
            fos.write(data.getBytes());
            fos.close();
            Log.d(TAG, "Logged data to " + filename);
        } catch (IOException e) {
            Log.e(TAG, "Error writing to " + filename + ": " + e.getMessage());
        }
    }

    // Đọc các log đã lưu (đối với dữ liệu định vị)
    public List<String> readLocationLogs() {
        return readLogFile(LOCATION_LOG_FILE);
    }

    // Xóa log sau khi đồng bộ
    public void clearLog(String filename) {
        try {
            FileOutputStream fos = context.openFileOutput(filename, Context.MODE_PRIVATE);
            fos.write("".getBytes());
            fos.close();
            Log.d(TAG, "Cleared log file " + filename);
        } catch (IOException e) {
            Log.e(TAG, "Error clearing " + filename + ": " + e.getMessage());
        }
    }

    private List<String> readLogFile(String filename) {
        List<String> logs = new ArrayList<>();
        try {
            InputStream is = context.openFileInput(filename);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = reader.readLine()) != null) {
                logs.add(line);
            }
            reader.close();
        } catch (IOException e) {
            Log.e(TAG, "Error reading " + filename + ": " + e.getMessage());
        }
        return logs;
    }

    // Đồng bộ các log định vị: gửi qua MQTT và xóa file log
    public void syncLocationLogs(MqttHandler mqttHandler) {
        List<String> logs = readLocationLogs();
        for (String entry : logs) {
            String[] parts = entry.split(",");
            if (parts.length >= 3) {
                try {
                    long timestamp = Long.parseLong(parts[0]);
                    double lat = Double.parseDouble(parts[1]);
                    double lon = Double.parseDouble(parts[2]);
                    double speed = Double.parseDouble(parts[3]);
                    mqttHandler.sendLocationTelemetry(lat, lon, speed, MqttHandler.UBLOX_LOCATION);
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Invalid log entry: " + entry);
                }
            }
        }
        clearLog(LOCATION_LOG_FILE);
    }
}
