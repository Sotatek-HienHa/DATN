package com.example.datn;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import com.google.android.gms.maps.model.LatLng;

public class MainActivity extends AppCompatActivity {

    private UartReader uartReader;
    private NMEAProcessor nmeaProcessor;
    private NMEAHandler nmeaHandler;
    private Thread readThread;

    // TextViews để hiển thị dữ liệu
    private TextView txtCoordinates;
    private TextView txtSatellitesEnabled;
    private TextView txtDeviceCoordinates; // Hiển thị tọa độ của Android

    private LocationManager locationManager;
    private LocationListener locationListener;
    private MqttHandler mqttHandler;
    private LocalStorageManager localStorageManager;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 100;

    // Buffer để lưu các tọa độ GNSS từ các hệ thống vệ tinh
    private final List<float[]> gnssCoordinatesBuffer = Collections.synchronizedList(new ArrayList<>());

    // Lưu tọa độ trung bình đã gửi gần nhất (dùng để so sánh thay đổi vị trí)
    private float lastSentAvgLat = -1.0f;
    private float lastSentAvgLon = -1.0f;
    private long lastTimestamp = 0;
    private int maxTimeout = 300000;
    private int maxDistance = 10;
    private String province;
    private boolean isGeofenceEnable = false;
    private boolean isOutside = false;
    private boolean gpsStatus = false;
    private boolean lastSentGpsStatus = true;
    private int batteryLevel = 0;
    private boolean isCharging = false;
    private float i = 0.01f;
    private double lastSendDeviceLat = 0.0d;
    private double lastSendDeviceLon = 0.0d;

    // Handler và Runnable để tính trung bình và gửi tọa độ sau mỗi 30 giây
    private final Handler averageHandler = new Handler(Looper.getMainLooper());
    private final Runnable averageAndSendRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d("max timeout", String.valueOf(maxTimeout));
            flushBufferAndSendAverage();
            // Lên lịch chạy lại sau 30 giây
            averageHandler.postDelayed(this, maxTimeout);
        }
    };

    private final Handler updateAttributesHandler = new Handler();
    private final Runnable updateAttributesRunnable = new Runnable() {
        @Override
        public void run() {
            if (maxTimeout != mqttHandler.maxTimeout
                    || maxDistance != mqttHandler.maxDistance
                    || !Objects.equals(province, mqttHandler.province)
                    || isGeofenceEnable != mqttHandler.isGeofenceEnable) {
                maxTimeout = mqttHandler.maxTimeout;
                maxDistance = mqttHandler.maxDistance;
                province = mqttHandler.province;
                isGeofenceEnable = mqttHandler.isGeofenceEnable;
                Log.d("AttributesUpdate", "Updated maxTimeout: " + maxTimeout + ", maxDistance: " + maxDistance);
                // Cập nhật yêu cầu định vị với các thông số mới
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    locationManager.removeUpdates(locationListener);
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, maxTimeout, 0, locationListener);
                }
                // Cập nhật lại lịch gửi trung bình: huỷ và đăng lại với khoảng thời gian mới
                averageHandler.removeCallbacks(averageAndSendRunnable);
                averageHandler.postDelayed(averageAndSendRunnable, maxTimeout);
            }
            updateAttributesHandler.postDelayed(this, 5000); // Kiểm tra mỗi 5 giây
//            getBatteryStatus();
//            mqttHandler.sendBatteryAttribute(batteryLevel, isCharging);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getBatteryStatus();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mqttHandler = new MqttHandler(this);
//        mqttHandler.sendInitDevice();
        localStorageManager = new LocalStorageManager(this);

        // Ánh xạ các TextView từ layout
        txtCoordinates = findViewById(R.id.txtCoordinates);
        txtSatellitesEnabled = findViewById(R.id.txtSatellitesEnabled);
        txtDeviceCoordinates = findViewById(R.id.txtDeviceCoordinates);

        // Khởi tạo các đối tượng UART và xử lý NMEA
        uartReader = new UartReader();
        nmeaProcessor = new NMEAProcessor();
        nmeaHandler = new NMEAHandler();

        // Thiết lập dịch vụ định vị của Android
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                getBatteryStatus();
                double deviceLat = location.getLatitude();
                double deviceLon = location.getLongitude();
                long currentTimestamp = System.currentTimeMillis();
                float speed = 0.0f;
                long timeDifference = (currentTimestamp - lastTimestamp) / 1000;
                deviceLat += i;
                deviceLon += i;
                i+=0.01f;
                // mqttHandler.sendGpsStatusAttribute(true);
                // mqttHandler.sendLocationTelemetry(deviceLat, deviceLon, 30.5f,MqttHandler.DEVICE_LOCATION);
