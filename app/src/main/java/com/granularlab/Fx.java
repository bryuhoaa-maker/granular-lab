package com.granularlab;

import java.util.Map;

/**
 * Цепь эффектов: CRUSH -> FILTER (SVF) -> DELAY (stereo) -> REVERB (freeverb) -> master soft-clip.
 * Все миксы и включения сглаживаются, чтобы не было щелчков.
 */
public class Fx {

    private final int sr;
    private float masterCur = .8f;
    private float crushEn = 0, filtEn = 1, delEn = 1, verbEn = 1;

    private final float[] svf = new float[4];
    private float fCoef = 1, qCoef = 1.6f;
    public int fType = 0;

    private int holdN = 1, holdC = 0;
    private float holdL = 0, holdR = 0;
    private float crSteps = 2048, crGain = 1, crPost = 1;

    private final float[] dL, dR;
    private int dW = 0;
    private float dTimeCur = 16537, dFb = .38f, dMix = .24f;
    private float dLpL = 0, dLpR = 0;

    private final float[] combBufL = new float[8 * 1700];
    private final float[] combBufR = new float[8 * 1700];
    private final int[] combT = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private final int[] combI = new int[8];
    private final float[] combFiltL = new float[8];
    private final float[] combFiltR = new float[8];

    private final float[] apBufL = new float[4 * 600];
    private final float[] apBufR = new float[4 * 600];
    private final int[] apT = {556, 441, 341, 225};
    private final int[] apI = new int[4];

    private float vFb = .85f, vDamp = .18f, vMix = .3f;

    private static final int[] CMB_OFF = new int[8];
    private static final int[] AP_OFF = new int[4];

    static {
        int o = 0;
        for (int i = 0; i < 8; i++) {
            CMB_OFF[i] = o;
            o += 1700;
        }
        o = 0;
        for (int i = 0; i < 4; i++) {
            AP_OFF[i] = o;
            o += 600;
        }
    }

    private final float[] out = new float[2];

    public Fx(int sr) {
        this.sr = sr;
        dL = new float[sr];
        dR = new float[sr];
    }

    public void beginBlock(Map<String, Engine.P> p, float bpm, float masterT) {
        masterCur += (masterT - masterCur) * .25f;
        crushEn += (p.get("fxCrush").cur - crushEn) * .25f;
        filtEn += (p.get("fxFilter").cur - filtEn) * .25f;
        delEn += (p.get("fxDelay").cur - delEn) * .25f;
        verbEn += (p.get("fxVerb").cur - verbEn) * .25f;

        fType = (int) p.get("ftype").cur;
        float fc = Math.min(11000, p.get("cutoff").cur);
        fCoef = (float) (2 * Math.sin(Math.PI * fc / sr));
        qCoef = Math.max(.08f, 2f * (1f - p.get("res").cur * .96f));

        float drive = p.get("crDrive").cur;
        crGain = 1 + drive * 15;
        crPost = 1f / (1f + drive * 2.2f);
        crSteps = (float) Math.pow(2, p.get("crBits").cur - 1);
        holdN = Math.max(1, (int) (1f / Math.max(.05f, p.get("crRate").cur)));

        float tMs = p.get("dTime").cur;
        if (p.get("dSync").cur > .5f && bpm > 20) {
            float beat = 60000f / bpm;
            int div = (int) p.get("dDiv").cur;
            float mult = div == 0 ? .25f : div == 1 ? .5f : div == 2 ? 1f : 2f;
            tMs = Math.min(940, beat * mult);
        }
        float tSamp = Math.max(64, Math.min(sr - 4, tMs * .001f * sr));
        dTimeCur += (tSamp - dTimeCur) * .02f;
        dFb = p.get("dFb").cur;
        dMix = p.get("dMix").cur;

        vFb = .7f + p.get("rSize").cur * .28f;
        vDamp = p.get("rDamp").cur * .4f;
        vMix = p.get("rMix").cur;
    }

