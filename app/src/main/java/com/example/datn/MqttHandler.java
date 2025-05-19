package com.example.datn;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.UUID;

public class MqttHandler {

    private static final String TAG = "MqttHandler";
    private final String serverUri = "tcp://117.6.59.112:1883";
    private final String deviceToken = "wiOUYA7zDSSTDyaRNR53";
    private final String telemetryTopic = "v1/devices/me/telemetry";
    private final String attributeTopic = "v1/devices/me/attributes";
    private final Context context;
    private MqttAndroidClient mqttClient;
    private MqttConnectOptions mqttConnectOptions;
    private boolean isReconnecting = false;

    public int maxTimeout = 10000;
    public int maxDistance = 10;
    public boolean isGeofenceEnable = false;
    public String province = "Hà Nội";

    public static final int DEVICE_LOCATION = 1;
    public static final int UBLOX_LOCATION = 2;

    private float lastSentLat = 0.0f;
    private float lastSentLon = 0.0f;




    public MqttHandler(Context context) {
        this.context = context.getApplicationContext();
        String clientId = UUID.randomUUID().toString();
        mqttClient = new MqttAndroidClient(context.getApplicationContext(), serverUri, clientId);

        mqttConnectOptions = new MqttConnectOptions();
        mqttConnectOptions.setCleanSession(true);
        mqttConnectOptions.setUserName(deviceToken);

        mqttClient.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                Log.d(TAG, "Connected to ThingsBoard: " + serverURI);
                // Sau khi kết nối thành công, subscribe vào topic attributes để nhận cập nhật
                try {
                    mqttClient.subscribe(attributeTopic, 1);
                } catch (MqttException e) {
                    Log.e(TAG, "Subscription to attributes failed: " + e.getMessage());
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                Log.w(TAG, "Connection lost: " + (cause != null ? cause.getMessage() : "Unknown"));
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                Log.d(TAG, "Message arrived. Topic: " + topic + ". Message: " + message.toString());
                if (topic.equals(attributeTopic)) {
                    try {
                        JSONObject json = new JSONObject(message.toString());
                        Log.d(TAG, "Attributes received: " + json.toString());
                        if (json.has("max_distance")) {
                            maxDistance = json.getInt("max_distance");
                            Log.d(TAG, "Updated maxDistance: " + maxDistance);
                        }
                        if (json.has("max_timeout")) {
                            maxTimeout = json.getInt("max_timeout");
                            Log.d(TAG, "Updated maxTimeout: " + maxTimeout);
                        }
                        if (json.has("isGeofenceEnable")) {
                            isGeofenceEnable = json.getBoolean("isGeofenceEnable");
                            if (isGeofenceEnable) {
                                if (lastSentLat != 0.0f && lastSentLon != 0.0f) {
                                    LatLng point = new LatLng(lastSentLat, lastSentLon);
                                    boolean isNowOutside = !GetBoundary.isPointInProvince(context, point, province);
                                    sendOutsideAttribute(isNowOutside);
                                }
                                sendBoundaryAttribute(GetBoundary.getBoundaryForProvince(context, province));
                            } else {
                                sendBoundaryAttribute(null);
                            }
                            Log.d(TAG, "Updated isGeofenceEnable: " + isGeofenceEnable);
                        }
                        if (json.has("provinces")) {
                            province = json.getString("provinces");
                            if (isGeofenceEnable) {
                                sendBoundaryAttribute(GetBoundary.getBoundaryForProvince(context, province));
                                Log.d("update boundary", GetBoundary.getBoundaryForProvince(context, province).toString());
                            } else {
                                sendBoundaryAttribute(null);
                            }
                            Log.d(TAG, "Updated province: " + province);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Failed to parse attribute JSON: " + e.getMessage());
                    }
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {

            }

        });

        connect();
        sendInitDevice();
    }

    public void connect() {
        try {
            mqttClient.connect(mqttConnectOptions, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.d(TAG, "Connected to ThingsBoard");
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(TAG, "Connection failed: " + exception.getMessage());
                }
            });
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    private void sendMqttMessage(String payload, String topic) {
        if (!mqttClient.isConnected()) {
            Log.w(TAG, "MQTT not connected. Trying to reconnect...");
            if (!isReconnecting) {
                reconnect(() -> sendMqttMessage(payload, topic));
            }
            return;
        }
        MqttMessage message = new MqttMessage(payload.getBytes());
        message.setQos(1);

        try {
            mqttClient.publish(topic, message, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.d(TAG, "MQTT sent: " + payload + " at " + topic);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(TAG, "Publish failed: " + exception.getMessage());
                    if (exception.getMessage().contains("not connected") && !isReconnecting) {
                        reconnect(() -> sendMqttMessage(payload, topic));
                    }
                }
            });
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    private void sendTelemetryMessage(String payload) {
        sendMqttMessage(payload, telemetryTopic);
    }

    private void sendAttributeMessage(String payload) {
        sendMqttMessage(payload, attributeTopic);
    }

    public void sendLocationTelemetry (double lat, double lon, double speed, int mode) {
        String payload;
        switch (mode) {
            case MqttHandler.DEVICE_LOCATION:
                payload = String.format("{\"device latitude\": %.6f, \"device longitude\": %.6f, \"speed\": %.1f}", lat, lon, speed);
                break;
            case MqttHandler.UBLOX_LOCATION:
                payload = String.format("{\"latitude\": %.6f, \"longitude\": %.6f, \"speed\": %.6f}", lat, lon, speed);
                break;
            default:
                payload = "";
                break;
        }
        lastSentLat = (float) lat;
        lastSentLon = (float) lon;
        sendTelemetryMessage(payload);
    }

//    @SuppressLint("DefaultLocale")
//    public void sendLocationTelemetry(double lat, double lon, double speed, int mode) {
//        if (!mqttClient.isConnected()) {
//            Log.w(TAG, "MQTT not connected. Trying to reconnect...");
//            if (!isReconnecting) {
//                reconnect(() -> sendLocationTelemetry(lat, lon, speed, mode));
//            }
//            return;
//        }
//        String payload;
//        switch (mode) {
//            case MqttHandler.DEVICE_LOCATION:
//                payload = String.format("{\"device latitude\": %.6f, \"device longitude\": %.6f, \"speed\": %.1f}", lat, lon, speed);
//                break;
//            case MqttHandler.UBLOX_LOCATION:
//                payload = String.format("{\"latitude\": %.6f, \"longitude\": %.6f, \"speed\": %.6f}", lat, lon, speed);
//                break;
//            default:
//                payload = "";
//                break;
//        }
//        MqttMessage message = new MqttMessage(payload.getBytes());
//        message.setQos(1);
//
//        try {
//            mqttClient.publish(telemetryTopic, message, null, new IMqttActionListener() {
//                @Override
//                public void onSuccess(IMqttToken asyncActionToken) {
//                    Log.d(TAG, "Telemetry sent: " + payload);
//                }
//
//                @Override
//                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
//                    Log.e(TAG, "Publish failed: " + exception.getMessage());
//                    if (exception.getMessage().contains("not connected") && !isReconnecting) {
//                        reconnect(() -> sendLocationTelemetry(lat, lon, speed, mode));
//                    }
//                }
//            });
//        } catch (MqttException e) {
//            e.printStackTrace();
//        }
//    }

    public void sendDistanceTelemetry(float distance) {
        if (!mqttClient.isConnected()) {
            Log.w(TAG, "MQTT not connected. Trying to reconnect...");
            if (!isReconnecting) {
                reconnect(() -> sendDistanceTelemetry(distance));
            }
            return;
        }
        String payload;
        payload = String.format("{\"distance\": %.1f}", distance);
        MqttMessage message = new MqttMessage(payload.getBytes());
        message.setQos(1);

        try {
            mqttClient.publish(telemetryTopic, message, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.d(TAG, "Telemetry sent: " + payload);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(TAG, "Publish failed: " + exception.getMessage());
                    if (exception.getMessage().contains("not connected") && !isReconnecting) {
                        reconnect(() -> sendDistanceTelemetry(distance));
                    }
                }
            });
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    public void sendBatteryAttribute(int batteryLevel, boolean isCharging) {
        String payload = String.format("{\"battery_level\": %d, \"is_charging\": %s}", batteryLevel, isCharging);
        sendAttributeMessage(payload);
    }

//    public void sendBatteryAttribute(int batteryLevel, boolean isCharging) {
//        if (!mqttClient.isConnected()) {
//            Log.w(TAG, "MQTT not connected. Trying to reconnect...");
//            if (!isReconnecting) {
//                reconnect(() -> sendBatteryAttribute(batteryLevel, isCharging));
//            }
//            return;
//        }
//        String payload = String.format("{\"battery_level\": %d, \"is_charging\": %s}", batteryLevel, isCharging);
//        MqttMessage message = new MqttMessage(payload.getBytes());
//        message.setQos(1);
//
//        try {
//            mqttClient.publish(attributeTopic, message, null, new IMqttActionListener() {
//                @Override
//                public void onSuccess(IMqttToken asyncActionToken) {
//                    Log.d(TAG, "Telemetry sent: " + payload);
//                }
//
//                @Override
//                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
//                    Log.e(TAG, "Publish failed: " + exception.getMessage());
//                    if (exception.getMessage().contains("not connected") && !isReconnecting) {
//                        reconnect(() -> sendBatteryAttribute(batteryLevel, isCharging));
//                    }
//                }
//            });
//        } catch (MqttException e) {
//            e.printStackTrace();
//        }
//    }

    public void sendOutsideAttribute(boolean isOutside) {
        String payload = String.format("{\"isOutside\": %s}", isOutside);
        sendAttributeMessage(payload);
    }

//    public void sendOutsideAttribute(boolean isOutside) {
//        if (!mqttClient.isConnected()) {
//
//            Log.w(TAG, "MQTT not connected. Trying to reconnect...");
//            if (!isReconnecting) {
//                reconnect(() -> sendOutsideAttribute(isOutside));
//            }
//            return;
//        }
//
//        String payload = String.format("{\"isOutside\": %s}", isOutside);
//
//        MqttMessage message = new MqttMessage(payload.getBytes());
//        message.setQos(1);
//
//        try {
//            mqttClient.publish(attributeTopic, message, null, new IMqttActionListener() {
//                @Override
//                public void onSuccess(IMqttToken asyncActionToken) {
//                    Log.d(TAG, "Telemetry sent: " + payload);
//                }
//
//                @Override
//                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
//                    Log.e(TAG, "Publish failed: " + exception.getMessage());
//                    if (exception.getMessage().contains("not connected") && !isReconnecting) {
//                        reconnect(() -> sendOutsideAttribute(isOutside));
//                    }
//                }
//            });
//        } catch (MqttException e) {
//            e.printStackTrace();
//        }
//    }

    public void sendGpsStatusAttribute(GpsState gpsState) {
        String gpsStatus = gpsState.toString();
        String payload = String.format("{\"gps_status\": \"%s\"}", gpsStatus);
        Log.d("payload", payload);
        sendAttributeMessage(payload);
    }

//    public void sendGpsStatusAttribute(boolean gpsStatus) {
//        if (!mqttClient.isConnected()) {
//            Log.w(TAG, "MQTT not connected. Trying to reconnect...");
//            if (!isReconnecting) {
//                reconnect(() -> sendGpsStatusAttribute(gpsStatus));
//            }
//            return;
//        }
//        String payload = String.format("{\"gps_status\": %s}", gpsStatus);
//
//        MqttMessage message = new MqttMessage(payload.getBytes());
//        message.setQos(1);
//
//        try {
//            mqttClient.publish(attributeTopic, message, null, new IMqttActionListener() {
//                @Override
//                public void onSuccess(IMqttToken asyncActionToken) {
//                    Log.d(TAG, "Telemetry sent: " + payload);
//                }
//
//                @Override
//                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
//                    Log.e(TAG, "Publish failed: " + exception.getMessage());
//                    if (exception.getMessage().contains("not connected") && !isReconnecting) {
//                        reconnect(() -> sendGpsStatusAttribute(gpsStatus));
//                    }
//                }
//            });
//        } catch (MqttException e) {
//            e.printStackTrace();
//        }
//    }

    public void sendBoundaryAttribute(List<LatLng> provinceBoundary) {
        StringBuilder payloadBuilder = new StringBuilder();
        payloadBuilder.append("{\"provinceBoundary\":[");
        if (provinceBoundary != null) {
            for (int i = 0; i < provinceBoundary.size(); i++) {
                LatLng point = provinceBoundary.get(i);
                payloadBuilder.append(String.format("[%.6f,%.6f]", point.latitude, point.longitude));
                if (i < provinceBoundary.size() - 1) {
                    payloadBuilder.append(",");
                }
            }
        }
        payloadBuilder.append("]}");
        String payload = payloadBuilder.toString();
        sendAttributeMessage(payload);
    }

//    @SuppressLint("DefaultLocale")
//    public void sendBoundaryAttribute(List<LatLng> provinceBoundary) {
//        if (!mqttClient.isConnected()) {
//            Log.w(TAG, "MQTT not connected. Trying to reconnect...");
//            if (!isReconnecting) {
//                reconnect(() -> sendBoundaryAttribute(provinceBoundary));
//            }
//            return;
//        }
//
//        // Build payload dạng {"provinceBoundary":[[x1,y1],[x2,y2],...]}
//        StringBuilder payloadBuilder = new StringBuilder();
//        payloadBuilder.append("{\"provinceBoundary\":[");
//        if (provinceBoundary != null) {
//            for (int i = 0; i < provinceBoundary.size(); i++) {
//                LatLng point = provinceBoundary.get(i);
//                payloadBuilder.append(String.format("[%.6f,%.6f]", point.latitude, point.longitude));
//                if (i < provinceBoundary.size() - 1) {
//                    payloadBuilder.append(",");
//                }
//            }
//        }
//        payloadBuilder.append("]}");
//        String payload = payloadBuilder.toString();
//        MqttMessage message = new MqttMessage(payload.getBytes());
//        message.setQos(1);
//
//        try {
//            mqttClient.publish(attributeTopic, message, null, new IMqttActionListener() {
//                @Override
//                public void onSuccess(IMqttToken asyncActionToken) {
//                    Log.d("attributesend", "Attribute sent: " + payload);
//                }
//
//                @Override
//                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
//                    Log.e(TAG, "Publish attribute failed: " + exception.getMessage());
//                    if (exception.getMessage().contains("not connected") && !isReconnecting) {
//                        reconnect(() -> sendBoundaryAttribute(provinceBoundary));
//                    }
//                }
//            });
//        } catch (MqttException e) {
//            e.printStackTrace();
//        }
//    }

    public void sendInitDevice() {
        String payload = String.format("{\"isGeofenceEnable\": %b, \"max_distance\": %d, \"max_timeout\": %d, \"provinces\": \"%s\", " +
                        "\"isOutside\": %b, \"provinceBoundary\": %s}",
                isGeofenceEnable, maxDistance, maxTimeout, province, false, "[]");
        sendAttributeMessage(payload);
    }

//    public void sendInitDevice() {
//        if (!mqttClient.isConnected()) {
//            Log.w(TAG, "MQTT not connected. Trying to reconnect...");
//            if (!isReconnecting) {
//                reconnect(() -> sendInitDevice());
//            }
//            return;
//        }
//        String payload = String.format("{\"isGeofenceEnable\": %b, \"max_distance\": %d, \"max_timeout\": %d, \"provinces\": \"%s\", " +
//                        "\"isOutside\": %b, \"provinceBoundary\": %s}",
//                isGeofenceEnable, maxDistance, maxTimeout, province, false, "[]");
//        MqttMessage message = new MqttMessage(payload.getBytes());
//        message.setQos(1);
//
//        try {
//            mqttClient.publish(attributeTopic, message, null, new IMqttActionListener() {
//                @Override
//                public void onSuccess(IMqttToken asyncActionToken) {
//                    Log.d("attributesend", "Attribute sent: " + payload);
//                }
//
//                @Override
//                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
//                    Log.e(TAG, "Publish attribute failed: " + exception.getMessage());
//                    if (exception.getMessage().contains("not connected") && !isReconnecting) {
//                        reconnect(() -> sendInitDevice());
//                    }
//                }
//            });
//        } catch (MqttException e) {
//            e.printStackTrace();
//        }
//    }

    private interface ReconnectCallback {
        void onReconnected();
    }

    private void reconnect(ReconnectCallback callback) {
        if (mqttClient.isConnected() || isReconnecting) {
            return;
        }

        isReconnecting = true;

        try {
            mqttClient.connect(mqttConnectOptions, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    isReconnecting = false;
                    Log.d(TAG, "Reconnected successfully");
                    if (callback != null) callback.onReconnected();
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    isReconnecting = false;
                    Log.e(TAG, "Reconnection failed: " + exception.getMessage());
                }
            });
        } catch (MqttException e) {
            isReconnecting = false;
            e.printStackTrace();
        }
    }
}
