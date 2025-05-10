package com.example.datn;

import android.location.Location;
import android.util.Log;

public class LocationUtils {
    public static double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        Log.d("calculate", String.valueOf(lat1));
        Log.d("calculate", String.valueOf(lat2));
        Log.d("calculate", String.valueOf(lon1));
        Log.d("calculate", String.valueOf(lon2));
        // WGS-84 ellipsiod parameters
        final double a = 6378137.0; // semi-major axis in meters
        final double f = 1 / 298.257223563; // flattening
        final double b = (1 - f) * a; // semi-minor axis

        // Convert degrees to radians
        double lat1Rad = Math.toRadians(lat1);
        double lon1Rad = Math.toRadians(lon1);
        double lat2Rad = Math.toRadians(lat2);
        double lon2Rad = Math.toRadians(lon2);

        // Difference in coordinates
        double U1 = Math.atan((1 - f) * Math.tan(lat1Rad));
        double U2 = Math.atan((1 - f) * Math.tan(lat2Rad));
        double L = lon2Rad - lon1Rad;

        double sinU1 = Math.sin(U1);
        double cosU1 = Math.cos(U1);
        double sinU2 = Math.sin(U2);
        double cosU2 = Math.cos(U2);

        double sinL = Math.sin(L);
        double cosL = Math.cos(L);

        // Iterative calculation of sigma
        double lambda = L;
        double sinSigma = 0.0, cosSigma = 0.0, sigma = 0.0; // Initialize sigma to avoid uninitialized variable error
        double sinAlpha, cos2Alpha, cosSigma2, C;
        double deltaSigma;
        int maxIter = 1000;
        int iter = 0;

        while (iter++ < maxIter) {
            sinSigma = Math.sqrt(Math.pow(cosU2 * sinL, 2) + Math.pow(cosU1 * sinU2 - sinU1 * cosU2 * cosL, 2));
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosL;
            sigma = Math.atan2(sinSigma, cosSigma);

            sinAlpha = cosU1 * cosU2 * sinL / sinSigma;
            cos2Alpha = 1 - sinAlpha * sinAlpha;
            cosSigma2 = cosSigma * cosSigma;
            C = f / 16 * cos2Alpha * (4 + f * (4 - 3 * cos2Alpha));

            deltaSigma = C * sinSigma * (cosSigma + C * cosSigma2 * (-1 + 2 * cosSigma2));

            lambda = L + (1 - f) * f * Math.sin(sigma) * (1 + C * cosSigma2 * (2 - cosSigma2));

            if (Math.abs(lambda) <= 1E-12) {
                break;
            }
        }

        // If the iteration did not converge, set a default value for sigma (error case)
        if (iter == maxIter) {
            // Return a large distance if the calculation did not converge
            return -1.0; // or throw an exception, depending on your needs
        }

        // Distance in meters
        double distance = b * sigma;

        // Return distance in kilometers
        return distance;
    }


}
