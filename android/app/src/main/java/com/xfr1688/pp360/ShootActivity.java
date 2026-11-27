package com.xfr1688.pp360;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 拍摄页（独立 Activity，全屏取景）。
 *
 * 模式：拍照 / 定时 / 全景（拍完自动拼成一张）/ 抓拍（连续确认后停转再对焦拍）。
 * 相册支持多选删除、多选导出到 Pictures/PP360。
 */
public class ShootActivity extends Activity {

    private static final int MODE_SHOT = 0;
    private static final int MODE_TIMER = 1;
    private static final int MODE_PANO = 2;
    private static final int MODE_TRACK = 3;
    private static final int REQ_CAM = 2001;
    private static final int PANO_TOTAL = 18;
    private static final int HIT_NEED = 3;

    private static final int C_BG = 0xFF05060A;
    private static final int C_CARD = 0xFF12151B;
    private static final int C_LINE = 0x1FFFFFFF;
    private static final int C_TEXT = 0xFFF2F4F7;
    private static final int C_DIM = 0xFF868C96;
    private static final int C_GOLD = 0xFFD8B25E;
    private static final int C_BLUE = 0xFF5AC8FA;
    private static final int C_RED = 0xFFFF6B5E;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextureView tex;
    private Overlay overlay;
    private TextView tvTop, tvHint, tvParam, tvLast, btnRun;
    private ImageView ivThumb;
    private final TextView[] chips = new TextView[4];

    private FrameLayout galleryLayer;
    private GridView grid;
    private TextView tvGalTitle, btnSelect, btnSelectAll, btnDel, btnExport, tvGalTip;
    private LinearLayout selBar;
    private PhotoAdapter adapter;

    private boolean selecting = false;
    private final Set<String> sel = new LinkedHashSet<String>();

    private CameraController cam;
    private int mode = MODE_SHOT;
    private int intervalMs = 5000;
    private int plan = 12;
    private int done = 0;
    private int hitStreak = 0;
    private boolean running = false;
    private boolean photoBusy = false;
    private boolean panoCollect = false;
    private final List<String> panoPaths = new ArrayList<String>();

