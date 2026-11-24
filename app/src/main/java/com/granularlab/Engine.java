package com.granularlab;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Environment;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Гранулярный движок: пул из 32 гранул, планировщик плотности,
 * скан позиции, транспонирование по последней ноте, FX-цепь,
 * осциллограф, метр, офлайн-рендер и MIDI-карта.
 */
public class Engine {

    public static final int SR = 44100;
    static final int FRAMES = 512;
    static final int MAXG = 32;

    private static Engine inst;

    public static synchronized Engine get() {
        if (inst == null) inst = new Engine();
        return inst;
    }

    /** Параметр с целевым и сглаженным значением. */
    public static class P {
        public final float min, max, def;
        public volatile float v;
        float cur;

        P(float mn, float mx, float d) {
            min = mn;
            max = mx;
            def = d;
            v = d;
            cur = d;
        }
    }

    public final Map<String, P> params = new HashMap<String, P>();

    private void def(String id, float mn, float mx, float d) {
        params.put(id, new P(mn, mx, d));
    }

    public P param(String id) {
        return params.get(id);
    }

    public void setParam(String id, double val) {
        P p = params.get(id);
        if (p == null) return;
        float v = (float) Math.max(p.min, Math.min(p.max, val));
        if (id.equals("dir") || id.equals("shape") || id.equals("ftype")
                || id.equals("dDiv") || id.equals("loop") || id.equals("dSync")
                || id.equals("crBits") || id.equals("fxCrush") || id.equals("fxFilter")
                || id.equals("fxDelay") || id.equals("fxVerb")
                || id.equals("density") || id.equals("pitch") || id.equals("bpm")) {
            v = Math.round(v);
        }
        p.v = v;
        if (id.equals("pos")) posReq = v;
    }

    public volatile boolean run = true, hold = true, mute = false;

    public void setRun(boolean b) { run = b; }
    public void setHold(boolean b) { hold = b; }
    public void setMute(boolean b) { mute = b; }

    volatile float[] samp = new float[0];
    volatile String sampName = "—";
    volatile float sampLen = 0;
    volatile int sampVer = 0;
    volatile int[] waveCols = new int[1280];
    volatile double scanPos = 0.35;
    volatile float posReq = -1;

    public volatile boolean rendering = false;
    private final Object renderLock = new Object();

    public volatile boolean recOn = false, recHas = false;
    public volatile float recT = 0;
    volatile int[] recCols = new int[0];
    volatile int recVer = 0;
    public volatile float[] lastRec = null;

    public volatile boolean midiOn = false;
    public volatile String midiName = "";
    public volatile int midiCh = 0;
    public volatile long midiAct = 0;
    public volatile float clockBpm = 0;
    public volatile long clockAt = 0;
    public volatile String learnParam = null;

    public final Map<Integer, String> ccMap = new HashMap<Integer, String>();
    private SharedPreferences prefs;

    private final ArrayDeque<Integer> notes = new ArrayDeque<Integer>();
    public volatile float bend = 0;

    public void noteOn(int n, int vel) {
        if (vel <= 0) {
            noteOff(n);
            return;
        }
        synchronized (notes) {
            notes.remove(n);
            notes.addLast(n);
        }
    }

    public void noteOff(int n) {
        synchronized (notes) {
            notes.remove(n);
        }
    }

    public void panic() {
        synchronized (notes) {
            notes.clear();
        }
        for (Grain g : pool) g.on = false;
        bend = 0;
        evT("PANIC — все голоса сброшены", 1);
    }

    static class Grain {
        boolean on;
        double pos, inc;
        int life, age;
        float gL, gR, pan;
    }

    final Grain[] pool = new Grain[MAXG];
    private final Fx fx = new Fx(SR);
    private final float[] scopeRing = new float[1024];
    private volatile int scopeIdx = 0;
    public volatile float mL = 0, mR = 0;

    private final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<String>();
    private AudioTrack track;
    private volatile boolean alive = false;

    private int rs = 0x1F2E3D4C;

    private float rnd() {
        rs ^= rs << 13;
        rs ^= rs >>> 17;
        rs ^= rs << 5;
        return (rs & 0x7FFFFFFF) / 2147483647f;
    }

    private static final float[] HANN = new float[1024];
    private static final float[] TUKEY = new float[1024];
    private static final float[] GATE = new float[1024];

