package com.granularlab;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

/** Мост между ES5-интерфейсом в WebView и нативным движком. */
public class Bridge {

    private final MainActivity act;
    private final Engine eng;
    private final UsbMidi usb;
    private final Recorder rec;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public Bridge(MainActivity a, Engine e, UsbMidi u, Recorder r) {
        act = a;
        eng = e;
        usb = u;
        rec = r;
    }

    @JavascriptInterface public String getState() { return eng.getState(); }
    @JavascriptInterface public String getWaveform() { return eng.getWaveform(); }
    @JavascriptInterface public void setParam(String id, double v) { eng.setParam(id, v); }
    @JavascriptInterface public void setRun(boolean b) { eng.setRun(b); }
    @JavascriptInterface public void setHold(boolean b) { eng.setHold(b); }
    @JavascriptInterface public void setMute(boolean b) { eng.setMute(b); }
    @JavascriptInterface public void noteOn(int n, int vel) { eng.noteOn(n, vel); }
    @JavascriptInterface public void noteOff(int n) { eng.noteOff(n); }

    @JavascriptInterface public void loadDemo(final int idx) {
        new Thread(new Runnable() {
            public void run() { eng.loadDemo(idx); }
        }, "GL-DemoBtn").start();
    }

    @JavascriptInterface public void openFiles() {
        ui.post(new Runnable() {
            public void run() {
                FileBrowser.show(act, new FileBrowser.Pick() {
                    public void onPick(String path) { eng.loadPath(path); }
                });
            }
        });
    }

    @JavascriptInterface public void startRec() { rec.start(); }
    @JavascriptInterface public void stopRec() { rec.stop(); }
    @JavascriptInterface public void cancelRec() { rec.cancel(); }
    @JavascriptInterface public void saveRec() { rec.save(); }
    @JavascriptInterface public void saveSample() { eng.saveSample(); }
    @JavascriptInterface public void exportWav() { eng.exportWav(); }
    @JavascriptInterface public String midiScan() { return usb.scan(); }
    @JavascriptInterface public void midiConnect(int id) { usb.connect(id); }
    @JavascriptInterface public void midiDisconnect() { usb.disconnect(); }

    @JavascriptInterface public void setMidiChannel(int ch) {
        usb.channel = ch;
        eng.midiCh = ch;
    }

    @JavascriptInterface public void learn(String param) {
        eng.learnParam = param;
        eng.evT("LEARN: двинь CC-ручку → " + param.toUpperCase(), 0);
    }

    @JavascriptInterface public void learnCancel() { eng.learnParam = null; }
    @JavascriptInterface public void panic() { eng.panic(); }
    @JavascriptInterface public void setOverlay(boolean b) { act.overlayOpen = b; }
    @JavascriptInterface public String version() { return "1.0.4"; }

    @JavascriptInterface public void toast(final String msg) {
        ui.post(new Runnable() {
            public void run() { Toast.makeText(act, msg, Toast.LENGTH_LONG).show(); }
        });
    }
}
