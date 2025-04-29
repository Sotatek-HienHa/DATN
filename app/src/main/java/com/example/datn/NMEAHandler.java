package com.example.datn;

import java.util.HashMap;
import java.util.Map;

public class NMEAHandler {
    // Java interface cho các parser câu NMEA
    interface SentenceParser {
        void parse(String[] tokens, GPSPosition position);
    }

    public static class GPSPosition {
        public float lat = 0.0f;
        public float lon = 0.0f;
        public float dir = 0.0f;
        public float velocity = 0.0f;
        public boolean legit = false;
    }

    GPSPosition position = new GPSPosition();

    static float Latitude2Decimal(String lat, String NS) {
        float med = Float.parseFloat(lat.substring(2)) / 60.0f;
        med += Float.parseFloat(lat.substring(0, 2));
        if (NS.startsWith("S")) {
            med = -med;
        }
        return med;
    }

    static float Longitude2Decimal(String lon, String WE) {
        float med = Float.parseFloat(lon.substring(3)) / 60.0f;
        med += Float.parseFloat(lon.substring(0, 3));
        if (WE.startsWith("W")) {
            med = -med;
        }
        return med;
    }

    // Các parser cho từng loại câu NMEA
    static class RMC implements SentenceParser {
        public void parse(String[] tokens, GPSPosition position) {
            position.lat = ("0".equals(tokens[3]) || "0".equals(tokens[4])) ? 0.0f : Latitude2Decimal(tokens[3], tokens[4]);
            position.lon = ("0".equals(tokens[5]) || "0".equals(tokens[6])) ? 0.0f : Longitude2Decimal(tokens[5], tokens[6]);
            position.velocity = "0".equals(tokens[7]) ? 0.0f : Float.parseFloat(tokens[7]);
            position.dir = "0".equals(tokens[8]) ? 0.0f : Float.parseFloat(tokens[8]);
            position.legit = "A".equals(tokens[2]);
        }
    }
    class VTG implements SentenceParser {
        public void parse(String[] tokens, GPSPosition position) {
            position.dir = "0".equals(tokens[3]) ? 0.0f : Float.parseFloat(tokens[3]);
            position.velocity = "0".equals(tokens[5]) ? 0.0f : Float.parseFloat(tokens[5]);
            position.legit = false;
        }
    }
    class GNS implements SentenceParser {
        public void parse(String[] tokens, GPSPosition position) {
            position.lat = ("0".equals(tokens[2]) || "0".equals(tokens[3])) ? 0.0f : Latitude2Decimal(tokens[2], tokens[3]);
            position.lon = ("0".equals(tokens[4]) || "0".equals(tokens[5])) ? 0.0f : Longitude2Decimal(tokens[4], tokens[5]);
            String mode = tokens[6];
            position.legit = mode.matches(".*[ADEMS].*");
        }
    }
    class GGA implements SentenceParser {
        public void parse(String[] tokens, GPSPosition position) {
            position.lat = ("0".equals(tokens[2]) || "0".equals(tokens[3])) ? 0.0f : Latitude2Decimal(tokens[2], tokens[3]);
            position.lon = ("0".equals(tokens[4]) || "0".equals(tokens[5])) ? 0.0f : Longitude2Decimal(tokens[4], tokens[5]);
            int fixQuality = tokens[6].isEmpty() ? 0 : Integer.parseInt(tokens[6]);
            position.legit = fixQuality > 0;
        }
    }

    private static final Map<String, SentenceParser> sentenceParsers = new HashMap<>();

    public NMEAHandler() {
        sentenceParsers.put("VTG", new VTG());
        sentenceParsers.put("RMC", new RMC());
        sentenceParsers.put("GNS", new GNS());
        sentenceParsers.put("GGA", new GGA());
    }

    public GPSPosition parse(String line) {

        if (line.startsWith("$G")) {
            String nmea = line.substring(1);
            String[] tokens = nmea.split(",");
            String type = tokens[0].substring(2);
            int i = 0;
            for (String x : tokens) {
                if (x.isEmpty()) {
                    tokens[i] = "0";
                }
                i++;
            }
            // TODO: kiểm tra CRC
            if (sentenceParsers.containsKey(type)) {
                sentenceParsers.get(type).parse(tokens, position);
            }
        }

        return position;
    }
}
