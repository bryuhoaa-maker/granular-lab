package com.granularlab;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {

    private WebView web;
    private Engine eng;
    private UsbMidi usb;
    public volatile boolean overlayOpen = false;
    private boolean backToastShown = false;
    private AudioManager am;
    private BroadcastReceiver receiver;

    private final AudioManager.OnAudioFocusChangeListener focusL =
            new AudioManager.OnAudioFocusChangeListener() {
                public void onAudioFocusChange(int change) {
                    if (change == AudioManager.AUDIOFOCUS_LOSS) {
                        eng.setRun(false);
                        eng.evT("Аудиофокус потерян — RUN выключен", 2);
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        eng = Engine.get();
        eng.init(this);
        usb = new UsbMidi(this, eng);
        Recorder rec = new Recorder(eng);

        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.requestAudioFocus(focusL, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        startService(new Intent(this, SynthService.class));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setDefaultFontSize(16);
        s.setTextZoom(100);
        s.setAllowFileAccess(true);
        web.setBackgroundColor(0xFF0A0D14);
        web.addJavascriptInterface(new Bridge(this, eng, usb, rec), "Native");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                android.util.Log.d("GL-UI", cm.message() + " @" + cm.lineNumber());
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient());
        setContentView(web);
        web.loadUrl("file:///android_asset/ui/index.html");

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (UsbMidi.ACTION_PERM.equals(a)) {
                    usb.onPermission(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false));
                } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                    usb.onAttach((UsbDevice) i.getParcelableExtra(UsbManager.EXTRA_DEVICE));
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                    usb.onDetach((UsbDevice) i.getParcelableExtra(UsbManager.EXTRA_DEVICE));
                } else if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(a)) {
                    eng.setRun(false);
                    eng.evT("Наушники вынуты — RUN выключен", 2);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(UsbMidi.ACTION_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(receiver, f);

        if (getIntent() != null
                && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction())) {
            usb.onAttach((UsbDevice) getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            usb.onAttach((UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE));
        }
    }

    @Override
    public void onBackPressed() {
        if (overlayOpen) {
            web.loadUrl("javascript:window.GL&&GL.closeTop&&GL.closeTop()");
            return;
        }
        moveTaskToBack(true);
        if (!backToastShown) {
            backToastShown = true;
            Toast.makeText(this, "Синт играет в фоне. Убери уведомление — движок остановится.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        if (isFinishing()) {
            try { am.abandonAudioFocus(focusL); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }
}
