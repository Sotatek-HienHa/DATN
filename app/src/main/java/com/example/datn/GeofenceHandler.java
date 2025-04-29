package com.example.datn;

import com.google.android.gms.maps.model.LatLng;
import java.util.List;
public class GeofenceHandler {

    public static boolean isPointInPolygon(LatLng point, List<LatLng> polygon) {
        int crossings = 0;
        int count = polygon.size();

        for (int i = 0; i < count; i++) {
            LatLng a = polygon.get(i);
            LatLng b = polygon.get((i + 1) % count);

            if (rayCrossesSegment(point, a, b)) {
                crossings++;
            }
        }
        return (crossings % 2 == 1); // true nếu số lần cắt là lẻ
    }

    private static boolean rayCrossesSegment(LatLng point, LatLng a, LatLng b) {
        if (a.latitude > b.latitude) {
            LatLng temp = a;
            a = b;
            b = temp;
        }

        if (point.latitude == a.latitude || point.latitude == b.latitude) {
            point = new LatLng(point.latitude + 0.00001, point.longitude);
        }

        if (point.latitude < a.latitude || point.latitude > b.latitude) {
            return false;
        }

        double xIntersect = (b.longitude - a.longitude) * (point.latitude - a.latitude)
                / (b.latitude - a.latitude) + a.longitude;

        return (xIntersect > point.longitude);
    }
}