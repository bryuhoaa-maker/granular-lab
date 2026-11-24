package com.granularlab;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;

/** USB-MIDI host поверх android.hardware.usb (работает на API 16, без android.media.midi). */
public class UsbMidi {

    public static final String ACTION_PERM = "com.granularlab.USB_PERMISSION";

    private final Context ctx;
    private final Engine eng;
    private final UsbManager um;

    private UsbDevice device, pending;
    private UsbDeviceConnection conn;
    private UsbInterface iface;
    private UsbEndpoint epIn;
    private volatile boolean connected = false;
    public volatile int channel = 0;

    public UsbMidi(Context c, Engine e) {
        ctx = c.getApplicationContext();
        eng = e;
        um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
    }

    public String scan() {
        try {
            JSONArray arr = new JSONArray();
            HashMap<String, UsbDevice> list = um.getDeviceList();
            for (UsbDevice d : list.values()) {
                if (!looksLikeMidi(d)) continue;
                JSONObject o = new JSONObject();
                o.put("id", d.getDeviceId());
                o.put("name", devName(d));
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private String devName(UsbDevice d) {
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                String p = d.getProductName();
                if (p != null && p.length() > 0) return p;
            } catch (Exception ignored) {
            }
        }
        return String.format(Locale.US, "USB %04X:%04X", d.getVendorId(), d.getProductId());
    }

    private boolean looksLikeMidi(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface in = d.getInterface(i);
            if (in.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO
                    && in.getInterfaceSubclass() == 3) return true;
            if (in.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC
                    && hasBulkIn(in)) return true;
        }
        return false;
    }