    /* ====================== 生命周期 ====================== */

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        try {
            getWindow().setStatusBarColor(C_BG);
        } catch (Exception ignored) {
        }
        if (!hasCam()) {
            tvTop.setText("等待相机权限…");
            tvTop.setTextColor(C_GOLD);
            requestPermissions(new String[]{"android.permission.CAMERA"}, REQ_CAM);
        }
        refreshThumb();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasCam()) {
            openCam();
        }
    }

    @Override
    protected void onPause() {
        stopRun();
        if (cam != null) {
            cam.detach();
            cam = null;
        }
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (galleryLayer != null && galleryLayer.getVisibility() == View.VISIBLE) {
            if (selecting) {
                selecting = false;
                sel.clear();
                updateSelUi();
                return;
            }
            galleryLayer.setVisibility(View.GONE);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_CAM) {
            if (hasCam()) {
                openCam();
            } else {
                tvTop.setText("没有相机权限，无法拍摄");
                tvTop.setTextColor(C_RED);
            }
        }
    }

    private boolean hasCam() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission("android.permission.CAMERA") == PackageManager.PERMISSION_GRANTED;
    }

    private void openCam() {
        if (cam != null) {
            return;
        }
        cam = new CameraController(this, camCb);
        cam.attach(tex);
        tvTop.setText("相机启动中…");
        tvTop.setTextColor(C_DIM);
    }

    /* ====================== 基础工具 ====================== */

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable round(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            d.setStroke(dp(strokeDp), stroke);
        }
        return d;
    }

    private GradientDrawable oval(int fill, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        if (strokeDp > 0) {
            d.setStroke(dp(strokeDp), stroke);
        }
        return d;
    }

    private TextView tv(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (bold) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private File shotDir() {
        File d = getExternalFilesDir("shots");
        if (d == null) {
            d = new File(getFilesDir(), "shots");
        }
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    private String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    /* ====================== 界面 ====================== */

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(C_BG);
        setContentView(root);

        FrameLayout frame = new FrameLayout(this);
        root.addView(frame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tex = new TextureView(this);
        frame.addView(tex, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new Overlay(this);
        frame.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(0xC8000000);
        top.setPadding(dp(16), dp(14), dp(16), dp(12));
        root.addView(top, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout r1 = row();
        TextView back = tv("‹ 返回", 13, C_BLUE, false);
        back.setPadding(dp(2), dp(4), dp(14), dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        r1.addView(back);

        View sp = new View(this);
        r1.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        tvTop = tv("相机启动中…", 11, C_DIM, false);
        r1.addView(tvTop);
        top.addView(r1);

        LinearLayout chipsRow = row();
        LinearLayout.LayoutParams crlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crlp.topMargin = dp(12);
        chipsRow.setLayoutParams(crlp);
        String[] names = {"拍照", "定时", "全景", "抓拍"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView c = tv(names[i], 12, C_TEXT, false);
            c.setGravity(Gravity.CENTER);
            c.setPadding(0, dp(9), 0, dp(9));
            LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            xp.rightMargin = i < 3 ? dp(8) : 0;
            c.setLayoutParams(xp);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setMode(idx);
                }
            });
            chips[i] = c;
            chipsRow.addView(c);
        }
        top.addView(chipsRow);

        tvParam = tv("", 10, 0xB3FFFFFF, false);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(9);
        tvParam.setLayoutParams(plp);
        tvParam.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleParam();
            }
        });
        top.addView(tvParam);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0xC8000000);
        bottom.setPadding(dp(18), dp(10), dp(18), dp(16));
        root.addView(bottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        tvHint = tv("", 11, C_GOLD, false);
        tvHint.setGravity(Gravity.CENTER);
        bottom.addView(tvHint);

        LinearLayout snapRow = row();
        snapRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srp.topMargin = dp(6);
        snapRow.setLayoutParams(srp);

        FrameLayout snap = new FrameLayout(this);
        LinearLayout.LayoutParams snlp = new LinearLayout.LayoutParams(dp(76), dp(76));
        snap.setLayoutParams(snlp);
        View ring = new View(this);
        ring.setBackground(oval(0x14FFFFFF, 0x66FFFFFF, 2));
        snap.addView(ring, new FrameLayout.LayoutParams(dp(76), dp(76)));
        View dot = new View(this);
        dot.setBackground(oval(0xFFFFFFFF, 0, 0));
        snap.addView(dot, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        snap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shootOnce("single");
            }
        });
        snapRow.addView(snap);
        bottom.addView(snapRow);

        btnRun = tv("开始", 13, C_TEXT, true);
        btnRun.setGravity(Gravity.CENTER);
        btnRun.setPadding(0, dp(13), 0, dp(13));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        btnRun.setLayoutParams(blp);
        btnRun.setBackground(round(C_CARD, C_LINE, 14, 1));
        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleRun();
            }
        });
        bottom.addView(btnRun);

        LinearLayout lastRow = row();
        lastRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lrp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lrp.topMargin = dp(9);
        lastRow.setLayoutParams(lrp);

        ivThumb = new ImageView(this);
        ivThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivThumb.setBackgroundColor(C_CARD);
        ivThumb.setVisibility(View.GONE);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(42), dp(42));
        ilp.rightMargin = dp(10);
        ivThumb.setLayoutParams(ilp);
        ivThumb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openGallery();
            }
        });
        lastRow.addView(ivThumb);

        tvLast = tv("还没有照片 · 点这里看相册", 10, C_DIM, false);
        tvLast.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openGallery();
            }
        });
        lastRow.addView(tvLast);
        bottom.addView(lastRow);

        buildGallery(root);

        setMode(MODE_SHOT);
        refreshRun();
    }

    /* ====================== 相册层 ====================== */

    private void buildGallery(FrameLayout root) {
        galleryLayer = new FrameLayout(this);
        galleryLayer.setBackgroundColor(C_BG);
        galleryLayer.setVisibility(View.GONE);
        root.addView(galleryLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        galleryLayer.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = row();
        bar.setBackgroundColor(0xFF0C0E13);
        bar.setPadding(dp(14), dp(13), dp(14), dp(13));
        TextView back = tv("‹ 返回", 13, C_BLUE, false);
        back.setPadding(dp(2), dp(4), dp(10), dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                galleryLayer.setVisibility(View.GONE);
                selecting = false;
                sel.clear();
                updateSelUi();
            }
        });
        bar.addView(back);

        tvGalTitle = tv("", 11, C_DIM, false);
        bar.addView(tvGalTitle);

        View sp = new View(this);
        bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));

        btnSelect = tv("选择", 12, C_BLUE, true);
        btnSelect.setPadding(dp(12), dp(6), dp(12), dp(6));
        btnSelect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selecting = !selecting;
                if (!selecting) {
                    sel.clear();
                }
                updateSelUi();
            }
        });
        bar.addView(btnSelect);
        col.addView(bar);

        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(dp(6));
        grid.setVerticalSpacing(dp(6));
        grid.setPadding(dp(8), dp(8), dp(8), dp(8));
        grid.setBackgroundColor(C_BG);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter = new PhotoAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object o = adapter.getItem(position);
                if (!(o instanceof File)) {
                    return;
                }
                File f = (File) o;
                if (selecting) {
                    String k = f.getAbsolutePath();
                    if (!sel.remove(k)) {
                        sel.add(k);
                    }
                    updateSelUi();
                } else {
                    showBig(f);
                }
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        grid.setLayoutParams(glp);
        col.addView(grid);

        tvGalTip = tv("点看大图 · 点「选择」可多选删除或导出", 10, 0xFF5A6068, false);
        tvGalTip.setGravity(Gravity.CENTER);
        tvGalTip.setPadding(0, dp(8), 0, dp(10));
        col.addView(tvGalTip);

        selBar = row();
        selBar.setBackgroundColor(0xFF0C0E13);
        selBar.setPadding(dp(12), dp(12), dp(12), dp(14));
        selBar.setVisibility(View.GONE);

        btnSelectAll = tv("全选", 12, C_TEXT, false);
        btnSelectAll.setGravity(Gravity.CENTER);
        btnSelectAll.setPadding(0, dp(11), 0, dp(11));
        btnSelectAll.setBackground(round(0x1FFFFFFF, C_LINE, 14, 1));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        al.rightMargin = dp(8);
        btnSelectAll.setLayoutParams(al);
        btnSelectAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                File[] fs = listShots();
                if (sel.size() >= fs.length) {
                    sel.clear();
                } else {
                    sel.clear();
                    for (int i = 0; i < fs.length; i++) {
                        sel.add(fs[i].getAbsolutePath());
                    }
                }
                updateSelUi();
            }
        });
        selBar.addView(btnSelectAll);

        btnDel = tv("删除", 12, C_RED, true);
        btnDel.setGravity(Gravity.CENTER);
        btnDel.setPadding(0, dp(11), 0, dp(11));
        btnDel.setBackground(round(0x1FFF6B5E, 0x4DFF6B5E, 14, 1));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dl.rightMargin = dp(8);
        btnDel.setLayoutParams(dl);
        btnDel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteSelected();
            }
        });
        selBar.addView(btnDel);

        btnExport = tv("导出", 12, C_GOLD, true);
        btnExport.setGravity(Gravity.CENTER);
        btnExport.setPadding(0, dp(11), 0, dp(11));
        btnExport.setBackground(round(0x1FD8B25E, 0x4DD8B25E, 14, 1));
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f);
        btnExport.setLayoutParams(el);
        btnExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportSelected();
            }
        });
        selBar.addView(btnExport);
        col.addView(selBar);
    }

    private void openGallery() {
        adapter.reload();
        tvGalTitle.setText(adapter.getCount() + " 张");
        galleryLayer.setVisibility(View.VISIBLE);
    }

    private void updateSelUi() {
        if (btnSelect == null) {
            return;
        }
        btnSelect.setText(selecting ? "取消" : "选择");
        btnSelect.setTextColor(selecting ? C_RED : C_BLUE);
        selBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        tvGalTip.setVisibility(selecting ? View.GONE : View.VISIBLE);
        btnDel.setText("删除 " + sel.size());
        btnExport.setText("导出 " + sel.size());
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void deleteSelected() {
        if (sel.isEmpty()) {
            toast("先选几张");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除 " + sel.size() + " 张照片？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int n = 0;
                        for (String s : new ArrayList<String>(sel)) {
                            File f = new File(s);
                            if (f.exists() && f.delete()) {
                                n++;
                            }
                        }
                        sel.clear();
                        selecting = false;
                        updateSelUi();
                        adapter.reload();
                        tvGalTitle.setText(adapter.getCount() + " 张");
                        refreshThumb();
                        toast("已删除 " + n + " 张");
                    }
                })
                .show();
    }

    private void exportSelected() {
        if (sel.isEmpty()) {
            toast("先选几张");
            return;
        }
        int ok = 0;
        int total = 0;
        for (String s : new ArrayList<String>(sel)) {
            File f = new File(s);
            if (!f.exists()) {
                continue;
            }
            total++;
            if (exportOne(f)) {
                ok++;
            }
        }
        if (ok == 0) {
            toast("导出失败（无权限或空间不足）");
        } else {
            toast("已导出 " + ok + " / " + total + " 张 → Pictures/PP360");
        }
    }

    private boolean exportOne(File f) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, f.getName());
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/PP360");
                Uri uri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) {
                    return false;
                }
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) {
                    return false;
                }
                copy(f, os);
                return true;
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "PP360");
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File dst = new File(dir, f.getName());
            FileOutputStream out = new FileOutputStream(dst);
            copy(f, out);
            MediaScannerConnection.scanFile(this, new String[]{dst.getAbsolutePath()}, null, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void copy(File src, OutputStream os) throws Exception {
        FileInputStream in = new FileInputStream(src);
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) {
            os.write(buf, 0, r);
        }
        in.close();
        os.close();
    }

    private void showBig(final File f) {
        ImageView iv = new ImageView(this);
        iv.setBackgroundColor(0xFF000000);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new ViewGroup.LayoutParams(dp(300), dp(400)));
        Bitmap bmp = decode(f, 1400);
        if (bmp == null) {
            toast("读不出这张");
            return;
        }
        iv.setImageBitmap(bmp);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(iv)
                .setNegativeButton("关闭", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                        confirmDelete(f);
                    }
                })
                .create();
        dlg.show();
        try {
            dlg.getWindow().setLayout(dp(320), dp(440));
        } catch (Exception ignored) {
        }
    }

    private void confirmDelete(final File f) {
        if (f == null || !f.exists()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除这张照片？")
                .setMessage(f.getName())
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (f.delete()) {
                            adapter.reload();
                            tvGalTitle.setText(adapter.getCount() + " 张");
                            refreshThumb();
                            toast("已删除");
                        } else {
                            toast("删不掉");
                        }
                    }
                })
                .show();
    }

    private Bitmap decode(File f, int maxPx) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int side = Math.max(o.outWidth, o.outHeight);
            int s = 1;
            while (side / (s * 2) >= maxPx) {
                s *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = s;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    private File[] listShots() {
        List<File> list = new ArrayList<File>();
        File[] fs = shotDir().listFiles();
        if (fs != null) {
            for (int i = 0; i < fs.length; i++) {
                if (fs[i].getName().endsWith(".jpg")) {
                    list.add(fs[i]);
                }
            }
        }
        Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long x = b.lastModified();
                long y = a.lastModified();
                return x > y ? 1 : (x < y ? -1 : 0);
            }
        });
        return list.toArray(new File[list.size()]);
    }

    private void refreshThumb() {
        if (ivThumb == null) {
            return;
        }
        File[] fs = listShots();
        if (fs.length == 0) {
            ivThumb.setVisibility(View.GONE);
            tvLast.setText("还没有照片 · 点这里看相册");
            tvLast.setTextColor(C_DIM);
            return;
        }
        Bitmap b = decode(fs[0], 160);
        if (b != null) {
            ivThumb.setImageBitmap(b);
            ivThumb.setVisibility(View.VISIBLE);
        }
        tvLast.setText(fs[0].getName());
        tvLast.setTextColor(C_TEXT);
    }

    /* ====================== 全景拼接 ====================== */

    private void stitchLastPano() {
        List<File> fs = new ArrayList<File>();
        for (int i = 0; i < panoPaths.size(); i++) {
            File f = new File(panoPaths.get(i));
            if (f.exists()) {
                fs.add(f);
            }
        }
        if (fs.size() < 2) {
            tvHint.setText("拼接失败：只拿到 " + fs.size() + " 张");
            tvHint.setTextColor(C_RED);
            return;
        }
        stitchPano(fs);
    }

    /** 每张取中间 34% 宽，按拍摄顺序横向排成一张 360° 展开图 */
    private void stitchPano(List<File> files) {
        int n = files.size();
        Bitmap[] src = new Bitmap[n];
        int h = 0;
        for (int i = 0; i < n; i++) {
            src[i] = decode(files.get(i), 640);
            if (src[i] == null) {
                toast("拼接失败：有一张读不出");
                for (int k = 0; k <= i; k++) {
                    if (src[k] != null) {
                        src[k].recycle();
                    }
                }
                return;
            }
            if (h == 0) {
                h = src[i].getHeight();
            }
        }
        int slice = Math.max(24, src[0].getWidth() * 34 / 100);
        int total = slice * n;
        Bitmap out;
        try {
            out = Bitmap.createBitmap(total, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            toast("内存不够拼接，保留原图");
            for (int k = 0; k < n; k++) {
                src[k].recycle();
            }
            return;
        }
        Canvas cv = new Canvas(out);
        cv.drawColor(0xFF000000);
        Paint pp = new Paint(Paint.FILTER_BITMAP_FLAG);
        pp.setFilterBitmap(true);
        for (int i = 0; i < n; i++) {
            Bitmap b = src[i];
            int sw = Math.min(slice, b.getWidth());
            int sx = (b.getWidth() - sw) / 2;
            int sy = Math.max(0, (b.getHeight() - h) / 2);
            Rect sr = new Rect(sx, sy, sx + sw, sy + h);
            Rect dr = new Rect(i * slice, 0, i * slice + sw, h);
            cv.drawBitmap(b, sr, dr, pp);
        }
        File outF = new File(shotDir(), "panorama_" + stamp() + ".jpg");
        boolean ok = false;
        try {
            FileOutputStream fo = new FileOutputStream(outF);
            ok = out.compress(Bitmap.CompressFormat.JPEG, 88, fo);
            fo.close();
        } catch (Exception e) {
            ok = false;
        }
        out.recycle();
        for (int i = 0; i < n; i++) {
            src[i].recycle();
        }
        if (!ok) {
            tvHint.setText("全景图保存失败");
            tvHint.setTextColor(C_RED);
            return;
        }
        try {
            MediaScannerConnection.scanFile(this, new String[]{outF.getAbsolutePath()}, null, null);
        } catch (Exception ignored) {
        }
        tvHint.setText("全景完成 · 已拼成 1 张 " + outF.getName());
        tvHint.setTextColor(C_GOLD);
        refreshThumb();
        if (adapter != null) {
            adapter.reload();
            tvGalTitle.setText(adapter.getCount() + " 张");
        }
    }

    /** 相册网格 */
    class PhotoAdapter extends BaseAdapter {
        private File[] files = new File[0];
        private final HashMap<String, Bitmap> cache = new HashMap<String, Bitmap>();

        void reload() {
            files = listShots();
            cache.clear();
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return files.length;
        }

        @Override
        public Object getItem(int i) {
            return i >= 0 && i < files.length ? files[i] : null;
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            ImageView iv;
            if (convert instanceof ImageView) {
                iv = (ImageView) convert;
            } else {
                iv = new ImageView(ShootActivity.this);
                iv.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(112)));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            }
            final File f = files[i];
            String key = f.getAbsolutePath();
            Bitmap b = cache.get(key);
            if (b == null) {
                b = decode(f, 240);
                if (b != null) {
                    cache.put(key, b);
                }
            }
            iv.setImageBitmap(b);
            iv.setTag(key);
            boolean on = selecting && sel.contains(key);
            iv.setBackground(on ? round(0x33D8B25E, C_GOLD, 8, 2) : round(C_CARD, 0, 0, 0));
            iv.setAlpha(on ? 0.6f : 1f);
            iv.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    confirmDelete(f);
                    return true;
                }
            });
            return iv;
        }
    }

    /* ====================== 模式 ====================== */

    private void setMode(int m) {
        if (running) {
            stopRun();
        }
        mode = m;
        for (int i = 0; i < 4; i++) {
            boolean on = i == m;
            chips[i].setTextColor(on ? 0xFF1A1204 : C_TEXT);
            chips[i].setBackground(on ? round(C_GOLD, 0xFFF0D9A0, 14, 1)
                    : round(0x26FFFFFF, 0x26FFFFFF, 14, 1));
        }
        if (m == MODE_SHOT) {
            tvParam.setText("手动快门 · 云台不动");
        } else if (m == MODE_TIMER) {
            tvParam.setText("每 " + (intervalMs / 1000) + " 秒 1 张 · 共 " + plan
                    + " 张 · 每张后云台正转一格（点此切换参数）");
        } else if (m == MODE_PANO) {
            tvParam.setText("云台正转一圈 18 格 · 拍完自动拼成一张全景");
        } else {
            tvParam.setText("云台巡转 · 人脸在中心框连续确认 3 次后停转拍摄");
        }
        if (cam != null) {
            cam.faceOn(m == MODE_TRACK);
        }
        overlay.setFaceReady(m == MODE_TRACK);
        tvHint.setText("");
    }

    private void cycleParam() {
        if (mode != MODE_TIMER) {
            toast("这个模式没有可调参数");
            return;
        }
        if (intervalMs == 5000) {
            intervalMs = 3000;
            plan = 6;
        } else if (intervalMs == 3000) {
            intervalMs = 10000;
            plan = 18;
        } else {
            intervalMs = 5000;
            plan = 12;
        }
        setMode(MODE_TIMER);
    }

    /* ====================== 拍摄 ====================== */

    private void shootOnce(String tag) {
        if (cam == null || !cam.isOpen()) {
            tvHint.setText("相机未就绪");
            tvHint.setTextColor(C_RED);
            return;
        }
        if (photoBusy) {
            return;
        }
        photoBusy = true;
        cam.shoot(tag);
    }

    private void toggleRun() {
        if (running) {
            stopRun();
            tvHint.setText("已停止");
            tvHint.setTextColor(C_DIM);
            return;
        }
        if (cam == null || !cam.isOpen()) {
            tvHint.setText("相机还没就绪，等一下再试");
            tvHint.setTextColor(C_RED);
            return;
        }
        if (mode == MODE_SHOT) {
            shootOnce("single");
            return;
        }
        if (!MainActivity.bleReady()) {
            tvHint.setText("先回云台页连上云台");
            tvHint.setTextColor(C_RED);
            return;
        }
        running = true;
        done = 0;
        hitStreak = 0;
        refreshRun();
        if (mode == MODE_TIMER) {
            tvHint.setText("定时拍摄开始");
            tvHint.setTextColor(C_GOLD);
            timerTick();
        } else if (mode == MODE_PANO) {
            panoPaths.clear();
            panoCollect = true;
            tvHint.setText("全景开始，云台转一圈（约 1 分钟）");
            tvHint.setTextColor(C_GOLD);
            panoTick();
        } else {
            cam.faceOn(true);
            overlay.setFaceReady(true);
            MainActivity.shootSpin(true);
            tvHint.setText("巡转中，找人脸…");
            tvHint.setTextColor(C_GOLD);
        }
    }

    private void stopRun() {
        boolean was = running;
        running = false;
        refreshRun();
        if (was && mode == MODE_TRACK) {
            MainActivity.shootSpin(false);
        }
    }

    private void timerTick() {
        if (!running || mode != MODE_TIMER) {
            return;
        }
        shootOnce("timer" + (done + 1));
        done++;
        tvHint.setText("定时拍摄 " + done + " / " + plan);
        if (done >= plan) {
            stopRun();
            tvHint.setText("定时拍摄完成，共 " + plan + " 张");
            tvHint.setTextColor(C_GOLD);
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                MainActivity.shootStep();
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        timerTick();
                    }
                }, intervalMs);
            }
        }, 1500);
    }

    private void panoTick() {
        if (!running || mode != MODE_PANO) {
            return;
        }
        MainActivity.shootStep();
        tvHint.setText("转到第 " + (done + 1) + " 格…");
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                shootOnce("pano" + (done + 1));
                done++;
                tvHint.setText("全景拍摄 " + done + " / " + PANO_TOTAL);
                if (done >= PANO_TOTAL) {
                    running = false;
                    refreshRun();
                    tvHint.setText("拍摄完成，正在拼接…");
                    tvHint.setTextColor(C_GOLD);
                    ui.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            stitchLastPano();
                            panoCollect = false;
                        }
                    }, 2200);
                    return;
                }
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        panoTick();
                    }
                }, 1000);
            }
        }, 1900);
    }

    private void refreshRun() {
        if (btnRun == null) {
            return;
        }
        btnRun.setText(running ? "停止" : "开始");
        btnRun.setTextColor(running ? 0xFF1A1204 : C_TEXT);
        btnRun.setBackground(running ? round(C_GOLD, 0xFFF0D9A0, 14, 1)
                : round(C_CARD, C_LINE, 14, 1));
    }

    /* ====================== 相机回调 ====================== */

    private final CameraController.Cb camCb = new CameraController.Cb() {
        @Override
        public void camLog(String m) {
        }

        @Override
        public void camReady(int w, int h) {
            tvTop.setText("相机就绪  " + w + "×" + h);
            tvTop.setTextColor(C_BLUE);
            overlay.setReady(true);
            if (mode == MODE_TRACK && cam != null) {
                cam.faceOn(true);
                overlay.setFaceReady(true);
            }
        }

        @Override
        public void camError(String m) {
            photoBusy = false;
            tvTop.setText(m);
            tvTop.setTextColor(C_RED);
            tvHint.setText(m);
            tvHint.setTextColor(C_RED);
        }

        @Override
        public void camShot(String path) {
            photoBusy = false;
            if (panoCollect && mode == MODE_PANO) {
                panoPaths.add(path);
            }
            String n = path.substring(path.lastIndexOf('/') + 1);
            tvLast.setText(n);
            tvLast.setTextColor(C_TEXT);
            refreshThumb();
            if (adapter != null && galleryLayer != null && galleryLayer.getVisibility() == View.VISIBLE) {
                adapter.reload();
                tvGalTitle.setText(adapter.getCount() + " 张");
            }
            try {
                MediaScannerConnection.scanFile(ShootActivity.this, new String[]{path}, null, null);
            } catch (Exception ignored) {
            }
            if (mode == MODE_TRACK && running) {
                running = false;
                refreshRun();
                tvHint.setText("抓到 1 张，按「开始」继续巡拍");
                tvHint.setTextColor(C_GOLD);
            }
        }

        @Override
        public void camFace(float nx, float ny, int size) {
            overlay.setFace(nx, ny);
            if (!running || mode != MODE_TRACK) {
                return;
            }
            boolean center = Math.abs(nx) < 0.10f && Math.abs(ny) < 0.12f && size >= 9;
            if (center) {
                hitStreak++;
                tvHint.setText("锁定中 " + hitStreak + " / " + HIT_NEED);
                tvHint.setTextColor(C_GOLD);
                if (hitStreak >= HIT_NEED) {
                    hitStreak = 0;
                    MainActivity.shootSpin(false);
                    tvHint.setText("已停转，对焦拍摄…");
                    ui.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (running && mode == MODE_TRACK) {
                                shootOnce("track");
                            }
                        }
                    }, 600);
                }
            } else {
                hitStreak = 0;
                if (size > 0 && size < 9) {
                    tvHint.setText("人脸太小，走近一点");
                    tvHint.setTextColor(C_DIM);
                } else {
                    tvHint.setText("巡转中，找人脸…");
                    tvHint.setTextColor(C_GOLD);
                }
            }
        }
    };

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /* ====================== 取景框叠层 ====================== */

    static class Overlay extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean ready = false;
        private boolean faceReady = false;
        private float nx = 2f;
        private float ny = 2f;
        private long ts = 0;

        Overlay(Context c) {
            super(c);
        }

        void setReady(boolean b) {
            ready = b;
            invalidate();
        }

        void setFaceReady(boolean b) {
            faceReady = b;
            invalidate();
        }

        void setFace(float x, float y) {
            nx = x;
            ny = y;
            ts = System.currentTimeMillis();
            invalidate();
        }

        private int dp(float v) {
            return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
        }

        private float clamp(float v, float lo, float hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            int m = dp(20);
            int len = dp(28);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            p.setColor(ready ? 0x66FFFFFF : 0x26FFFFFF);
            c.drawLine(m, m, m + len, m, p);
            c.drawLine(m, m, m, m + len, p);
            c.drawLine(w - m - len, m, w - m, m, p);
            c.drawLine(w - m, m, w - m, m + len, p);
            c.drawLine(m, h - m, m + len, h - m, p);
            c.drawLine(m, h - m - len, m, h - m, p);
            c.drawLine(w - m - len, h - m, w - m, h - m, p);
            c.drawLine(w - m, h - m - len, w - m, h - m, p);

            if (!faceReady) {
                return;
            }
            boolean fresh = System.currentTimeMillis() - ts < 1600;
            boolean hit = fresh && Math.abs(nx) < 0.10f && Math.abs(ny) < 0.12f;
            int cx = w / 2;
            int cy = h / 2;
            int bw = dp(84);
            int bh = dp(84);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(hit ? dp(2.2f) : dp(1.2f));
            p.setColor(hit ? C_GOLD : 0x40FFFFFF);
            c.drawRect(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2, p);
            if (fresh) {
                float fx = cx + ny * w;
                float fy = cy + nx * h;
                p.setStyle(Paint.Style.FILL);
                p.setColor(C_GOLD);
                c.drawCircle(clamp(fx, dp(6), w - dp(6)), clamp(fy, dp(6), h - dp(6)), dp(4f), p);
            }
        }
    }
}
