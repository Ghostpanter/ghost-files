package com.ghostpanter.scrcpy;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

// Android Studio-compatible Wireless Debugging QR payloads:
//   WIFI:T:ADB;S:<service-name>;P:<password>;;
// The target's "Pair device with QR code" camera starts a pairing server
// advertising _adb-tls-pairing._tcp under that service name.
public final class QrCodes {

    // Studio uses "studio-" + 10 random printable chars. Keep the alphabet
    // conservative so mDNS instance names stay ASCII-safe.
    private static final String NAME_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int NAME_LEN = 10;
    private static final int PASS_LEN = 10;

    public static final class Payload {
        public final String serviceName;
        public final String password;
        public final String qrText;

        public Payload(String serviceName, String password) {
            this.serviceName = serviceName;
            this.password = password;
            this.qrText = "WIFI:T:ADB;S:" + serviceName + ";P:" + password + ";;";
        }
    }

    private QrCodes() {}

    public static Payload generate() {
        SecureRandom rnd = new SecureRandom();
        StringBuilder name = new StringBuilder("studio-");
        for (int i = 0; i < NAME_LEN; i++) {
            name.append(NAME_ALPHABET.charAt(rnd.nextInt(NAME_ALPHABET.length())));
        }
        // Digits-only password matches the pairing-code UX length class.
        StringBuilder pass = new StringBuilder(PASS_LEN);
        for (int i = 0; i < PASS_LEN; i++) {
            pass.append((char) ('0' + rnd.nextInt(10)));
        }
        return new Payload(name.toString(), pass.toString());
    }

    // Parse a WIFI:T:ADB;S:...;P:...;; string (for paste / future camera scan).
    public static Payload parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (!s.toUpperCase(Locale.ROOT).startsWith("WIFI:")) return null;
        String service = null;
        String password = null;
        // Tokenize on ';' but keep escaped values simple (ADB QR has none).
        String body = s.substring(5);
        for (String part : body.split(";")) {
            if (part.isEmpty()) continue;
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            String key = part.substring(0, colon);
            String val = part.substring(colon + 1);
            if ("T".equalsIgnoreCase(key) && !"ADB".equalsIgnoreCase(val)) return null;
            if ("S".equalsIgnoreCase(key)) service = val;
            if ("P".equalsIgnoreCase(key)) password = val;
        }
        if (service == null || service.isEmpty() || password == null || password.isEmpty()) {
            return null;
        }
        return new Payload(service, password);
    }

    public static Bitmap encode(String text, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            var matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int offset = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            return bmp;
        } catch (Exception e) {
            throw new IllegalStateException("QR encode failed", e);
        }
    }
}
