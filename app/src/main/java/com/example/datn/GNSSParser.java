package com.example.datn;

public class GNSSParser {

    private boolean gpsEnabled = false;
    private boolean sbasEnabled = false;  // Thêm trường cho SBAS
    private boolean galileoEnabled = false;
    private boolean beidouEnabled = false;
    private boolean qzssEnabled = false;  // Thêm trường cho QZSS
    private boolean glonassEnabled = false;

    public GNSSParser(byte[] ubxMessage) throws Exception {
        parseUbxCfgGnssMessage(ubxMessage);
    }

    private void parseUbxCfgGnssMessage(byte[] message) throws Exception {
        // Kiểm tra độ dài tối thiểu của thông điệp (header + length + payload + checksum)
        if (message.length < 8) {
            throw new Exception("Độ dài thông điệp UBX không hợp lệ");
        }

        // Tìm thông điệp UBX-CFG-GNSS trong mảng byte
        int offset = 0;
        while (offset < message.length - 8) {
            if ((message[offset] == (byte) 0xB5) && (message[offset + 1] == (byte) 0x62)) {
                if ((message[offset + 2] == 0x06) && (message[offset + 3] == 0x3E)) {
                    break;
                }
            }
            offset++;
        }

        if (offset >= message.length - 8) {
            throw new Exception("Không tìm thấy thông điệp UBX-CFG-GNSS");
        }

        // Đọc độ dài (2 byte, little-endian)
        int length = ((message[offset + 5] & 0xFF) << 8) | (message[offset + 4] & 0xFF);
        if (offset + 6 + length + 2 > message.length) {
            throw new Exception("Thông điệp UBX-CFG-GNSS không đầy đủ");
        }

        // Trích xuất payload
        byte[] payload = new byte[length];
        System.arraycopy(message, offset + 6, payload, 0, length);

        int payloadOffset = 3;
        int numConfigBlocks = payload[payloadOffset++] & 0xFF;

        for (int i = 0; i < numConfigBlocks; i++) {
            if (payloadOffset + 8 > payload.length) {
                throw new Exception("Kết thúc payload không mong muốn");
            }
            int gnssId = payload[payloadOffset++] & 0xFF;
            payloadOffset += 3;
            int flags = ((payload[payloadOffset + 3] & 0xFF) << 24)
                    | ((payload[payloadOffset + 2] & 0xFF) << 16)
                    | ((payload[payloadOffset + 1] & 0xFF) << 8)
                    | (payload[payloadOffset] & 0xFF);
            payloadOffset += 4;

            boolean enabled = (flags & 0x01) != 0;
            switch (gnssId) {
                case 0: // GPS
                    gpsEnabled = enabled;
                    break;
                case 1: // SBAS
                    sbasEnabled = enabled;
                    break;
                case 2: // Galileo
                    galileoEnabled = enabled;
                    break;
                case 3: // BeiDou
                    beidouEnabled = enabled;
                    break;
                case 4: // IMES (bỏ qua)
                    break;
                case 5: // QZSS
                    qzssEnabled = enabled;
                    break;
                case 6: // GLONASS
                    glonassEnabled = enabled;
                    break;
                default:
                    break;
            }
        }
    }

    // Trả về tổng số hệ thống GNSS được bật
    public int getEnabledCount() {
        int count = 0;
        if (gpsEnabled) count++;
        if (sbasEnabled) count++;
        if (galileoEnabled) count++;
        if (beidouEnabled) count++;
        if (qzssEnabled) count++;
        if (glonassEnabled) count++;
        return count;
    }

    public String getStatusSummary() {
        return "GPS:" + gpsEnabled +
                ", SBAS:" + sbasEnabled +
                ", Galileo:" + galileoEnabled +
                ", BeiDou:" + beidouEnabled +
                ", QZSS:" + qzssEnabled +
                ", GLONASS:" + glonassEnabled;
    }


    public boolean isGpsEnabled() {
        return gpsEnabled;
    }

    public boolean isGlonassEnabled() {
        return glonassEnabled;
    }

    public boolean isGalileoEnabled() {
        return galileoEnabled;
    }

    public boolean isBeidouEnabled() {
        return beidouEnabled;
    }
}