    static {
        for (int i = 0; i < 1024; i++) {
            double ph = i / 1023.0;
            HANN[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * ph));
            double e = 0.15;
            TUKEY[i] = (float) (ph < e
                    ? 0.5 - 0.5 * Math.cos(Math.PI * ph / e)
                    : (ph > 1 - e ? 0.5 - 0.5 * Math.cos(Math.PI * (1 - ph) / e) : 1));
            double g = 0.04;
            GATE[i] = (float) (ph < g ? ph / g : (ph > 1 - g ? (1 - ph) / g : 1));
        }
    }

    private Engine() {
        for (int i = 0; i < MAXG; i++) pool[i] = new Grain();

        def("pos", 0, 1, .35f);
        def("spread", 0, .5f, .08f);
        def("size", 10, 500, 180);
        def("density", 1, 80, 22);
        def("pitch", -24, 24, 0);
        def("scan", -2, 2, .12f);
        def("pan", 0, 1, .55f);
        def("level", 0, 1, .8f);

        def("dir", 0, 2, 0);
        def("shape", 0, 2, 0);
        def("loop", 0, 1, 1);

        def("cutoff", 40, 12000, 6500);
        def("res", 0, .95f, .18f);
        def("ftype", 0, 2, 0);

        def("crDrive", 0, 1, .35f);
        def("crBits", 2, 16, 12);
        def("crRate", .05f, 1, 1);

        def("dTime", 20, 900, 375);
        def("dFb", 0, .9f, .38f);
        def("dMix", 0, 1, .24f);
        def("dSync", 0, 1, 0);
        def("dDiv", 0, 3, 1);

        def("rSize", 0, 1, .72f);
        def("rDamp", 0, 1, .45f);
        def("rMix", 0, 1, .3f);

        def("master", 0, 1, .8f);
        def("bpm", 60, 200, 120);

        def("fxCrush", 0, 1, 0);
        def("fxFilter", 0, 1, 1);
        def("fxDelay", 0, 1, 1);
        def("fxVerb", 0, 1, 1);

        ccMap.put(1, "spread");
        ccMap.put(7, "master");
        ccMap.put(10, "pan");
        ccMap.put(11, "density");
        ccMap.put(74, "cutoff");
        ccMap.put(71, "res");
        ccMap.put(91, "rMix");
        ccMap.put(93, "dMix");
    }

    public void init(Context ctx) {
        prefs = ctx.getSharedPreferences("gl", 0);
        try {
            String j = prefs.getString("ccmap", null);
            if (j != null) {
                JSONObject o = new JSONObject(j);
                java.util.Iterator<?> it = o.keys();
                while (it.hasNext()) {
                    String k = (String) it.next();
                    ccMap.put(Integer.parseInt(k), o.getString(k));
                }
            }
        } catch (Exception ignored) {
        }

        if (alive) return;

        int min = AudioTrack.getMinBufferSize(SR,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = 8192;

        try {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, SR,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min * 2, 16384), AudioTrack.MODE_STREAM);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                evT("AudioTrack init fail", 3);
                return;
            }
            track.play();
        } catch (Exception e) {
            evT("Аудио недоступно: " + e.getMessage(), 3);
            return;
        }

        alive = true;
        new Thread(new Runnable() {
            public void run() { audioLoop(); }
        }, "GL-Audio").start();

        new Thread(new Runnable() {
            public void run() { loadDemo(0); }
        }, "GL-Demo").start();
    }

    public void stop() {
        alive = false;
        try {
            if (track != null) {
                track.pause();
                track.flush();
                track.release();
            }
        } catch (Exception ignored) {
        }
        track = null;
    }

    private void audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        short[] buf = new short[FRAMES * 2];
        while (alive) {
            if (rendering) {
                Arrays.fill(buf, (short) 0);
            } else {
                processBlock(buf, false);
            }
            AudioTrack t = track;
            if (t == null) break;
            int w = t.write(buf, 0, buf.length);
            if (w < 0) break;
        }
    }

    private float gateEnv = 0, transposeCur = 0, spawnAcc = 0;
    private static final float GA = 1 - (float) Math.exp(-1.0 / (SR * 0.012));
    private static final float GR = 1 - (float) Math.exp(-1.0 / (SR * 0.25));

    void processBlock(short[] out, boolean offline) {
        float[] s = samp;
        int len = s.length;
        boolean loop = param("loop").v > .5f;

        float pr = posReq;
        if (pr >= 0) {
            scanPos = pr;
            posReq = -1;
        }

        for (P p : params.values()) p.cur += (p.v - p.cur) * 0.25f;

        float size = param("size").cur;
        float density = param("density").cur;
        float spread = param("spread").cur;
        float scan = param("scan").cur;
        float panS = param("pan").cur;
        float level = param("level").cur;
        int dirMode = (int) param("dir").cur;
        int shape = (int) param("shape").cur;
        float masterT = mute ? 0 : param("master").cur;

        int lastNote;
        synchronized (notes) {
            lastNote = notes.isEmpty() ? 60 : notes.peekLast();
        }
        float trT = (lastNote - 60) + bend;
        boolean gateOn = offline || (run && (hold || !notes.isEmpty()));
        float[] win = shape == 1 ? TUKEY : (shape == 2 ? GATE : HANN);
        double lenSec = len > 0 ? len / (double) SR : 1;

        fx.beginBlock(params, effectiveBpm(), masterT);
        float pkL = 0, pkR = 0;

        for (int f = 0; f < FRAMES; f++) {
            transposeCur += (trT - transposeCur) * 0.004f;
            gateEnv += ((gateOn ? 1f : 0f) - gateEnv) * (gateOn ? GA : GR);

            if (len > 0) {
                scanPos += scan / (SR * lenSec);
                if (loop) {
                    scanPos -= Math.floor(scanPos);
                } else {
                    scanPos = Math.max(0, Math.min(1, scanPos));
                }
                spawnAcc += density / SR;
                while (spawnAcc >= 1f) {
                    spawnAcc -= 1f;
                    if (gateEnv > 0.02f && len > 4) {
                        double ratio = Math.pow(2, (param("pitch").cur + transposeCur) / 12.0);
                        spawnGrain(len, loop, size, spread, panS, dirMode, ratio);
                    }
                }
            }

            float l = 0, r = 0;
            for (int g = 0; g < MAXG; g++) {
                Grain gr = pool[g];
                if (!gr.on) continue;
                int idx = (int) gr.pos;
                if (idx < 0 || idx >= len - 1) {
                    gr.on = false;
                    continue;
                }
                float frac = (float) (gr.pos - idx);
                float v = s[idx] + (s[idx + 1] - s[idx]) * frac;
                float w = win[(int) (gr.age * 1023f / gr.life)];
                l += v * w * gr.gL;
                r += v * w * gr.gR;
                gr.pos += gr.inc;
                gr.age++;
                if (gr.age >= gr.life) gr.on = false;
            }
            l *= gateEnv * level;
            r *= gateEnv * level;

            float[] st = fx.process(l, r);
            l = st[0];
            r = st[1];

            float al = Math.abs(l), ar = Math.abs(r);
            if (al > pkL) pkL = al;
            if (ar > pkR) pkR = ar;

            if ((f & 1) == 0) {
                scopeRing[scopeIdx & 1023] = (l + r) * .5f;
                scopeIdx++;
            }
            out[f * 2] = (short) (Math.max(-1, Math.min(1, l)) * 32767);
            out[f * 2 + 1] = (short) (Math.max(-1, Math.min(1, r)) * 32767);
        }
        mL = Math.max(mL * .9f, pkL);
        mR = Math.max(mR * .9f, pkR);
    }

    private void spawnGrain(int len, boolean loop, float sizeMs,
                            float spread, float panS, int dirMode, double ratio) {
        Grain gr = null;
        for (int g = 0; g < MAXG; g++) {
            if (!pool[g].on) {
                gr = pool[g];
                break;
            }
        }
        if (gr == null) return;

        double p = scanPos * len + (rnd() * 2 - 1) * spread * len;
        if (loop) {
            p %= len;
            if (p < 0) p += len;
        } else {
            p = Math.max(0, Math.min(len - 2, p));
        }
        boolean rev = dirMode == 1 || (dirMode == 2 && rnd() < .5f);
        gr.pos = p;
        gr.inc = ratio * (rev ? -1 : 1);
        gr.life = Math.max(64, (int) (sizeMs * 0.001 * SR * (0.85 + 0.3 * rnd())));
        gr.age = 0;
        float pan = (rnd() * 2 - 1) * panS;
        double ang = (pan + 1) * Math.PI / 4;
        gr.gL = (float) Math.cos(ang) * .85f;
        gr.gR = (float) Math.sin(ang) * .85f;
        gr.pan = pan;
        gr.on = true;
    }

    public void setSample(float[] s, String name) {
        if (s == null || s.length < 64) {
            evT("Сэмпл пустой или повреждён", 3);
            return;
        }
        if (s.length > 60 * SR) {
            float[] t = new float[60 * SR];
            System.arraycopy(s, 0, t, 0, t.length);
            s = t;
            evT("Обрезано до 60 с", 2);
        }
        Wav.normalize(s, .89f);
        samp = s;
        sampName = name;
        sampLen = s.length / (float) SR;
        sampVer++;

        int[] cols = new int[1280];
        for (int c = 0; c < 640; c++) {
            int a = (int) ((long) c * s.length / 640);
            int b = (int) ((long) (c + 1) * s.length / 640);
            int step = Math.max(1, (b - a) / 48);
            float mn = 0, mx = 0;
            for (int i = a; i < b && i < s.length; i += step) {
                float v = s[i];
                if (v < mn) mn = v;
                if (v > mx) mx = v;
            }
            cols[c * 2] = (int) (mn * 1000);
            cols[c * 2 + 1] = (int) (mx * 1000);
        }
        waveCols = cols;
        scanPos = param("pos").v;

        JSONArray a = new JSONArray();
        a.put("ld");
        a.put(name);
        a.put(round2(sampLen));
        ev(a);
    }

    public void loadDemo(int idx) {
        try {
            setSample(DemoBank.generate(idx), DemoBank.NAMES[idx % DemoBank.NAMES.length]);
        } catch (Exception e) {
            evT("Demo error: " + e.getMessage(), 3);
        }
    }

    public void loadPath(final String path) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String low = path.toLowerCase(Locale.US);
                    float[] s = low.endsWith(".wav") ? Wav.read(path) : Decoder.decode(path);
                    String name = new File(path).getName();
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) name = name.substring(0, dot);
                    setSample(s, name.toUpperCase(Locale.US));
                } catch (Exception e) {
                    evT("Не открылось: " + e.getMessage(), 3);
                }
            }
        }, "GL-Load").start();
    }

    public void saveSample() {
        final float[] s = samp;
        final String n = sampName;
        if (s.length == 0) {
            evT("Нет сэмпла", 3);
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    String p = outDir() + "/sample_" + stamp() + "_"
                            + n.replaceAll("[^A-Za-z0-9]+", "_") + ".wav";
                    Wav.write(s, 1, p);
                    JSONArray a = new JSONArray();
                    a.put("sv");
                    a.put(p);
                    ev(a);
                } catch (Exception e) {
                    evT("Save error: " + e.getMessage(), 3);
                }
            }
        }, "GL-Save").start();
    }

    public void exportWav() {
        if (rendering) return;
        new Thread(new Runnable() {
            public void run() {
                synchronized (renderLock) {
                    rendering = true;
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException ignored) {
                    }
                    double savedPos = scanPos;
                    for (Grain g : pool) g.on = false;
                    spawnAcc = 0;

                    int total = 8 * SR;
                    float[] out = new float[total * 2];
                    short[] buf = new short[FRAMES * 2];
                    int done = 0;
                    while (done < total) {
                        processBlock(buf, true);
                        int n = Math.min(FRAMES, total - done);
                        for (int i = 0; i < n * 2; i++) {
                            out[done * 2 + i] = buf[i] / 32768f;
                        }
                        done += n;
                    }
                    for (Grain g : pool) g.on = false;
                    scanPos = savedPos;
                    rendering = false;

                    try {
                        String p = outDir() + "/render_" + stamp() + ".wav";
                        Wav.write(out, 2, p);
                        JSONArray a = new JSONArray();
                        a.put("ex");
                        a.put(p);
                        ev(a);
                    } catch (Exception e) {
                        evT("Export error: " + e.getMessage(), 3);
                    }
                }
            }
        }, "GL-Render").start();
    }

    static String outDir() {
        File d = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC), "GranularLab");
        if (!d.exists()) d.mkdirs();
        return d.getAbsolutePath();
    }

    static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    public float effectiveBpm() {
        if (clockBpm > 0 && System.currentTimeMillis() - clockAt < 2000) return clockBpm;
        return param("bpm").v;
    }

    public void onMidiCC(int cc, int val) {
        JSONArray a = new JSONArray();
        a.put("cc");
        a.put(cc);
        a.put(val);
        ev(a);

        String lp = learnParam;
        if (lp != null) {
            ccMap.put(cc, lp);
            learnParam = null;
            saveCC();
            JSONArray l = new JSONArray();
            l.put("ln");
            l.put(lp);
            l.put(cc);
            ev(l);
            return;
        }
        if (cc == 64) {
            setHold(val >= 64);
            return;
        }
        String id = ccMap.get(cc);
        if (id != null) {
            P p = params.get(id);
            if (p != null) setParam(id, p.min + (p.max - p.min) * (val / 127.0));
        }
    }

    public void onMidiBend(int v14) {
        bend = ((v14 - 8192) / 8192f) * 2f;
    }

    public void onMidiClock() {
        long now = System.nanoTime();
        if (clockLast > 0) {
            double dt = (now - clockLast) / 1e9;
            if (dt > 0.001 && dt < 0.2) {
                clockRing[clockRi & 15] = dt;
                clockRi++;
                int n = Math.min(16, clockRi);
                double sum = 0;
                for (int i = 0; i < n; i++) sum += clockRing[i];
                clockBpm = (float) (60.0 / (sum / n * 24.0));
                clockAt = System.currentTimeMillis();
            }
        }
        clockLast = now;
    }

    private long clockLast = 0;
    private final double[] clockRing = new double[16];
    private int clockRi = 0;

    public void onMidiStart() {
        setRun(true);
        evT("MIDI START → RUN", 1);
    }

    public void onMidiStop() {
        setRun(false);
        evT("MIDI STOP", 2);
    }

    private void saveCC() {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<Integer, String> e : ccMap.entrySet()) {
                o.put(String.valueOf(e.getKey()), e.getValue());
            }
            prefs.edit().putString("ccmap", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public void evT(String msg, int kind) {
        JSONArray a = new JSONArray();
        a.put("t");
        a.put(msg);
        a.put(kind);
        ev(a);
    }

    public void ev(JSONArray a) {
        events.add(a.toString());
        if (events.size() > 200) events.clear();
    }

    public String getState() {
        try {
            JSONObject o = new JSONObject();
            o.put("run", run ? 1 : 0);
            o.put("hold", hold ? 1 : 0);
            o.put("mute", mute ? 1 : 0);
            o.put("pos", Math.round(scanPos * 1000) / 1000.0);

            int gc = 0;
            JSONArray gr = new JSONArray();
            int len = Math.max(1, samp.length);
            for (Grain g : pool) {
                if (g.on) {
                    gc++;
                    if (gr.length() < 24) {
                        JSONArray t = new JSONArray();
                        t.put((int) (g.pos / len * 1000));
                        t.put((int) (g.age * 1000f / g.life));
                        t.put((int) (g.pan * 1000));
                        gr.put(t);
                    }
                }
            }
            o.put("gcount", gc);
            o.put("grains", gr);

            JSONArray m = new JSONArray();
            m.put(round2(mL));
            m.put(round2(mR));
            o.put("meter", m);

            boolean clk = clockBpm > 0 && System.currentTimeMillis() - clockAt < 2000;
            o.put("bpm", Math.round(effectiveBpm() * 10) / 10.0);
            o.put("clk", clk ? 1 : 0);

            JSONObject md = new JSONObject();
            md.put("on", midiOn ? 1 : 0);
            md.put("name", midiName);
            md.put("ch", midiCh);
            md.put("act", midiAct == 0 ? 999999 : System.currentTimeMillis() - midiAct);
            o.put("midi", md);

            JSONObject rc = new JSONObject();
            rc.put("on", recOn ? 1 : 0);
            rc.put("t", round2(recT));
            rc.put("has", recHas ? 1 : 0);
            o.put("rec", rc);

            JSONObject sm = new JSONObject();
            sm.put("name", sampName);
            sm.put("len", round2(sampLen));
            sm.put("ver", sampVer);
            o.put("sample", sm);

            o.put("learn", learnParam == null ? JSONObject.NULL : learnParam);
            o.put("rendering", rendering ? 1 : 0);

            JSONArray sc = new JSONArray();
            int si = scopeIdx;
            for (int k = 0; k < 192; k++) {
                float v = scopeRing[(si - 384 + k * 2 + 4096) & 1023];
                sc.put((int) (Math.max(-1, Math.min(1, v)) * 1000));
            }
            o.put("scope", sc);

            JSONObject pp = new JSONObject();
            for (Map.Entry<String, P> e : params.entrySet()) {
                if (!e.getKey().equals("pos")) {
                    pp.put(e.getKey(), Math.round(e.getValue().v * 1000) / 1000.0);
                }
            }
            o.put("p", pp);

            JSONArray evs = new JSONArray();
            for (int i = 0; i < 32; i++) {
                String e = events.poll();
                if (e == null) break;
                evs.put(new JSONArray(e));
            }
            o.put("ev", evs);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public String getWaveform() {
        try {
            JSONObject o = new JSONObject();
            if (recOn) {
                o.put("ver", 100000 + recVer);
                o.put("len", round2(recT));
                JSONArray c = new JSONArray();
                int[] rc = recCols;
                for (int i = 0; i < rc.length; i++) c.put(rc[i]);
                o.put("cols", c);
            } else {
                o.put("ver", sampVer);
                o.put("len", round2(sampLen));
                JSONArray c = new JSONArray();
                int[] wc = waveCols;
                for (int i = 0; i < wc.length; i++) c.put(wc[i]);
                o.put("cols", c);
            }
            return o.toString();
        } catch (Exception e) {
            return "{\"ver\":0,\"len\":0,\"cols\":[]}";
        }
    }
}
