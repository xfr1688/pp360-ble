package com.xfr1688.pp360;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 拍拍360（TL-6C）BLE 直连控制器。
 *
 * 协议（静态逆向 com.deepai.paipai，实机验证）：
 *   17 字节帧，写 fff1，通知 fff4，无校验无加密。
 *   11 01 55 01 02 [cmd] [speed] [refresh] 11 88 88 88 00 00 00 0D 0A
 *   cmd 0x02 + speed=0 + refresh=1  → 前进 20°，约 0.9s 到位（唯一可用步进）
 *   cmd 0x06                        → 连续旋转开关（每发一次翻转状态，不回包）
 *   反向指令不存在，只能走 17 步绕圈（340°）
 */
public class MainActivity extends Activity {

    /* ---------------- 协议常量 ---------------- */
    private static final UUID SVC_UUID  = UUID.fromString("00001910-0000-1000-8000-00805f9b34fb");
    private static final UUID W_UUID    = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final UUID N_UUID    = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int STEP_DEG = 20;    // 每步 20°
    private static final int RING     = 18;    // 一圈 18 步
    private static final int CMD_STEP = 0x02;  // 前进一格
    private static final int CMD_SPIN = 0x06;  // 连续旋转开关

    private static final byte[] BASE = new byte[]{
            0x11, 0x01, 0x55, 0x01, 0x02, 0x00, 0x00, 0x00, 0x11,
            (byte) 0x88, (byte) 0x88, (byte) 0x88, 0x00, 0x00, 0x00, 0x0D, 0x0A
    };

    /* ---------------- 配色 ---------------- */
    private static final int C_BG     = 0xFF0B0B0D;
    private static final int C_PANEL  = 0xFF1A1A20;
    private static final int C_FIELD  = 0xFF101014;
    private static final int C_LINE   = 0xFF30303A;
    private static final int C_TEXT   = 0xFFECECEA;
    private static final int C_DIM    = 0xFF8D8D99;
    private static final int C_GOLD   = 0xFFD8B25E;
    private static final int C_GOLD_D = 0xFFC9A44B;
    private static final int C_ERR    = 0xFFE2705F;

    /* ---------------- 状态 ---------------- */
    private final Handler ui = new Handler(Looper.getMainLooper());

    private BluetoothAdapter mAdapter;
    private BluetoothLeScanner mScanner;
    private BluetoothGatt mGatt;
    private BluetoothGattCharacteristic chW, chN;
    private BluetoothDevice mDevice;

    private final Map<String, BluetoothDevice> found = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();

    private boolean scanning = false;
    private boolean busy = false;
    private boolean spin = false;
    private int pos = 0, laps = 0, sent = 0;

    /* ---------------- 控件 ---------------- */
    private TextView tvStatus, tvAngle, tvMeta, tvLog;
    private Button btnStep, btnBack, btn90, btn180, btn360, btnStop,
            btnSpin, btnSpinStop, btnConnect, btnDisc, btnGo;
    private EditText etDeg;

    private final List<String> lines = new ArrayList<>();

