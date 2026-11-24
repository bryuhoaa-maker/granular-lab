package com.granularlab;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

/** Нативный тёмный браузер файлов по всем SD-разделам (WebView file input на 4.1 не работает). */
public class FileBrowser {

    public interface Pick {
        void onPick(String path);
    }

    private static final String[] EXT = {".wav", ".mp3", ".ogg", ".flac", ".m4a", ".aac", ".3gp"};
    private static final int BG = 0xFF0E1424, PANEL = 0xFF131A2C, LINE = 0xFF232E4E,
            TXT = 0xFFE9E4D6, DIM = 0xFF8B93A8, AMBER = 0xFFFFAE1F, CYAN = 0xFF2BD9FF;

    public static void show(final Context ctx, final Pick pick) {
        final Dialog dlg = new Dialog(ctx, android.R.style.Theme_Holo_Dialog_NoActionBar);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(ctx, 14);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("SAMPLE BROWSER · SD");
        title.setTextColor(AMBER);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(15);
        root.addView(title);

        final ArrayList<File> roots = storageRoots();
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        LinearLayout chipRow = new LinearLayout(ctx);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(chipRow);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(ctx, 10);
        hp.bottomMargin = dp(ctx, 8);
        root.addView(hs, hp);

        final TextView pathView = new TextView(ctx);
        pathView.setTextColor(CYAN);
        pathView.setTypeface(Typeface.MONOSPACE);
        pathView.setTextSize(11);
        pathView.setSingleLine(true);
        root.addView(pathView);

        final ListView list = new ListView(ctx);
        list.setDivider(new ColorDrawable(LINE));
        list.setDividerHeight(1);
        list.setCacheColorHint(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
        lp.topMargin = dp(ctx, 8);
        root.addView(list, lp);

        final TextView empty = new TextView(ctx);
        empty.setText("Папка пуста. Ищи .wav / .mp3 / .ogg / .flac");
        empty.setTextColor(DIM);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(ctx, 24), 0, dp(ctx, 24));
        empty.setVisibility(View.GONE);
        root.addView(empty);

        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(ctx, 10);
        root.addView(btnRow, bp);

        Button up = mkBtn(ctx, "⬆ UP");
        Button close = mkBtn(ctx, "✕ ЗАКРЫТЬ");
        btnRow.addView(up, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
        cp.leftMargin = dp(ctx, 8);
        btnRow.addView(close, cp);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });

        final File[] cur = {roots.size() > 0 ? roots.get(0) : new File("/")};
        final ArrayList<File> items = new ArrayList<File>();

        final BaseAdapter adapter = new BaseAdapter() {
            public int getCount() { return items.size(); }
            public Object getItem(int i) { return items.get(i); }
            public long getItemId(int i) { return i; }

            public View getView(int i, View cv, ViewGroup parent) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(ctx, 6), dp(ctx, 11), dp(ctx, 6), dp(ctx, 11));
                File f = items.get(i);
                boolean dir = f.isDirectory();

                TextView ic = new TextView(ctx);
                ic.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                ic.setTextSize(13);
                ic.setText(dir ? "▸" : "♪");
                ic.setTextColor(dir ? CYAN : AMBER);
                ic.setPadding(0, 0, dp(ctx, 10), 0);
                row.addView(ic);

                TextView name = new TextView(ctx);
                name.setText(f.getName());
                name.setTextColor(dir ? TXT : 0xFFD5DAE8);
                name.setTextSize(14);
                name.setSingleLine(true);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

                TextView meta = new TextView(ctx);
                meta.setTextColor(DIM);
                meta.setTextSize(11);
                meta.setTypeface(Typeface.MONOSPACE);
                meta.setText(dir ? "DIR" : String.format(Locale.US, "%.0fK", f.length() / 1024f));
                row.addView(meta);
                return row;
            }
        };
        list.setAdapter(adapter);

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = new Runnable() {
            public void run() {
                items.clear();
                File d = cur[0];
                pathView.setText(d.getAbsolutePath());
                File[] fs = d.listFiles();
                ArrayList<File> dirs = new ArrayList<File>();
                ArrayList<File> files = new ArrayList<File>();
                if (fs != null) {
                    for (File f : fs) {
                        if (f.getName().startsWith(".")) continue;
                        if (f.isDirectory() && f.canRead()) dirs.add(f);
                        else if (f.isFile() && isAudio(f.getName())) files.add(f);
                    }
                }
                Comparator<File> cmp = new Comparator<File>() {
                    public int compare(File a, File b) {
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                };
                Collections.sort(dirs, cmp);
                Collections.sort(files, cmp);
                items.addAll(dirs);
                items.addAll(files);
                adapter.notifyDataSetChanged();
                empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            }
        };

        for (int i = 0; i < roots.size(); i++) {
            final File r = roots.get(i);
            Button chip = mkBtn(ctx, rootLabel(r));
            chip.setTextSize(11);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    cur[0] = r;
                    refresh[0].run();
                }
            });
            LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(-2, -2);
            chp.rightMargin = dp(ctx, 6);
            chipRow.addView(chip, chp);
        }

        up.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                File p = cur[0].getParentFile();
                if (p != null && p.canRead()) {
                    cur[0] = p;
                    refresh[0].run();
                }
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View v, int i, long id) {
                File f = items.get(i);
                if (f.isDirectory()) {
                    cur[0] = f;
                    refresh[0].run();
                } else {
                    dlg.dismiss();
                    pick.onPick(f.getAbsolutePath());
                }
            }
        });

        refresh[0].run();
        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(BG));
            int sw = ctx.getResources().getDisplayMetrics().widthPixels;
            int sh = ctx.getResources().getDisplayMetrics().heightPixels;
            w.setLayout(Math.min(sw - dp(ctx, 24), dp(ctx, 560)), sh - dp(ctx, 90));
        }
        dlg.show();
    }

    private static boolean isAudio(String n) {
        String l = n.toLowerCase(Locale.US);
        for (String e : EXT) if (l.endsWith(e)) return true;
        return false;
    }

    private static ArrayList<File> storageRoots() {
        ArrayList<File> out = new ArrayList<File>();
        ArrayList<String> seen = new ArrayList<String>();
        addRoot(out, seen, Environment.getExternalStorageDirectory());
        String[] cands = {"/storage/extSdCard", "/storage/sdcard1", "/storage/external_SD",
                "/mnt/extSdCard", "/mnt/sdcard/extSdCard", "/Removable/MicroSD",
                "/storage/sdcard0", "/mnt/sdcard"};
        for (String c : cands) addRoot(out, seen, new File(c));
        if (out.isEmpty()) out.add(new File("/"));
        return out;
    }

    private static void addRoot(ArrayList<File> out, ArrayList<String> seen, File f) {
        try {
            if (f == null || !f.exists() || !f.canRead()) return;
            String key = f.getCanonicalPath();
            if (seen.contains(key)) return;
            seen.add(key);
            out.add(f);
        } catch (Exception ignored) {
        }
    }

    private static String rootLabel(File f) {
        String p = f.getAbsolutePath();
        return (p.contains("ext") || p.contains("sdcard1")
                || p.contains("MicroSD") || p.contains("external")) ? "EXT SD" : "INT SD";
    }

    private static Button mkBtn(Context ctx, String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextColor(TXT);
        b.setTextSize(13);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setAllCaps(false);
        GradientDrawable d = new GradientDrawable();
        d.setColor(PANEL);
        d.setStroke(dp(ctx, 1), LINE);
        d.setCornerRadius(dp(ctx, 8));
        b.setBackgroundDrawable(d);
        b.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
        return b;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + .5f);
    }
}
