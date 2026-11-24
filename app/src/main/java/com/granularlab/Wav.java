package com.granularlab;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Чтение RIFF/WAVE (PCM 8/16/24/32, float32, extensible), запись 16-bit, ресемпл, нормализация. */
public class Wav {

    private static int le16(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8)
                | ((b[o + 2] & 255) << 16) | ((b[o + 3] & 255) << 24);
    }

    private static boolean id(byte[] b, int o, String s) {
        for (int i = 0; i < 4; i++) if (b[o + i] != s.charAt(i)) return false;
        return true;
    }

    public static float[] read(String path) throws Exception {
        File f = new File(path);
        if (f.length() > 48 * 1024 * 1024) throw new Exception("файл > 48 МБ");
        byte[] b = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
        } finally {
            in.close();
        }
        if (b.length < 44 || !id(b, 0, "RIFF") || !id(b, 8, "WAVE")) {
            throw new Exception("не RIFF/WAVE");
        }

        int pos = 12, fmt = 0, ch = 0, rate = 0, bits = 0, dataOff = -1, dataLen = 0;
        while (pos + 8 <= b.length) {
            int sz = le32(b, pos + 4);
            if (id(b, pos, "fmt ")) {
                fmt = le16(b, pos + 8);
                ch = le16(b, pos + 10);
                rate = le32(b, pos + 12);
                bits = le16(b, pos + 22);
                if (fmt == 0xFFFE && sz >= 40) fmt = le16(b, pos + 32);
            } else if (id(b, pos, "data")) {
                dataOff = pos + 8;
                dataLen = Math.min(sz, b.length - dataOff);
            }
            if (sz < 0) break;
            pos += 8 + sz + (sz & 1);
        }
        if (dataOff < 0 || ch < 1 || rate < 1000) throw new Exception("нет fmt/data chunk");
        if (fmt != 1 && fmt != 3) throw new Exception("формат " + fmt + " не PCM/float");
        int bp = bits / 8;
        if (bp < 1) throw new Exception("bits " + bits);

        int frames = dataLen / (bp * ch);
        float[] out = new float[frames];
        for (int i = 0; i < frames; i++) {
            float acc = 0;
            for (int c = 0; c < ch; c++) {
                int o = dataOff + (i * ch + c) * bp;
                float v;
                if (fmt == 3 && bits == 32) {
                    v = Float.intBitsToFloat(le32(b, o));
                } else if (bits == 16) {
                    v = (short) le16(b, o) / 32768f;
                } else if (bits == 8) {
                    v = ((b[o] & 255) - 128) / 128f;
                } else if (bits == 24) {
                    int x = (b[o] & 255) | ((b[o + 1] & 255) << 8) | (b[o + 2] << 16);
                    v = x / 8388608f;
                } else if (bits == 32) {
                    v = le32(b, o) / 2147483648f;
                } else {
                    throw new Exception("bits " + bits);
                }
                acc += v;
            }
            out[i] = acc / ch;
        }
        if (rate != Engine.SR) out = resample(out, rate, Engine.SR);
        return out;
    }

    public static float[] resample(float[] in, int from, int to) {
        if (from == to || in.length == 0) return in;
        int n = (int) ((long) in.length * to / from);
        float[] out = new float[n];
        double step = from / (double) to;
        for (int i = 0; i < n; i++) {
            double p = i * step;
            int i0 = (int) p;
            if (i0 >= in.length - 1) {
                out[i] = in[in.length - 1];
                continue;
            }
            float fr = (float) (p - i0);
            out[i] = in[i0] + (in[i0 + 1] - in[i0]) * fr;
        }
        return out;
    }

    public static void normalize(float[] s, float peak) {
        float mx = 0;
        for (int i = 0; i < s.length; i++) {
            float a = Math.abs(s[i]);
            if (a > mx) mx = a;
        }
        if (mx < 1e-6f) return;
        float g = peak / mx;
        for (int i = 0; i < s.length; i++) s[i] *= g;
    }

    public static void write(float[] data, int ch, String path) throws Exception {
        File f = new File(path);
        File dir = f.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        int frames = data.length / ch;
        int dataLen = frames * ch * 2;
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        put32(h, 4, 36 + dataLen);
        put(h, 8, "WAVE");
        put(h, 12, "fmt ");
        put32(h, 16, 16);
        put16(h, 20, 1);
        put16(h, 22, ch);
        put32(h, 24, Engine.SR);
        put32(h, 28, Engine.SR * ch * 2);
        put16(h, 32, ch * 2);
        put16(h, 34, 16);
        put(h, 36, "data");
        put32(h, 40, dataLen);

        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(h);
            byte[] buf = new byte[8192];
            int bi = 0;
            for (int i = 0; i < data.length; i++) {
                int v = (int) (Math.max(-1, Math.min(1, data[i])) * 32767);
                buf[bi++] = (byte) (v & 255);
                buf[bi++] = (byte) ((v >> 8) & 255);
                if (bi >= buf.length) {
                    o.write(buf);
                    bi = 0;
                }
            }
            if (bi > 0) o.write(buf, 0, bi);
        } finally {
            o.close();
        }
    }

    private static void put(byte[] b, int o, String s) {
        for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i);
    }

    private static void put16(byte[] b, int o, int v) {
        b[o] = (byte) (v & 255);
        b[o + 1] = (byte) ((v >> 8) & 255);
    }

    private static void put32(byte[] b, int o, int v) {
        put16(b, o, v & 65535);
        put16(b, o + 2, (v >> 16) & 65535);
    }
}
