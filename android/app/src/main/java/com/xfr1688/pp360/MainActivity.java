package com.xfr1688.pp360;

import android.app.Activity;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * PP360 云台控制器 —— v0.2 界面。
 *
 * 结构：底部三个入口（云台 / 拍摄 / 设置），控制台收进「开发者选项」。
 * 首页是产品透视图 + 角度读数 + 主操作，扫描结果直接列在页内，优先识别 TL- 设备。
 */
public class MainActivity extends Activity {

    private static final int STEP_DEG = 20;
    private static final int RING = 18;
    private static final int STEP_MS = 1000;

    /* ---------------- 配色：深空黑 + 香槟金 + 冷蓝 ---------------- */
    private static final int C_BG     = 0xFF05060A;
    private static final int C_CARD   = 0xFF12151B;
    private static final int C_CARD_2 = 0xFF0C0E13;
    private static final int C_LINE   = 0x1FFFFFFF;
    private static final int C_TEXT   = 0xFFF2F4F7;
    private static final int C_DIM    = 0xFF868C96;
    private static final int C_GOLD   = 0xFFD8B25E;
    private static final int C_BLUE   = 0xFF5AC8FA;
    private static final int C_RED    = 0xFFFF6B5E;

    /* ---------------- 状态 ---------------- */
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BleLink ble;

    private boolean busy = false;
    private boolean spin = false;
    private boolean scanning = false;
    private boolean autoConnect = true;
    private int pos = 0, laps = 0, sent = 0;

    private final List<BleLink.Dev> named = new ArrayList<>();
    private final List<BleLink.Dev> unnamed = new ArrayList<>();
    private boolean unnamedShown = false;

    /* ---------------- 页面容器 ---------------- */
    private FrameLayout content;
    private ScrollView pageHome, pageShot, pageSet, pageDev;
    private ScrollView lastScroll;
    private TextView tabHome, tabShot, tabSet;

    /* ---------------- 首页 ---------------- */
    private TextView tvPill, tvAngle, tvMeta, tvScanHint, tvOther, tvConnLine;
    private GimbalView gimbal;
    private LinearLayout devList, connBox;
    private Button btnRescan, btnDisc;
    private TextView btnSpin, btnMinus, btnPlus;

    /* ---------------- 设置 ---------------- */
    private Switch swAuto;
    private TextView tvSetDev, tvSetMac, tvSetState;

    /* ---------------- 开发者 ---------------- */
    private TextView tvLog, tvDiag;
    private Button btnStep1, btnP90, btnP180, btnP360, btnGo, btnRaw;
    private EditText etDeg, etRaw;
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
        ble = new BleLink(this, bleCb);
        render();
        setPill("未连接", C_DIM);
        refreshBtns();
        log("PP360 v0.2 启动");

