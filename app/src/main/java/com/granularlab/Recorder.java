package com.granularlab;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import org.json.JSONArray;

/** Запись с микрофона планшета: живая волна для UI, автонормализация, сохранение. */
public class Recorder {

    private final Engine eng;
    private AudioRecord rec;
    private volatile boolean recording = false, cancelFlag = false;
    private float[] buf;
    private int pos = 0;

    public Recorder(Engine e) {
        eng = e;
    }

    public void start() {
        if (recording) return;
        cancelFlag = false;
        int min = AudioRecord.getMinBufferSize(Engine.SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = 4096;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, Engine.SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min * 2, 8192));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                rec = null;
                eng.evT("Микрофон недоступен (проверь RECORD_AUDIO)", 3);
                return;
            }
        } catch (Exception e) {
            eng.evT("Микрофон: " + e.getMessage(), 3);
            return;
        }
        buf = new float[Engine.SR * 30];
        pos = 0;
        eng.recCols = new int[0];
        eng.recVer = 0;
        eng.recT = 0;
        recording = true;
        eng.recOn = true;
        rec.startRecording();
        new Thread(new Runnable() {
            public void run() { loop(); }
        }, "GL-Rec").start();
        eng.evT("REC ● играй в микрофон (до 30 с)", 2);
    }

    private void loop() {
        short[] tmp = new short[1024];
        int[] cols = new int[0];
        while (recording && pos < buf.length) {
            int n = rec.read(tmp, 0, 1024);
            if (n <= 0) continue;
            float mn = 0, mx = 0;
            for (int i = 0; i < n && pos < buf.length; i++) {
                float v = tmp[i] / 32768f;
                buf[pos++] = v;
                if (v < mn) mn = v;
                if (v > mx) mx = v;
            }
            int[] nc = new int[cols.length + 2];
            System.arraycopy(cols, 0, nc, 0, cols.length);
            nc[cols.length] = (int) (mn * 1000);
            nc[cols.length + 1] = (int) (mx * 1000);
            cols = nc;
            eng.recCols = cols;
            eng.recVer++;
            eng.recT = pos / (float) Engine.SR;
        }
        try { rec.stop(); } catch (Exception ignored) { }
        try { rec.release(); } catch (Exception ignored) { }
        rec = null;
        recording = false;
        eng.recOn = false;

        if (cancelFlag || pos < 2048) {
            eng.evT(cancelFlag ? "Запись отменена" : "Слишком короткая запись", 2);
            eng.recCols = new int[0];
            eng.recVer = 0;
            return;
        }
        float[] s = new float[pos];
        System.arraycopy(buf, 0, s, 0, pos);
        Wav.normalize(s, .89f);
        eng.lastRec = s;
        eng.recHas = true;
        String name = "REC_" + Engine.stamp().substring(9);
        eng.setSample(s, name);
        eng.recCols = new int[0];
        eng.recVer = 0;
        JSONArray a = new JSONArray();
        a.put("rc");
        a.put(name);
        a.put(Engine.round2(pos / (float) Engine.SR));
        eng.ev(a);
    }

    public void stop() {
        recording = false;
    }

    public void cancel() {
        cancelFlag = true;
        recording = false;
    }

    public void save() {
        final float[] s = eng.lastRec;
        if (s == null) {
            eng.evT("Нет записи для сохранения", 3);
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    String p = Engine.outDir() + "/rec_" + Engine.stamp() + ".wav";
                    Wav.write(s, 1, p);
                    JSONArray a = new JSONArray();
                    a.put("sv");
                    a.put(p);
                    eng.ev(a);
                } catch (Exception e) {
                    eng.evT("Save rec: " + e.getMessage(), 3);
                }
            }
        }, "GL-SaveRec").start();
    }
}
