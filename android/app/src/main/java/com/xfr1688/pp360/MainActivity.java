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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 拍拍360（TL-6C）BLE 控制器主界面。
 *
 * 协议（静态逆向 com.deepai.paipai，实机验证）：
 *   cmd 0x02 + speed=0 + refresh=1  → 前进 20°，约 0.9s 到位（唯一可用步进）
 *   cmd 0x06                        → 连续旋转开关（发一次翻转一次，不回包）
 *   反向指令不存在，只能走 17 步绕圈（340°）
 */
public class MainActivity extends Activity {

    private static final int STEP_DEG = 20;   // 每步 20°
    private static final int RING     = 18;   // 一圈 18 步
    private static final int STEP_MS  = 1000; // 每步间隔（实机 0.9s 到位）

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
    private BleLink ble;

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

        ble = new BleLink(this, bleCb);
        if (!ble.isSupported()) {
            setStatus("此设备没有蓝牙");
            btnConnect.setEnabled(false);
        } else {
            setStatus("未连接");
        }
        render();
        paintSpin();
        refreshBtns();
        log("帧：17 字节 · 写 fff1 · 命令位 = 第 6 字节");
        log("协议来自 com.deepai.paipai v2.2.6 静态逆向");
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

    /* ---------------- 链路回调 ---------------- */
    private final BleLink.Callback bleCb = new BleLink.Callback() {
        @Override
        public void onLog(String msg) {
            log(msg);
        }

        @Override
        public void onState(String state) {
            setStatus(state);
        }

        @Override
        public void onConnected(final String name) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    btnConnect.setText("已连接");
                    setStatus("已连接 " + name);
                    refreshBtns();
                    toast("连上了：" + name);
                }
            });
        }

        @Override
        public void onNotify(byte[] value) {
            final String h = BleLink.hexOf(value);
            ui.post(new Runnable() {
                @Override
                public void run() {
                    log("← " + h);
                }
            });
        }

        @Override
        public void onDisconnected() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    spin = false;
                    busy = false;
                    btnConnect.setText("连接云台");
                    setStatus("未连接");
                    paintSpin();
                    refreshBtns();
                }
            });
        }
    };

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

        LinearLayout r1 = row();
        btnBack = mkBtn("◀ 回退 17 步", false);
        btnStep = mkBtn("进一步 20° ▶", true);
        addCell(r1, btnBack, 1f);
        addCell(r1, btnStep, 1f);
        addTop(root, r1, 16);

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

        LinearLayout r3 = row();
        btnSpin = mkBtn("◉ 连续旋转", true);
        btnSpinStop = mkBtn("■ 停", false);
        addCell(r3, btnSpin, 1f);
        addCell(r3, btnSpinStop, 0.8f);
        addTop(root, r3, 8);

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

    /* ====================== 交互 ====================== */

    private void bindEvents() {
        btnStep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSteps(1, "进一步");
            }
        });
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSteps(RING - 1, "回退一步");
            }
        });
        btn90.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goDeg(90, "转到 90°");
            }
        });
        btn180.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goDeg(180, "转到 180°");
            }
        });
        btn360.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSteps(RING, "转一圈");
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopSpin();
            }
        });
        btnSpin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSpin();
            }
        });
        btnSpinStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopSpin();
            }
        });
        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ble != null && ble.isConnected()) {
                    toast("已经连着了，先断开再换设备");
                    return;
                }
                btnConnect.setText("扫描中…");
                ble.scan();
            }
        });
        btnDisc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ble != null) {
                    ble.disconnect();
                    toast("已断开");
                }
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
                    toast("角度得是数字");
                    return;
                }
                if (d < 0 || d > 360) {
                    toast("输入 0–360 之间");
                    return;
                }
                goDeg(d, "转到 " + d + "°");
            }
        });
    }

    /* ====================== 步进 ====================== */

    private void doSteps(final int n, final String label) {
        if (ble == null || !ble.isConnected()) {
            toast("还没连上云台");
            return;
        }
        if (busy) {
            toast("正在执行，稍等");
            return;
        }
        if (n <= 0) {
            toast("已经在目标角度");
            return;
        }
        busy = true;
        refreshBtns();

        if (spin) {
            ble.toggleSpin();
            spin = false;
            log("先停掉连续旋转");
            paintSpin();
        }

        log("→ " + label + "：" + n + " 步（" + (n * STEP_DEG) + "°），约 " + n + " 秒");

        final int total = n;
        final int[] done = {0};
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (done[0] >= total) {
                    busy = false;
                    refreshBtns();
                    log("=== " + label + " 完成：" + total + " 步 ===");
                    return;
                }
                if (!ble.step()) {
                    busy = false;
                    refreshBtns();
                    log("写入失败，中断（连接可能掉了）");
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

    private void goDeg(int deg, String label) {
        int n = stepsTo(deg);
        if (n == 0) {
            toast("已在目标角度");
            return;
        }
        doSteps(n, label);
    }

    /* ====================== 连续旋转 ====================== */

    private void toggleSpin() {
        if (ble == null || !ble.isConnected()) {
            toast("还没连上云台");
            return;
        }
        if (busy) {
            toast("正在步进，稍等再开");
            return;
        }
        if (!ble.toggleSpin()) {
            toast("发送失败");
            return;
        }
        spin = !spin;
        log(spin ? "→ 0x06 启动连续旋转（不回包，不自停）" : "→ 0x06 停止连续旋转");
        toast(spin ? "正在连续旋转，再点一次停" : "已停");
        paintSpin();
    }

    private void stopSpin() {
        if (ble == null || !ble.isConnected()) {
            toast("还没连上");
            return;
        }
        if (busy) {
            toast("正在步进");
            return;
        }
        if (!spin) {
            toast("当前已是停止状态（0x0E 在本机无效，只能 0x06 翻转）");
            return;
        }
        ble.toggleSpin();
        spin = false;
        log("→ 0x06 停止（0x0E 对本机无效）");
        toast("已发送停止");
        paintSpin();
    }

    /* ====================== 界面刷新 ====================== */

    private void render() {
        if (tvAngle == null) {
            return;
        }
        tvAngle.setText((pos * STEP_DEG) + "°");
        tvMeta.setText(pos + " / " + RING + " 步 · 已转 " + laps + " 圈 · 已发 " + sent + " 帧");
    }

    private void paintSpin() {
        if (btnSpin == null) {
            return;
        }
        btnSpin.setText(spin ? "■ 停止旋转" : "◉ 连续旋转");
        btnStop.setEnabled(!busy && spin);
        btnSpinStop.setEnabled(!busy && spin);
        setStatus(spin ? "连续旋转中…"
                : (ble != null && ble.isConnected() ? ("已连接 " + ble.deviceName()) : "未连接"));
    }

    private void refreshBtns() {
        if (btnStep == null) {
            return;
        }
        btnStep.setEnabled(!busy);
        btnBack.setEnabled(!busy);
        btn90.setEnabled(!busy);
        btn180.setEnabled(!busy);
        btn360.setEnabled(!busy);
        btnGo.setEnabled(!busy);
        btnSpin.setEnabled(!busy);
        btnStop.setEnabled(!busy && spin);
        btnSpinStop.setEnabled(!busy && spin);
    }

    private void setStatus(String s) {
        if (tvStatus == null) {
            return;
        }
        tvStatus.setText("●  " + s);
        if (s.contains("失败") || s.contains("没") || s.contains("断开")) {
            tvStatus.setTextColor(C_ERR);
        } else if (s.contains("中") || s.contains("旋转")) {
            tvStatus.setTextColor(C_GOLD);
        } else {
            tvStatus.setTextColor(C_DIM);
        }
    }

    private void log(String m) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
        lines.add("[" + t + "] " + m);
        while (lines.size() > 80) {
            lines.remove(0);
        }
        if (tvLog != null) {
            StringBuilder sb = new StringBuilder();
            for (String s : lines) {
                sb.append(s).append('\n');
            }
            tvLog.setText(sb.toString());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