//                if (lastSendDeviceLat != 0.0d && lastSendDeviceLon != 0.0d) {
//                    double distance = LocationUtils.calculateDistance(deviceLat, deviceLon, lastSendDeviceLat, lastSendDeviceLon);
//                    mqttHandler.sendDistacneTelemetry((float) distance);
//                    speed = (float) (distance / timeDifference);
//                }
                if (!gpsStatus) {
                    if (lastSendDeviceLat != 0.0d && lastSendDeviceLon != 0.0d) {
                        double distance = LocationUtils.calculateDistance(deviceLat, deviceLon, lastSendDeviceLat, lastSendDeviceLon);
                        mqttHandler.sendDistacneTelemetry((float) distance);
                        speed = (float) (distance / timeDifference);
                    }
                    mqttHandler.sendLocationTelemetry(deviceLat, deviceLon, speed,MqttHandler.DEVICE_LOCATION);
                    if (mqttHandler.isGeofenceEnable && province != null) {
                        LatLng point = new LatLng(deviceLat, deviceLon);
                        boolean isNowOutside = !GetBoundary.isPointInProvince(MainActivity.this, point, province);
                        Log.d("MqttHandler", String.valueOf(isNowOutside));
                        if (isNowOutside && !isOutside) {
                            mqttHandler.sendOutsideAttribute(true);
                            isOutside = true;
                        } else if (!isNowOutside && isOutside) {
                            mqttHandler.sendOutsideAttribute(false);
                            isOutside = false;
                        }
                    }
                }
                lastSendDeviceLat = deviceLat;
                lastSendDeviceLon = deviceLon;
                lastTimestamp = currentTimestamp;


                double finalDeviceLat = deviceLat;
                double finalDeviceLon = deviceLon;
                runOnUiThread(() -> {
                    txtDeviceCoordinates.setText(
                            String.format("Device Lat: %.6f\nDevice Lon: %.6f", finalDeviceLat, finalDeviceLon)
                    );
                });
                // mqttHandler.sendOutsideAttribute(true);
                mqttHandler.sendBatteryAttribute(batteryLevel, isCharging);
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
        };

        // Yêu cầu cấp quyền định vị nếu chưa được cấp
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE
            );
        } else {
            startLocationUpdates();
            Log.d("test", "called");
        }

        // Tạo và phân tích cấu hình GNSS (bật tất cả hệ thống)
        GNSSControl gnssControl = new GNSSControl();
        byte[] configMessage = gnssControl.createConfigGNSSMessage(true, true, true, true, true, true);
        try {
            GNSSParser parser = new GNSSParser(configMessage);
            int enabledCount = parser.getEnabledCount();
            txtSatellitesEnabled.setText("Enabled GNSS systems: " + enabledCount);

        } catch (Exception e) {
            e.printStackTrace();
            txtSatellitesEnabled.setText("Error parsing GNSS config");
        }

        // Mở cổng UART và bắt đầu đọc dữ liệu NMEA
        if (uartReader.openUart("/dev/ttyHSL0")) {
            Log.d("MainActivity", "UART opened successfully");
            startReadingData();
        } else {
            Log.e("MainActivity", "Unable to open UART");
        }

        // Bắt đầu tác vụ trung bình và gửi tọa độ sau mỗi 30 giây
        averageHandler.postDelayed(averageAndSendRunnable, maxTimeout);
        updateAttributesHandler.postDelayed(updateAttributesRunnable, 5000);
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            // Yêu cầu cập nhật vị trí mỗi 5 giây hoặc khi di chuyển 10 mét
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, maxTimeout, 0, locationListener);
        }
    }

    // Phương thức tính trung bình và gửi tọa độ, sau đó cập nhật lastSentAvg
    private void flushBufferAndSendAverage() {
        float sumLat = 0.0f;
        float sumLon = 0.0f;
        float sumSpeed = 0.0f;
        int count = 0;

        synchronized (gnssCoordinatesBuffer) {
            for (float[] coord : gnssCoordinatesBuffer) {
                sumLat += coord[0];
                sumLon += coord[1];
                sumSpeed += coord[2];
                count++;
            }
            gnssCoordinatesBuffer.clear();
        }

        if (isNetworkAvailable()) {
            if (gpsStatus != lastSentGpsStatus) {
                mqttHandler.sendGpsStatusAttribute(gpsStatus);
                lastSentGpsStatus = gpsStatus;
            }
            localStorageManager.syncLocationLogs(mqttHandler);
            if (count > 0) {
                float avgLat = sumLat / count;
                float avgLon = sumLon / count;
                float avgSpeed = sumSpeed / count;
                float processedAvgSpeed = avgSpeed < 5 ? 0 : avgSpeed;
                mqttHandler.sendLocationTelemetry(avgLat, avgLon, processedAvgSpeed, MqttHandler.UBLOX_LOCATION);
                runOnUiThread(() -> {
                    txtCoordinates.setText(String.format("Avg Lat: %.6f\nAvg Lon: %.6f", avgLat, avgLon));
                });
                if (mqttHandler.isGeofenceEnable && province != null) {
                    LatLng point = new LatLng(avgLat, avgLon);
                    boolean isNowOutside = !GetBoundary.isPointInProvince(this, point, province);
                    if (isNowOutside && !isOutside) {
                        mqttHandler.sendOutsideAttribute(true);
                        isOutside = true;
                    } else if (!isNowOutside && isOutside) {
                        mqttHandler.sendOutsideAttribute(false);
                        isOutside = false;
                    }
                }
                lastSentAvgLat = avgLat;
                lastSentAvgLon = avgLon;
                Log.d("AverageTelemetry", "Processed averaged coordinates: " + avgLat + ", " + avgLon);
                gpsStatus = true;
            } else {
                gpsStatus = false;
            }
        } else {
            if (count > 0) {
                float avgLat = sumLat / count;
                float avgLon = sumLon / count;
                localStorageManager.logLocationData(avgLat, avgLon, System.currentTimeMillis());
            }
        }
    }

    private void startReadingData() {
        readThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    int b = uartReader.read();
                    if (b >= 0) {
                        String sentence = nmeaProcessor.processIncomingByte(b);
                        if (sentence != null) {
                            Log.d("sentence", sentence);
                            NMEAHandler.GPSPosition pos = nmeaHandler.parse(sentence);
                            Log.d("legit", String.valueOf(pos.legit));
                            if (pos.legit) {
                                float lat = pos.lat;
                                float lon = pos.lon;
                                float speed = pos.velocity * 1.852f;
                                // Thêm tọa độ nhận được vào buffer
                                gnssCoordinatesBuffer.add(new float[]{lat, lon, speed});

                                // Nếu đã có tọa độ trung bình được gửi trước đó thì kiểm tra sự thay đổi vị trí
                                if (lastSentAvgLat >= 0 && lastSentAvgLon >= 0) {
                                    float[] results = new float[1];
                                    Location.distanceBetween(lastSentAvgLat, lastSentAvgLon, lat, lon, results);
                                    float distance = results[0];
                                    mqttHandler.sendDistacneTelemetry(distance);
                                    if (distance >= maxDistance) {
                                        // Nếu thay đổi ≥10m, gửi ngay trung bình các tọa độ trong buffer
                                        flushBufferAndSendAverage();
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        readThread.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uartReader.closeUart();
        if (readThread != null && readThread.isAlive()) {
            readThread.interrupt();
        }
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        // Hủy các callback của tác vụ trung bình
        averageHandler.removeCallbacks(averageAndSendRunnable);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE &&
                grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }

    private void getBatteryStatus() {
        BatteryManager batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        int batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        boolean isCharging = batteryManager.isCharging();
        this.batteryLevel = batteryLevel;
        this.isCharging = isCharging;

        Log.d("BatteryStatus", "Battery Level: " + batteryLevel + "%, Charging: " + isCharging);
    }

}