        if (!ble.isSupported()) {
            setPill("不支持蓝牙", C_RED);
            tvScanHint.setText("此设备没有蓝牙适配器");
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!ble.isConnected()) {
                    startScan();
                }
            }
        }, 1400);
    }

    @Override
    protected void onDestroy() {
        try {
            if (ble != null) {
                ble.disconnect();
            }
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (ble != null) {
            ble.onPermissionResult(requestCode, grantResults);
        }
    }

    /* ====================== 基础绘图工具 ====================== */

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

    private void addTop(LinearLayout parent, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        v.setLayoutParams(lp);
        parent.addView(v);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private void addCell(LinearLayout r, View v, float weight, int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.rightMargin = dp(rightDp);
        v.setLayoutParams(lp);
        r.addView(v);
    }

    private Button btn(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(primary ? 0xFF1A1204 : C_TEXT);
        b.setBackground(primary ? round(C_GOLD, 0xFFF0D9A0, 16, 1) : round(C_CARD, C_LINE, 16, 1));
        b.setPadding(dp(6), dp(14), dp(6), dp(14));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    /** 一页 = ScrollView + 带边距的竖排内容 */
    private LinearLayout newPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(22), dp(20), dp(28));
        sv.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lastScroll = sv;
        return col;
    }

    /* ====================== 建界面 ====================== */

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(C_BG);
        setContentView(root);

        content = new FrameLayout(this);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        clp.bottomMargin = dp(66);
        root.addView(content, clp);

        pageHome = (ScrollView) buildHome();
        pageShot = (ScrollView) buildShot();
        pageSet = (ScrollView) buildSettings();
        pageDev = (ScrollView) buildDev();

        buildTabBar(root);
        showPage(0);
    }

    private View buildHome() {
        LinearLayout col = newPage();

        LinearLayout head = row();
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(tv("PP360", 24, C_TEXT, true));
        left.addView(tv("GIMBAL CONSOLE", 10, C_DIM, false));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        left.setLayoutParams(llp);
        head.addView(left);

        tvPill = tv("未连接", 12, C_DIM, false);
        tvPill.setPadding(dp(12), dp(6), dp(12), dp(6));
        tvPill.setBackground(round(0x14FFFFFF, 0x26FFFFFF, 20, 1));
        head.addView(tvPill);
        addTop(col, head, 0);

        gimbal = new GimbalView(this);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(268));
        glp.topMargin = dp(8);
        gimbal.setLayoutParams(glp);
        col.addView(gimbal);

        tvAngle = tv("0°", 42, C_TEXT, true);
        tvAngle.setGravity(Gravity.CENTER);
        tvAngle.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        addTop(col, tvAngle, 2);

        tvMeta = tv("第 0 圈 · 0 / 18 步", 12, C_DIM, false);
        tvMeta.setGravity(Gravity.CENTER);
        addTop(col, tvMeta, 4);

        LinearLayout ops = row();
        btnMinus = tv("−20°", 15, C_TEXT, false);
        btnMinus.setGravity(Gravity.CENTER);
        btnMinus.setPadding(0, dp(18), 0, dp(18));
        btnMinus.setBackground(round(C_CARD, C_LINE, 18, 1));

        btnSpin = tv("开始旋转", 16, 0xFF1A1204, true);
        btnSpin.setGravity(Gravity.CENTER);
        btnSpin.setPadding(0, dp(18), 0, dp(18));
        btnSpin.setBackground(round(C_GOLD, 0xFFF0D9A0, 18, 1));

        btnPlus = tv("+20°", 15, C_TEXT, false);
        btnPlus.setGravity(Gravity.CENTER);
        btnPlus.setPadding(0, dp(18), 0, dp(18));
        btnPlus.setBackground(round(C_CARD, C_LINE, 18, 1));

        addCell(ops, btnMinus, 0.8f, 8);
        addCell(ops, btnSpin, 1.6f, 8);
        addCell(ops, btnPlus, 0.8f, 0);
        addTop(col, ops, 18);

        connBox = new LinearLayout(this);
        connBox.setOrientation(LinearLayout.VERTICAL);
        connBox.setBackground(round(C_CARD_2, C_LINE, 18, 1));
        int pad = dp(14);
        connBox.setPadding(pad, dp(14), pad, pad);
        addTop(col, connBox, 18);

        tvScanHint = tv("正在搜索附近云台…", 12, C_DIM, false);
        connBox.addView(tvScanHint);

        devList = new LinearLayout(this);
        devList.setOrientation(LinearLayout.VERTICAL);
        addTop(connBox, devList, 4);

        tvOther = tv("", 11, C_DIM, false);
        tvOther.setVisibility(View.GONE);
        addTop(connBox, tvOther, 8);

        tvConnLine = tv("", 13, C_TEXT, false);
        tvConnLine.setVisibility(View.GONE);
        addTop(connBox, tvConnLine, 6);

        LinearLayout act = row();
        btnRescan = btn("重新搜索", false);
        btnDisc = btn("断开", false);
        btnDisc.setVisibility(View.GONE);
        addCell(act, btnRescan, 1f, 8);
        addCell(act, btnDisc, 0.7f, 0);
        addTop(connBox, act, 12);

        View spacer = new View(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24));
        spacer.setLayoutParams(slp);
        col.addView(spacer);

        return lastScroll;
    }

    private View buildShot() {
        LinearLayout col = newPage();

        addTop(col, tv("拍摄", 24, C_TEXT, true), 0);
        addTop(col, tv("把云台变成一台会跟人的相机", 12, C_DIM, false), 4);

        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setGravity(Gravity.CENTER);
        frame.setBackground(round(C_CARD_2, C_LINE, 22, 1));
        frame.setPadding(dp(16), dp(56), dp(16), dp(56));
        TextView lens = tv("◎", 44, 0x33FFFFFF, false);
        lens.setGravity(Gravity.CENTER);
        frame.addView(lens);
        TextView t1 = tv("相机接入在下一版", 14, C_TEXT, true);
        t1.setGravity(Gravity.CENTER);
        addTop(frame, t1, 10);
        TextView t2 = tv("需要相机权限与人脸/人体跟踪，先做 UI 骨架", 11, C_DIM, false);
        t2.setGravity(Gravity.CENTER);
        addTop(frame, t2, 4);
        addTop(col, frame, 18);

        String[][] modes = {
                {"跟随", "锁定主体，转台持续追焦", "规划中"},
                {"定时", "每 N 秒转一格拍一张", "规划中"},
                {"全景", "18 张拼一张 360°", "规划中"}
        };
        for (int i = 0; i < modes.length; i++) {
            LinearLayout cardRow = row();
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(tv(modes[i][0], 15, C_TEXT, true));
            texts.addView(tv(modes[i][1], 11, C_DIM, false));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            texts.setLayoutParams(tlp);
            cardRow.addView(texts);
            TextView st = tv(modes[i][2], 11, 0xFF6A7078, false);
            st.setPadding(dp(10), dp(5), dp(10), dp(5));
            st.setBackground(round(0x0CFFFFFF, C_LINE, 12, 1));
            cardRow.addView(st);
            cardRow.setBackground(round(C_CARD, C_LINE, 18, 1));
            int p = dp(16);
            cardRow.setPadding(p, dp(16), p, dp(16));
            addTop(col, cardRow, 10);
        }

        addTop(col, tv("说明：跟踪需要实时读相机帧做目标检测，会显著吃电；后续版本做成可选开关，默认只用固定角度定时拍摄。",
                11, 0xFF6A7078, false), 18);

        return lastScroll;
    }

    private View buildSettings() {
        LinearLayout col = newPage();

        addTop(col, tv("设置", 24, C_TEXT, true), 0);
        addTop(col, tv("设备 · 偏好 · 开发者", 12, C_DIM, false), 4);

        LinearLayout devCard = new LinearLayout(this);
        devCard.setOrientation(LinearLayout.VERTICAL);
        devCard.setBackground(round(C_CARD, C_LINE, 18, 1));
        int p = dp(16);
        devCard.setPadding(p, dp(16), p, dp(16));
        devCard.addView(tv("当前设备", 11, C_DIM, false));
        tvSetDev = tv("未连接", 16, C_TEXT, true);
        addTop(devCard, tvSetDev, 6);
        tvSetMac = tv("—", 11, C_DIM, false);
        addTop(devCard, tvSetMac, 3);
        tvSetState = tv("—", 11, C_GOLD, false);
        addTop(devCard, tvSetState, 3);
        addTop(col, devCard, 18);

        LinearLayout pref = new LinearLayout(this);
        pref.setOrientation(LinearLayout.VERTICAL);
        pref.setBackground(round(C_CARD, C_LINE, 18, 1));
        pref.setPadding(p, dp(14), p, dp(14));

        LinearLayout prefRow = row();
        LinearLayout prefTexts = new LinearLayout(this);
        prefTexts.setOrientation(LinearLayout.VERTICAL);
        prefTexts.addView(tv("自动连接优先设备", 14, C_TEXT, true));
        prefTexts.addView(tv("搜到 TL- 开头且只有一台时直接连", 11, C_DIM, false));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        prefTexts.setLayoutParams(plp);
        prefRow.addView(prefTexts);

        swAuto = new Switch(this);
        swAuto.setChecked(autoConnect);
        swAuto.setShowText(false);
        prefRow.addView(swAuto);
        pref.addView(prefRow);
        addTop(col, pref, 12);

        LinearLayout devEntry = row();
        devEntry.setBackground(round(C_CARD, C_LINE, 18, 1));
        devEntry.setPadding(p, dp(18), p, dp(18));
        LinearLayout devTexts = new LinearLayout(this);
        devTexts.setOrientation(LinearLayout.VERTICAL);
        devTexts.addView(tv("开发者选项", 14, C_TEXT, true));
        devTexts.addView(tv("单步控制台 · 原始帧 · 日志与诊断", 11, C_DIM, false));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        devTexts.setLayoutParams(dlp);
        devEntry.addView(devTexts);
        devEntry.addView(tv("›", 22, C_DIM, false));
        addTop(col, devEntry, 12);

        LinearLayout about = new LinearLayout(this);
        about.setOrientation(LinearLayout.VERTICAL);
        about.setBackground(round(C_CARD, C_LINE, 18, 1));
        about.setPadding(p, dp(16), p, dp(16));
        about.addView(tv("关于", 11, C_DIM, false));
        addTop(about, tv("PP360 v0.2", 14, C_TEXT, true), 6);
        addTop(about, tv("协议来自 com.deepai.paipai v2.2.6 静态逆向 · 未使用第三方代码", 11, C_DIM, false), 4);
        addTop(about, tv("开源：github.com/xfr1688/pp360-ble", 11, 0xFF6A7078, false), 4);
        addTop(col, about, 12);

        return lastScroll;
    }

    private View buildDev() {
        LinearLayout col = newPage();

        TextView back = tv("‹ 返回设置", 13, C_BLUE, false);
        col.addView(back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(2);
            }
        });

        addTop(col, tv("开发者选项", 22, C_TEXT, true), 8);
        addTop(col, tv("单步控制台 · 调试用，正常使用不需要进来", 11, C_DIM, false), 3);

        LinearLayout c1 = row();
        btnStep1 = btn("单步 20°", false);
        btnP90 = btn("90°", false);
        btnP180 = btn("180°", false);
        btnP360 = btn("一圈", false);
        addCell(c1, btnStep1, 1.4f, 8);
        addCell(c1, btnP90, 1f, 8);
        addCell(c1, btnP180, 1f, 8);
        addCell(c1, btnP360, 1f, 0);
        addTop(col, c1, 14);

        LinearLayout c2 = row();
        etDeg = new EditText(this);
        etDeg.setHint("角度");
        etDeg.setHintTextColor(C_DIM);
        etDeg.setTextColor(C_TEXT);
        etDeg.setTextSize(14);
        etDeg.setInputType(InputType.TYPE_CLASS_NUMBER);
        etDeg.setBackground(round(C_CARD, C_LINE, 14, 1));
        etDeg.setPadding(dp(14), dp(12), dp(14), dp(12));
        btnGo = btn("转到", false);
        addCell(c2, etDeg, 1.6f, 8);
        addCell(c2, btnGo, 0.9f, 0);
        addTop(col, c2, 8);

        LinearLayout c3 = row();
        etRaw = new EditText(this);
        etRaw.setText("11 01 55 01 02 02 00 01 11 88 88 88 00 00 00 0D 0A");
        etRaw.setTextSize(10);
        etRaw.setTextColor(C_DIM);
        etRaw.setInputType(InputType.TYPE_CLASS_TEXT);
        etRaw.setBackground(round(C_CARD, C_LINE, 14, 1));
        etRaw.setPadding(dp(12), dp(12), dp(12), dp(12));
        btnRaw = btn("发送帧", false);
        addCell(c3, etRaw, 2.4f, 8);
        addCell(c3, btnRaw, 1f, 0);
        addTop(col, c3, 8);

        tvDiag = tv("", 10, 0xFF6A7078, false);
        addTop(col, tvDiag, 14);

        addTop(col, tv("运行日志", 11, C_DIM, false), 14);
        tvLog = tv("", 10, 0xFF9AA0AA, false);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setBackground(round(C_CARD_2, C_LINE, 16, 1));
        tvLog.setPadding(dp(14), dp(12), dp(14), dp(12));
        tvLog.setTextIsSelectable(true);
        addTop(col, tvLog, 6);

        return lastScroll;
    }

    private void buildTabBar(FrameLayout root) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xF2090B0F);
        bar.setPadding(dp(10), dp(10), dp(10), dp(12));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66), Gravity.BOTTOM);
        root.addView(bar, lp);

        tabHome = tabItem("云台", 0);
        tabShot = tabItem("拍摄", 1);
        tabSet = tabItem("设置", 2);
        addCell(bar, tabHome, 1f, 6);
        addCell(bar, tabShot, 1f, 6);
        addCell(bar, tabSet, 1f, 0);
    }

    private TextView tabItem(String s, final int idx) {
        TextView t = tv(s, 13, 0xFF5A6068, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(12), 0, dp(12));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setTab(idx);
            }
        });
        return t;
    }

    /* ====================== 页面切换 ====================== */

    private void showPage(int idx) {
        pageHome.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        pageShot.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        pageSet.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        pageDev.setVisibility(idx == 3 ? View.VISIBLE : View.GONE);
        int off = 0xFF5A6068;
        if (tabHome != null) {
            tabHome.setTextColor(idx == 0 ? C_GOLD : off);
            tabShot.setTextColor(idx == 1 ? C_GOLD : off);
            tabSet.setTextColor(idx == 2 ? C_GOLD : off);
        }
    }

    private void setTab(int idx) {
        showPage(idx);
        if (idx == 0 && ble != null && !ble.isConnected() && !scanning) {
            startScan();
        }
    }

    /* ====================== 交互 ====================== */

    private void bindEvents() {
        btnMinus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSteps(RING - 1, "回退一步");
            }
        });
        btnPlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSteps(1, "进一步");
            }
        });
        btnSpin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSpin();
            }
        });
        btnRescan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        btnDisc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ble != null) {
                    ble.disconnect();
                }
            }
        });
        tvOther.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                unnamedShown = true;
                for (int i = 0; i < unnamed.size(); i++) {
                    devList.addView(deviceRow(unnamed.get(i), true));
                }
                tvOther.setVisibility(View.GONE);
            }
        });
        swAuto.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                autoConnect = checked;
            }
        });

        btnStep1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSteps(1, "单步");
            }
        });
        btnP90.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goDeg(90);
            }
        });
        btnP180.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goDeg(180);
            }
        });
        btnP360.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSteps(RING, "一圈");
            }
        });
        btnGo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String s = etDeg.getText().toString().trim();
                if (s.isEmpty()) {
                    toast("先输入角度");
                    return;
                }
                int d;
                try {
                    d = Integer.parseInt(s);
                } catch (Exception e) {
                    toast("得是数字");
                    return;
                }
                goDeg(d);
            }
        });
        btnRaw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendRaw();
            }
        });
        devEntryClickHook();
    }

    /** 设置页的「开发者选项」整行点击 -> 进第四页 */
    private void devEntryClickHook() {
        // 入口行是通过 devTexts 的父容器注册的，这里用页面内的查找方式
        if (pageSet == null) {
            return;
        }
        LinearLayout col = null;
        Object tag = pageSet.getChildAt(0);
        if (tag instanceof LinearLayout) {
            col = (LinearLayout) tag;
        }
        if (col == null || col.getChildCount() < 4) {
            return;
        }
        View entry = col.getChildAt(3);
        if (entry != null) {
            entry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPage(3);
                }
            });
        }
    }

    /* ====================== 扫描结果渲染 ====================== */

    private void clearDevices() {
        named.clear();
        unnamed.clear();
        unnamedShown = false;
        if (devList != null) {
            devList.removeAllViews();
        }
        if (tvOther != null) {
            tvOther.setText("");
            tvOther.setVisibility(View.GONE);
        }
    }

    private void startScan() {
        if (scanning || ble == null) {
            return;
        }
        if (ble.isConnected()) {
            return;
        }
        scanning = true;
        clearDevices();
        tvScanHint.setText("正在搜索附近云台…（先短按一下云台电源键）");
        tvScanHint.setTextColor(C_DIM);
        tvScanHint.setVisibility(View.VISIBLE);
        devList.setVisibility(View.VISIBLE);
        btnRescan.setText("搜索中…");
        ble.scan();
    }

    private String signalText(int rssi) {
        if (rssi >= -60) {
            return "信号强";
        }
        if (rssi >= -76) {
            return "信号中";
        }
        return "信号弱";
    }

    private View deviceRow(final BleLink.Dev d, boolean showMac) {
        LinearLayout row = row();
        row.setBackground(round(d.isTl() ? 0x14D8B25E : C_CARD,
                d.isTl() ? 0x4DD8B25E : C_LINE, 16, 1));
        int p = dp(14);
        row.setPadding(p, dp(13), p, dp(13));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        row.setLayoutParams(rlp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(tlp);

        LinearLayout nameRow = row();
        TextView nm = tv(showMac ? "未命名设备" : d.label(), 14, C_TEXT, d.isTl());
        nameRow.addView(nm);
        if (d.isTl()) {
            TextView tag = tv("推荐", 10, C_GOLD, false);
            tag.setPadding(dp(7), dp(2), dp(7), dp(2));
            tag.setBackground(round(0x1FD8B25E, 0x4DD8B25E, 10, 1));
            LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tagLp.leftMargin = dp(8);
            tag.setLayoutParams(tagLp);
            nameRow.addView(tag);
        }
        texts.addView(nameRow);

        String sub = signalText(d.rssi) + (showMac ? "   " + d.mac() : "");
        texts.addView(tv(sub, 11, C_DIM, false));
        row.addView(texts);

        TextView go = tv("连接", 12, C_BLUE, false);
        go.setPadding(dp(12), dp(8), dp(12), dp(8));
        go.setBackground(round(0x145AC8FA, 0x405AC8FA, 14, 1));
        row.addView(go);

        View.OnClickListener clk = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ble != null) {
                    scanning = false;
                    ble.stopScan();
                    ble.connect(d.address);
                }
            }
        };
        row.setOnClickListener(clk);
        go.setOnClickListener(clk);
        return row;
    }

    /* ====================== 链路回调 ====================== */

    private final BleLink.Callback bleCb = new BleLink.Callback() {
        @Override
        public void onLog(String msg) {
            log(msg);
        }

        @Override
        public void onState(String state) {
            setPill(state, (state.contains("失败") || state.contains("未授")) ? C_RED
                    : (state.contains("中") || state.contains("搜索")) ? C_GOLD : C_DIM);
            if (tvSetState != null) {
                tvSetState.setText(state);
            }
            if (state.contains("搜索")) {
                return;
            }
            if (!state.contains("中") && !state.contains("发现")) {
                scanning = false;
                if (btnRescan != null) {
                    btnRescan.setText("重新搜索");
                }
            }
        }

        @Override
        public void onScanFound(BleLink.Dev dev) {
            if (dev.label().startsWith("未命名")) {
                unnamed.add(dev);
                return;
            }
            named.add(dev);
            devList.addView(deviceRow(dev, false));
            tvScanHint.setText("发现 " + named.size() + " 台可用设备");
            tvScanHint.setTextColor(C_GOLD);
        }

        @Override
        public void onScanFinished(List<BleLink.Dev> list) {
            scanning = false;
            btnRescan.setText("重新搜索");
            if (tvDiag != null) {
                tvDiag.setText(ble.diag());
            }
            if (named.isEmpty() && unnamed.isEmpty()) {
                tvScanHint.setText("没搜到设备。短按云台电源键让灯亮，再点「重新搜索」");
                tvScanHint.setTextColor(C_RED);
                return;
            }
            tvScanHint.setText("可用设备 " + named.size() + " 台");
            if (named.isEmpty()) {
                tvScanHint.setTextColor(C_RED);
                tvScanHint.setText("只搜到未命名设备，点下方展开看看");
            }
            if (unnamed.size() > 0 && !unnamedShown) {
                tvOther.setText("另有 " + unnamed.size() + " 台未命名设备，点此展开");
                tvOther.setVisibility(View.VISIBLE);
            }

            if (autoConnect && named.size() == 1 && named.get(0).isTl() && !ble.isConnected()) {
                log("自动连接优先设备 " + named.get(0).label());
                tvScanHint.setText("自动连接 " + named.get(0).label() + "…");
                ble.connect(named.get(0).address);
            }
        }

        @Override
        public void onConnected(String name) {
            scanning = false;
            spin = false;
            busy = false;
            setPill("已连接", C_BLUE);
            tvScanHint.setVisibility(View.GONE);
            devList.setVisibility(View.GONE);
            tvOther.setVisibility(View.GONE);
            tvConnLine.setText("已连接 " + name + "  ·  " + ble.deviceMac());
            tvConnLine.setVisibility(View.VISIBLE);
            btnRescan.setVisibility(View.GONE);
            btnDisc.setVisibility(View.VISIBLE);
            gimbal.setConnected(true);
            tvSetDev.setText(name);
            tvSetMac.setText(ble.deviceMac());
            tvSetState.setText("已连接");
            refreshBtns();
            paintSpin();
            toast("已连接 " + name);
        }

        @Override
        public void onNotify(byte[] value) {
            log("← " + BleLink.hexOf(value));
        }

        @Override
        public void onDisconnected() {
            scanning = false;
            spin = false;
            busy = false;
            setPill("未连接", C_DIM);
            tvScanHint.setVisibility(View.VISIBLE);
            devList.setVisibility(View.VISIBLE);
            tvConnLine.setVisibility(View.GONE);
            btnRescan.setVisibility(View.VISIBLE);
            btnDisc.setVisibility(View.GONE);
            tvSetDev.setText("未连接");
            tvSetMac.setText("—");
            tvSetState.setText("—");
            gimbal.setConnected(false);
            gimbal.setSpinning(false);
            refreshBtns();
            paintSpin();
        }
    };

    /* ====================== 步进 / 旋转 ====================== */

    private void doSteps(final int n, final String label) {
        if (ble == null || !ble.isConnected()) {
            toast("还没连接云台");
            return;
        }
        if (busy) {
            toast("正在执行");
            return;
        }
        if (n <= 0) {
            toast("已在目标角度");
            return;
        }
        busy = true;
        refreshBtns();
        if (spin) {
            ble.toggleSpin();
            spin = false;
            gimbal.setSpinning(false);
            log("先停止连续旋转");
            paintSpin();
        }
        log("→ " + label + "：" + n + " 步");

        final int total = n;
        final int[] done = {0};
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (done[0] >= total) {
                    busy = false;
                    refreshBtns();
                    log("=== " + label + " 完成 ===");
                    return;
                }
                if (!ble.step()) {
                    busy = false;
                    refreshBtns();
                    toast("发送失败，检查连接");
                    return;
                }
                done[0]++;
                sent++;
                pos = (pos + 1) % RING;
                if (pos == 0) {
                    laps++;
                }
                render();
                ui.postDelayed(this, STEP_MS);
            }
        });
    }

    private int stepsTo(int deg) {
        int target = ((Math.round(deg / (float) STEP_DEG)) % RING + RING) % RING;
        return (target - pos + RING) % RING;
    }

    private void goDeg(int deg) {
        if (deg < 0 || deg > 360) {
            toast("输入 0–360");
            return;
        }
        int n = stepsTo(deg);
        if (n == 0) {
            toast("已在目标角度");
            return;
        }
        doSteps(n, "转到 " + deg + "°");
    }

    private void toggleSpin() {
        if (ble == null || !ble.isConnected()) {
            toast("还没连接云台");
            startScan();
            return;
        }
        if (busy) {
            toast("正在步进");
            return;
        }
        if (!ble.toggleSpin()) {
            toast("发送失败");
            return;
        }
        spin = !spin;
        gimbal.setSpinning(spin);
        log(spin ? "→ 0x06 连续旋转" : "→ 0x06 停止");
        paintSpin();
    }

    private void sendRaw() {
        if (ble == null || !ble.isConnected()) {
            toast("还没连接");
            return;
        }
        String t = etRaw.getText().toString().replaceAll("[^0-9a-fA-F]", "");
        if (t.isEmpty() || t.length() % 2 != 0) {
            toast("hex 长度不对");
            return;
        }
        byte[] arr = new byte[t.length() / 2];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
        }
        if (ble.send(arr)) {
            log("→ RAW " + BleLink.hexOf(arr));
            toast("已发送");
        } else {
            toast("发送失败");
        }
    }

    /* ====================== 界面刷新 ====================== */

    private void render() {
        tvAngle.setText((pos * STEP_DEG) + "°");
        tvMeta.setText("第 " + laps + " 圈 · " + pos + " / " + RING + " 步");
        if (gimbal != null) {
            gimbal.setAngleTarget(pos * STEP_DEG + laps * 360f);
        }
    }

    private void paintSpin() {
        btnSpin.setText(spin ? "停止旋转" : "开始旋转");
        btnSpin.setTextColor(spin ? 0xFFF2F4F7 : 0xFF1A1204);
        btnSpin.setBackground(spin ? round(0xFF2A2F38, 0x40FFFFFF, 18, 1)
                : round(C_GOLD, 0xFFF0D9A0, 18, 1));
        if (ble != null && ble.isConnected()) {
            setPill(spin ? "旋转中" : "已连接", spin ? C_GOLD : C_BLUE);
        }
    }

    private void refreshBtns() {
        btnMinus.setEnabled(!busy);
        btnPlus.setEnabled(!busy);
        btnSpin.setEnabled(!busy);
        btnStep1.setEnabled(!busy);
        btnP90.setEnabled(!busy);
        btnP180.setEnabled(!busy);
        btnP360.setEnabled(!busy);
        btnGo.setEnabled(!busy);
        btnRaw.setEnabled(!busy);
    }

    private void setPill(String s, int color) {
        tvPill.setText(s);
        tvPill.setTextColor(color);
        tvPill.setBackground(round(0x14FFFFFF, 0x26FFFFFF, 20, 1));
    }

    private void log(String m) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
        lines.add("[" + t + "] " + m);
        while (lines.size() > 120) {
            lines.remove(0);
        }
        if (tvLog == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            sb.append(s).append('\n');
        }
        tvLog.setText(sb.toString());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
