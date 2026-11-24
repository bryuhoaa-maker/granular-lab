package com.granularlab;

/** Процедурные демо-сэмплы, чтобы звук был из коробки без файлов на SD. */
public class DemoBank {

    public static final String[] NAMES = {"AURORA PAD", "GLASS BELL", "TAPE VOX", "BREAK 120"};
    private static final int SR = Engine.SR;

    public static float[] generate(int idx) {
        switch (idx % 4) {
            case 0:
                return aurora();
            case 1:
                return bell();
            case 2:
                return vox();
            default:
                return brk();
        }
    }

    private static float[] aurora() {
        int n = SR * 6;
        float[] s = new float[n];
        double[] freqs = {110, 164.81, 220, 277.18, 329.63};
        double[] ph = new double[5];
        double[] det = {1.0015, .9987, 1.0008, .999, 1.0021};
        float lp = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) SR;
            float v = 0;
            for (int k = 0; k < 5; k++) {
                ph[k] += freqs[k] * det[k] / SR;
                ph[k] -= Math.floor(ph[k]);
                float saw = (float) (2 * ph[k] - 1);
                float trem = (float) (0.7 + 0.3 * Math.sin(2 * Math.PI * (0.11 + k * 0.07) * t + k));
                v += saw * trem * 0.2f;
            }
            float fc = (float) (900 + 2600 * (0.5 - 0.5 * Math.cos(2 * Math.PI * t / 6)));
            float a = Math.min(1f, fc * 6.283f / SR);
            lp += a * (v - lp);
            float env = (float) (Math.min(1.0, t / .8) * Math.min(1.0, (6 - t) / 1.2));
            s[i] = lp * env;
        }
        return s;
    }

    private static float[] bell() {
        int n = SR * 3;
        float[] s = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) SR;
            double idx = 6 * Math.exp(-t * 3);
            double mod = Math.sin(2 * Math.PI * 1230 * t) * idx;
            double v = Math.sin(2 * Math.PI * 880 * t + mod) * Math.exp(-t * 2.2);
            v += Math.sin(2 * Math.PI * 1760 * t) * .3 * Math.exp(-t * 4);
            v += Math.sin(2 * Math.PI * 2640 * t) * .12 * Math.exp(-t * 6);
            s[i] = (float) (v * Math.min(1.0, t / .002));
        }
        return s;
    }

    private static float[] vox() {
        int n = SR * 4;
        float[] s = new float[n];
        double ph = 0;
        float[] b1 = new float[2], b2 = new float[2], b3 = new float[2];
        for (int i = 0; i < n; i++) {
            double t = i / (double) SR;
            double u = t / 4;
            double f0 = 110 * (1 + .004 * Math.sin(2 * Math.PI * 5 * t));
            ph += f0 / SR;
            ph -= Math.floor(ph);
            float src = (float) (2 * ph - 1);
            float F1 = (float) (700 - 400 * u);
            float F2 = (float) (1100 - 230 * u);
            float F3 = (float) (2600 - 350 * u);
            float y1 = bp(b1, src, F1, 80);
            float y2 = bp(b2, src, F2, 120);
            float y3 = bp(b3, src, F3, 200);
            float env = (float) (Math.min(1.0, t / .5) * Math.min(1.0, 4 - t));
            s[i] = (y1 + y2 * .5f + y3 * .25f) * env;
        }
        return s;
    }

    private static float bp(float[] st, float in, float fc, float bw) {
        float f = (float) (2 * Math.sin(Math.PI * Math.min(fc, 10000f) / SR));
        float q = Math.max(.05f, bw * 6.283f / SR * 2);
        st[0] += f * st[1];
        float hi = in - st[0] - q * st[1];
        st[1] += f * hi;
        return st[1];
    }

    private static float[] brk() {
        int n = SR * 4;
        float[] s = new float[n];
        double step = SR * 0.125;
        int[] kicks = {0, 10, 16, 26};
        int[] snares = {8, 24};
        int[] opens = {14, 30};
        java.util.Random rnd = new java.util.Random(7);
        for (int k = 0; k < 32; k++) {
            int at = (int) (k * step);
            boolean isK = contains(kicks, k);
            boolean isS = contains(snares, k);
            boolean isO = contains(opens, k);
            if (isK) {
                int len = (int) (SR * .22);
                double p = 0;
                for (int i = 0; i < len && at + i < n; i++) {
                    double t = i / (double) SR;
                    double f = 45 + 110 * Math.exp(-t * 30);
                    p += 2 * Math.PI * f / SR;
                    s[at + i] += (float) (Math.sin(p) * Math.exp(-t * 14) * .9);
                }
            }
            if (isS) {
                int len = (int) (SR * .18);
                for (int i = 0; i < len && at + i < n; i++) {
                    double t = i / (double) SR;
                    float nz = rnd.nextFloat() * 2 - 1;
                    s[at + i] += (float) ((nz * .55 + Math.sin(2 * Math.PI * 190 * t) * .35)
                            * Math.exp(-t * 22) * .7);
                }
            }
            if ((k % 2 == 0) || isO) {
                int len = (int) (SR * (isO ? .25 : .045));
                float prev = 0;
                for (int i = 0; i < len && at + i < n; i++) {
                    double t = i / (double) SR;
                    float nz = rnd.nextFloat() * 2 - 1;
                    float hp = nz - prev;
                    prev = nz;
                    s[at + i] += (float) (hp * Math.exp(-t * (isO ? 12 : 70)) * .25);
                }
            }
        }
        return s;
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }
} 