    private boolean hasBulkIn(UsbInterface in) {
        for (int i = 0; i < in.getEndpointCount(); i++) {
            UsbEndpoint e = in.getEndpoint(i);
            if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && e.getDirection() == UsbConstants.USB_DIR_IN) return true;
        }
        return false;
    }

    public void connect(int deviceId) {
        UsbDevice target = null;
        for (UsbDevice d : um.getDeviceList().values()) {
            if (d.getDeviceId() == deviceId) {
                target = d;
                break;
            }
        }
        if (target == null) {
            eng.evT("Устройство не найдено — SCAN ещё раз", 3);
            return;
        }
        final UsbDevice d = target;
        if (!um.hasPermission(d)) {
            pending = d;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, new Intent(ACTION_PERM), 0);
            um.requestPermission(d, pi);
            return;
        }
        new Thread(new Runnable() {
            public void run() { open(d); }
        }, "GL-UsbOpen").start();
    }

    public void onPermission(boolean granted) {
        if (granted && pending != null) {
            final UsbDevice d = pending;
            pending = null;
            new Thread(new Runnable() {
                public void run() { open(d); }
            }, "GL-UsbOpen").start();
        } else {
            eng.evT("Доступ к USB не разрешён", 3);
        }
    }

    private void open(UsbDevice d) {
        try {
            disconnectSilent();
            UsbInterface best = null;
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface in = d.getInterface(i);
                if (in.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO
                        && in.getInterfaceSubclass() == 3 && hasBulkIn(in)) {
                    best = in;
                    break;
                }
                if (best == null && in.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC
                        && hasBulkIn(in)) {
                    best = in;
                }
            }
            if (best == null) {
                eng.evT("Нет MIDI-интерфейса на устройстве", 3);
                return;
            }
            UsbDeviceConnection c = um.openDevice(d);
            if (c == null) {
                eng.evT("Не открылось USB-устройство", 3);
                return;
            }
            if (!c.claimInterface(best, true)) {
                c.close();
                eng.evT("Не удалось захватить интерфейс", 3);
                return;
            }
            UsbEndpoint in = null;
            for (int i = 0; i < best.getEndpointCount(); i++) {
                UsbEndpoint e = best.getEndpoint(i);
                if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                        && e.getDirection() == UsbConstants.USB_DIR_IN) {
                    in = e;
                    break;
                }
            }
            if (in == null) {
                c.close();
                eng.evT("Нет bulk-IN endpoint", 3);
                return;
            }
            device = d;
            conn = c;
            iface = best;
            epIn = in;
            connected = true;
            eng.midiOn = true;
            eng.midiName = devName(d);
            ctx.getSharedPreferences("gl", 0).edit()
                    .putInt("usb_vid", d.getVendorId())
                    .putInt("usb_pid", d.getProductId()).apply();
            JSONArray a = new JSONArray();
            a.put("md");
            a.put(1);
            a.put(eng.midiName);
            eng.ev(a);
            eng.evT("MIDI подключен: " + eng.midiName, 1);
            new Thread(new Runnable() {
                public void run() { readLoop(); }
            }, "GL-UsbRead").start();
        } catch (Exception e) {
            eng.evT("USB open: " + e.getMessage(), 3);
        }
    }

    private void readLoop() {
        byte[] buf = new byte[64];
        while (connected) {
            UsbDeviceConnection c = conn;
            UsbEndpoint e = epIn;
            if (c == null || e == null) break;
            int n = c.bulkTransfer(e, buf, buf.length, 0);
            if (n < 0) break;
            if (n == 0) continue;
            eng.midiAct = System.currentTimeMillis();
            for (int i = 0; i + 3 < n; i += 4) {
                int cin = buf[i] & 0xF;
                int b1 = buf[i + 1] & 255;
                int b2 = buf[i + 2] & 255;
                int b3 = buf[i + 3] & 255;
                switch (cin) {
                    case 0x8:
                        noteMsg(false, b1, b2, 0);
                        break;
                    case 0x9:
                        noteMsg(true, b1, b2, b3);
                        break;
                    case 0xB:
                        if (chanOk(b1)) eng.onMidiCC(b2 & 127, b3 & 127);
                        break;
                    case 0xE:
                        if (chanOk(b1)) eng.onMidiBend((b2 & 127) | ((b3 & 127) << 7));
                        break;
                    case 0x5:
                    case 0xF:
                        realtime(b1);
                        break;
                    default:
                        break;
                }
            }
        }
        if (connected) {
            connected = false;
            eng.midiOn = false;
            JSONArray a = new JSONArray();
            a.put("md");
            a.put(0);
            a.put("");
            eng.ev(a);
        }
    }

    private void realtime(int b) {
        if (b == 0xF8) eng.onMidiClock();
        else if (b == 0xFA || b == 0xFB) eng.onMidiStart();
        else if (b == 0xFC) eng.onMidiStop();
    }

    private boolean chanOk(int status) {
        return channel == 0 || (status & 15) == channel - 1;
    }

    private void noteMsg(boolean on, int status, int note, int vel) {
        if (!chanOk(status)) return;
        if (on && vel > 0) eng.noteOn(note, vel);
        else eng.noteOff(note);
        JSONArray a = new JSONArray();
        a.put("n");
        a.put(note);
        a.put(on ? vel : 0);
        eng.ev(a);
    }

    public void disconnect() {
        disconnectSilent();
        eng.evT("MIDI отключен", 2);
    }

    private void disconnectSilent() {
        connected = false;
        eng.midiOn = false;
        UsbDeviceConnection c = conn;
        if (c != null) {
            try {
                if (iface != null) c.releaseInterface(iface);
            } catch (Exception ignored) {
            }
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
        conn = null;
        iface = null;
        epIn = null;
        device = null;
        JSONArray a = new JSONArray();
        a.put("md");
        a.put(0);
        a.put("");
        eng.ev(a);
    }

    public void onAttach(UsbDevice d) {
        if (d == null || !looksLikeMidi(d)) return;
        int vid = ctx.getSharedPreferences("gl", 0).getInt("usb_vid", -1);
        int pid = ctx.getSharedPreferences("gl", 0).getInt("usb_pid", -1);
        JSONArray a = new JSONArray();
        a.put("scan");
        eng.ev(a);
        if (d.getVendorId() == vid && d.getProductId() == pid && um.hasPermission(d)) {
            final UsbDevice dd = d;
            new Thread(new Runnable() {
                public void run() { open(dd); }
            }, "GL-UsbAuto").start();
        } else {
            eng.evT("USB MIDI обнаружен: " + devName(d) + " — открой MIDI → CONNECT", 0);
        }
    }

    public void onDetach(UsbDevice d) {
        if (device != null && d != null && d.getDeviceId() == device.getDeviceId()) {
            disconnectSilent();
            eng.evT("MIDI-устройство отсоединено", 2);
        }
    }
}
