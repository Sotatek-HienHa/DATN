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
    private final String serverUri = "tcp://116.111.116.185:1883";
    private final String deviceToken = "wiOUYA7zDSSTDyaRNR53";
    private final String telemetryTopic = "v1/devices/me/telemetry";
    private final String attributeTopic = "v1/devices/me/attributes";

    private MqttAndroidClient mqttClient;
    private MqttConnectOptions mqttConnectOptions;
    private boolean isReconnecting = false;

    public int maxTimeout = 30000;
    public int maxDistance = 10;
    public boolean isGeofenceEnable = false;
    public String province = "Hà Nội";

    public static final int DEVICE_LOCATION = 1;
    public static final int UBLOX_LOCATION = 2;




    public MqttHandler(Context context) {
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

    @SuppressLint("DefaultLocale")
    public void sendLocationTelemetry(double lat, double lon, double speed, int mode) {
        if (!mqttClient.isConnected()) {
            Log.w(TAG, "MQTT not connected. Trying to reconnect...");
            if (!isReconnecting) {
                reconnect(() -> sendLocationTelemetry(lat, lon, speed, mode));
            }
            return;
        }
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
                        reconnect(() -> sendLocationTelemetry(lat, lon, speed, mode));
                    }
                }
            });
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    public void sendDistacneTelemetry(float distance) {
        if (!mqttClient.isConnected()) {
            Log.w(TAG, "MQTT not connected. Trying to reconnect...");
            if (!isReconnecting) {
                reconnect(() -> sendDistacneTelemetry(distance));
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
                        reconnect(() -> sendDistacneTelemetry(distance));
                    }
                }
            });
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    public void sendBatteryAttribute(int batteryLevel, boolean isCharging) {
        if (!mqttClient.isConnected()) {
            Log.w(TAG, "MQTT not connected. Trying to reconnect...");
            if (!isReconnecting) {
                reconnect(() -> sendBatteryAttribute(batteryLevel, isCharging));
            }
            return;
        }
        String payload = String.format("{\"battery_level\": %d, \"is_charging\": %s}", batteryLevel, isCharging);
        MqttMessage message = new MqttMessage(payload.getBytes());
        message.setQos(1);

        try {
            mqttClient.publish(attributeTopic, message, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.d(TAG, "Telemetry sent: " + payload);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(TAG, "Publish failed: " + exception.getMessage());
                    if (exception.getMessage().contains("not connected") && !isReconnecting) {
                        reconnect(() -> sendBatteryAttribute(batteryLevel, isCharging));
                    }
                }
            });
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }


    public void sendOutsideAttribute(boolean isOutside) {
        if (!mqttClient.isConnected()) {

            Log.w(TAG, "MQTT not connected. Trying to reconnect...");
            if (!isReconnecting) {
                reconnect(() -> sendOutsideAttribute(isOutside));
            }
            return;
        }

        String payload = String.format("{\"isOutside\": %s}", isOutside);

        MqttMessage message = new MqttMessage(payload.getBytes());
        message.setQos(1);

        try {
            mqttClient.publish(attributeTopic, message, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.d(TAG, "Telemetry sent: " + payload);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(TAG, "Publish failed: " + exception.getMessage());
                    if (exception.getMessage().contains("not connected") && !isReconnecting) {
                        reconnect(() -> sendOutsideAttribute(isOutside));
                    }
                }
            });
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    public void sendGpsStatusAttribute(boolean gpsStatus) {
        if (!mqttClient.isConnected()) {
            Log.w(TAG, "MQTT not connected. Trying to reconnect...");
            if (!isReconnecting) {
                reconnect(() -> sendGpsStatusAttribute(gpsStatus));
            }
            return;
        }
        String payload = String.format("{\"gps_status\": %s}", gpsStatus);

        MqttMessage message = new MqttMessage(payload.getBytes());
        message.setQos(1);

        try {
            mqttClient.publish(attributeTopic, message, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.d(TAG, "Telemetry sent: " + payload);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(TAG, "Publish failed: " + exception.getMessage());
                    if (exception.getMessage().contains("not connected") && !isReconnecting) {
                        reconnect(() -> sendGpsStatusAttribute(gpsStatus));
                    }
                }
            });
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("DefaultLocale")
    public void sendBoundaryAttribute(List<LatLng> provinceBoundary) {
        if (!mqttClient.isConnected()) {
            Log.w(TAG, "MQTT not connected. Trying to reconnect...");
            if (!isReconnecting) {
                reconnect(() -> sendBoundaryAttribute(provinceBoundary));
            }
            return;
        }

        // Build payload dạng {"provinceBoundary":[[x1,y1],[x2,y2],...]}
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
        MqttMessage message = new MqttMessage(payload.getBytes());
        message.setQos(1);

        try {
            mqttClient.publish(attributeTopic, message, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.d("attributesend", "Attribute sent: " + payload);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(TAG, "Publish attribute failed: " + exception.getMessage());
                    if (exception.getMessage().contains("not connected") && !isReconnecting) {
                        reconnect(() -> sendBoundaryAttribute(provinceBoundary));
                    }
                }
            });
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

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
