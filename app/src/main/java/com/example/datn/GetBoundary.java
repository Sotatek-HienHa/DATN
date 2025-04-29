package com.example.datn;

import android.content.Context;
import com.google.android.gms.maps.model.LatLng;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class GetBoundary {

    public static Map<String, List<LatLng>> getProvinceBoundaries(Context context) {
        Map<String, List<LatLng>> provinceBoundaries = new HashMap<>();

        String provincesJson = loadJSONFromAsset(context, "provinces.json");
        if (provincesJson == null) return provinceBoundaries; // Trả về empty nếu lỗi

        JsonArray provinces = JsonParser.parseString(provincesJson).getAsJsonArray();

        for (JsonElement provinceElement : provinces) {
            JsonObject provinceObj = provinceElement.getAsJsonObject();
            String provinceName = provinceObj.get("tỉnh").getAsString();
            JsonArray coordinateGroups = provinceObj.getAsJsonArray("tọa độ");

            List<LatLng> latLngList = new ArrayList<>();
            for (JsonElement groupElement : coordinateGroups) {
                JsonArray coords = groupElement.getAsJsonArray();
                for (JsonElement coordElement : coords) {
                    JsonArray latlng = coordElement.getAsJsonArray();
                    double lng = latlng.get(0).getAsDouble();
                    double lat = latlng.get(1).getAsDouble();
                    latLngList.add(new LatLng(lat, lng));
                }
            }

            provinceBoundaries.put(provinceName, latLngList);
        }

        return provinceBoundaries;
    }

    private static String loadJSONFromAsset(Context context, String filename) {
        String json = null;
        try {
            InputStream is = context.getAssets().open(filename);
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            json = new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        }
        return json;
    }

    public static List<LatLng> getBoundaryForProvince(Context context, String provinceName) {
        Map<String, List<LatLng>> all = getProvinceBoundaries(context);
        return all.getOrDefault(provinceName, new ArrayList<>());
    }

    public static boolean isPointInProvince(Context context, LatLng point, String provinceName) {
        List<LatLng> boundary = getBoundaryForProvince(context, provinceName);
        return boundary != null && !boundary.isEmpty()
                && GeofenceHandler.isPointInPolygon(point, boundary);
    }
}