    /* ====================== 生命周期 ====================== */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        bindEvents();
        try {
            getWindow().setStatusBarColor(C_BG);
        } catch (Exception ignored) {
        }

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mAdapter = (bm == null) ? null : bm.getAdapter();
        if (mAdapter == null) {
            setStatus("此设备没有蓝牙");
            btnConnect.setEnabled(false);
        } else {
            setStatus("未连接");
        }
        render();
        paintSpin();
        refreshBtns();
        log("帧：17 字节 · 写 fff1 · 命令位 = 第 6 字节");
    }

    @Override
    protected void onDestroy() {
        try {
            if (mGatt != null) {
                mGatt.disconnect();
                mGatt.close();
            }
        } catch (Exception ignored) {
        }
        mGatt = null;
        chW = null;
        chN = null;
        super.onDestroy();
    }

    /* ====================== UI 构建 ====================== */

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable bg(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            d.setStroke(dp(strokeDp), stroke);
        }
        return d;
    }

    private TextView text(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private void addTop(LinearLayout parent, View v, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        v.setLayoutParams(lp);
        parent.addView(v);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private void addCell(LinearLayout r, View v, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.rightMargin = dp(8);
        v.setLayoutParams(lp);
        r.addView(v);
    }

    private Button mkBtn(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(primary ? 0xFF231A06 : C_TEXT);
        b.setBackground(primary ? bg(C_GOLD_D, 0xFFE8C880, 14, 1) : bg(C_PANEL, C_LINE, 14, 1));
        b.setPadding(dp(4), dp(14), dp(4), dp(14));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);

        root.addView(text("拍拍360 云台", 20, C_TEXT, true));
        addTop(root, text("PP360 · BLE 直连 · 不需要官方 App", 11, C_DIM, false), 2);

        tvStatus = text("未连接", 12, C_DIM, false);
        addTop(root, tvStatus, 14);

        tvAngle = text("0°", 46, C_GOLD, true);
        tvAngle.setGravity(Gravity.CENTER);
        addTop(root, tvAngle, 8);

        tvMeta = text("0 / 18 步 · 已转 0 圈", 12, C_DIM, false);
        tvMeta.setGravity(Gravity.CENTER);
        addTop(root, tvMeta, 2);

        /* 行：回退 / 进一步 */
        LinearLayout r1 = row();
        btnBack = mkBtn("◀ 回退 17 步", false);
        btnStep = mkBtn("进一步 20° ▶", true);
        addCell(r1, btnBack, 1f);
        addCell(r1, btnStep, 1f);
        addTop(root, r1, 16);

        /* 行：预设角度 */
        LinearLayout r2 = row();
        btn90 = mkBtn("90°", false);
        btn180 = mkBtn("180°", false);
        btn360 = mkBtn("一圈", false);
        btnStop = mkBtn("停止", false);
        addCell(r2, btn90, 1f);
        addCell(r2, btn180, 1f);
        addCell(r2, btn360, 1f);
        addCell(r2, btnStop, 1f);
        addTop(root, r2, 8);

        /* 行：连续旋转 */
        LinearLayout r3 = row();
        btnSpin = mkBtn("◉ 连续旋转", true);
        btnSpinStop = mkBtn("■ 停", false);
        addCell(r3, btnSpin, 1f);
        addCell(r3, btnSpinStop, 0.8f);
        addTop(root, r3, 8);

        /* 行：自定义角度 */
        LinearLayout r4 = row();
        etDeg = new EditText(this);
        etDeg.setHint("角度，如 45");
        etDeg.setHintTextColor(C_DIM);
        etDeg.setTextColor(C_TEXT);
        etDeg.setTextSize(14);
        etDeg.setInputType(InputType.TYPE_CLASS_NUMBER);
        etDeg.setBackground(bg(C_FIELD, C_LINE, 14, 1));
        etDeg.setPadding(dp(14), dp(12), dp(14), dp(12));
        btnGo = mkBtn("转到", false);
        addCell(r4, etDeg, 1.8f);
        addCell(r4, btnGo, 0.8f);
        addTop(root, r4, 8);

        /* 行：连接 */
        LinearLayout r5 = row();
        btnConnect = mkBtn("连接云台", true);
        btnDisc = mkBtn("断开", false);
        addCell(r5, btnConnect, 1f);
        addCell(r5, btnDisc, 0.7f);
        addTop(root, r5, 16);

        addTop(root, text("① 短按云台电源键（灯亮）→ ② 点「连接云台」→ ③ 选 TL-6C",
                11, C_DIM, false), 10);

        addTop(root, text("运行日志", 11, C_DIM, false), 20);
        tvLog = new TextView(this);
        tvLog.setTextSize(11);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextColor(0xFF9A9AA6);
        tvLog.setBackground(bg(0xFF0C0C0F, C_LINE, 12, 1));
        tvLog.setPadding(dp(12), dp(10), dp(12), dp(10));
        tvLog.setTextIsSelectable(true);
        addTop(root, tvLog, 6);

        TextView foot = text("实测结论：只有正向 20°/步 与 0x06 连续旋转开关，没有反向指令。\n" +
                "协议来自 com.deepai.paipai v2.2.6 静态逆向 · 未使用第三方代码", 10, 0xFF5D5D68, false);
        foot.setGravity(Gravity.CENTER);
        addTop(root, foot, 18);
    }