    private static float soft(float x) {
        float x2 = x * x;
        return x * (27 + x2) / (27 + 9 * x2);
    }

    public float[] process(float l, float r) {
        if (crushEn > .01f) {
            if (holdC <= 0) {
                holdL = Math.round(soft(l * crGain) * crPost * crSteps) / crSteps;
                holdR = Math.round(soft(r * crGain) * crPost * crSteps) / crSteps;
                holdC = holdN;
            }
            holdC--;
            l += (holdL - l) * crushEn;
            r += (holdR - r) * crushEn;
        }

        if (filtEn > .01f) {
            float wl = svf[0], bl = svf[1], wr = svf[2], br = svf[3];
            wl += fCoef * bl;
            float hl = l - wl - qCoef * bl;
            bl += fCoef * hl;
            wr += fCoef * br;
            float hr = r - wr - qCoef * br;
            br += fCoef * hr;
            if (bl > 8) bl = 8;
            if (bl < -8) bl = -8;
            if (br > 8) br = 8;
            if (br < -8) br = -8;
            svf[0] = wl + 1e-20f;
            svf[1] = bl;
            svf[2] = wr + 1e-20f;
            svf[3] = br;
            float fl = fType == 0 ? wl : fType == 1 ? hl : bl;
            float fr = fType == 0 ? wr : fType == 1 ? hr : br;
            l += (fl - l) * filtEn;
            r += (fr - r) * filtEn;
        }

        float rp = dW - dTimeCur;
        if (rp < 0) rp += sr;
        int i0 = (int) rp;
        float fr = rp - i0;
        int i1 = i0 + 1 >= sr ? 0 : i0 + 1;
        float wetL = dL[i0] + (dL[i1] - dL[i0]) * fr;
        float wetR = dR[i0] + (dR[i1] - dR[i0]) * fr;
        dLpL += .32f * (wetL - dLpL);
        dLpR += .32f * (wetR - dLpR);
        dL[dW] = l + dLpL * dFb * .75f + dLpR * dFb * .25f;
        dR[dW] = r + dLpR * dFb * .75f + dLpL * dFb * .25f;
        dW++;
        if (dW >= sr) dW = 0;
        float dm = dMix * delEn;
        l += wetL * dm;
        r += wetR * dm;

        if (verbEn > .01f || vMix > .001f) {
            float in = (l + r) * .5f * .015f + 1e-20f;
            float accL = 0, accR = 0;
            for (int i = 0; i < 8; i++) {
                int base = CMB_OFF[i], t = combT[i], idx = combI[i];
                float yl = combBufL[base + idx];
                combFiltL[i] = yl * (1 - vDamp) + combFiltL[i] * vDamp;
                combBufL[base + idx] = in + combFiltL[i] * vFb;
                accL += yl;
                float yr = combBufR[base + idx];
                combFiltR[i] = yr * (1 - vDamp) + combFiltR[i] * vDamp;
                combBufR[base + idx] = in + combFiltR[i] * vFb;
                accR += yr;
                int ni = idx + 1;
                if (ni >= t) ni = 0;
                combI[i] = ni;
            }
            for (int i = 0; i < 4; i++) {
                int base = AP_OFF[i], t = apT[i], idx = apI[i];
                float bl = apBufL[base + idx];
                apBufL[base + idx] = accL + bl * .5f;
                accL = -accL + bl;
                float br = apBufR[base + idx];
                apBufR[base + idx] = accR + br * .5f;
                accR = -accR + br;
                int ni = idx + 1;
                if (ni >= t) ni = 0;
                apI[i] = ni;
            }
            float vm = vMix * verbEn;
            l += accL * vm * 3f;
            r += accR * vm * 3f;
        }

        out[0] = soft(l * masterCur * 1.1f);
        out[1] = soft(r * masterCur * 1.1f);
        return out;
    }
}
