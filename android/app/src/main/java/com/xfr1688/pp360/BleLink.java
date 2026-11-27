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
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 拍拍360（TL-6C）BLE 链路层。
 * 只负责：扫设备、建链路、发 17 字节帧。所有回调已统一切到主线程。
 */
public class BleLink {

    public static final UUID SVC_UUID  = UUID.fromString("00001910-0000-1000-8000-00805f9b34fb");
    public static final UUID W_UUID    = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    public static final UUID N_UUID    = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final int CMD_STEP = 0x02;
    public static final int CMD_SPIN = 0x06;

    private static final int SCAN_MS = 15000;

    private static final byte[] BASE = new byte[]{
            0x11, 0x01, 0x55, 0x01, 0x02, 0x00, 0x00, 0x00, 0x11,
            (byte) 0x88, (byte) 0x88, (byte) 0x88, 0x00, 0x00, 0x00, 0x0D, 0x0A
    };

    public interface Callback {
        void onLog(String msg);
        void onState(String state);
        void onConnected(String name);
        void onNotify(byte[] value);
        void onDisconnected();
    }

    private final Activity activity;
    private final Callback cb;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private BluetoothAdapter mAdapter;
    private BluetoothLeScanner mScanner;
    private BluetoothGatt mGatt;
    private BluetoothGattCharacteristic chW, chN;
    private BluetoothDevice mDevice;

    private final Map<String, BluetoothDevice> found = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private boolean scanning = false;

    public BleLink(Activity a, Callback callback) {
        this.activity = a;
        this.cb = callback;
        BluetoothManager bm = (BluetoothManager) a.getSystemService(Context.BLUETOOTH_SERVICE);
        mAdapter = (bm == null) ? null : bm.getAdapter();
    }

    /* ---------------- 主线程分发 ---------------- */
    private void pLog(final String m) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                cb.onLog(m);
            }
        });
    }

    private void pState(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                cb.onState(s);
            }
        });
    }

    private void pConnected(final String name) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                cb.onConnected(name);
            }
        });
    }

    private void pNotify(final byte[] v) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                cb.onNotify(v);
            }
        });
    }

    private void pDisconnected() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                cb.onDisconnected();
            }
        });
    }

    private void pToast(final String m) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, m, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public boolean isSupported() {
        return mAdapter != null;
    }

    public boolean isConnected() {
        return mGatt != null && chW != null;
    }

    public String deviceName() {
        return safeName(mDevice);
    }

    /* ---------------- 诊断 ---------------- */
    private String diag() {
        StringBuilder sb = new StringBuilder();
        sb.append("诊断 SDK ").append(Build.VERSION.SDK_INT);
        sb.append(" · 蓝牙 ").append((mAdapter != null && mAdapter.isEnabled()) ? "开" : "关");
        try {
            sb.append(" · 已配对 ").append(mAdapter.getBondedDevices().size()).append(" 个");
        } catch (Exception e) {
            sb.append(" · 已配对读不到(无 CONNECT 权限)");
        }
        try {
            boolean loc = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            sb.append(" · 位置权限 ").append(loc ? "有" : "无");
        } catch (Exception ignored) {
        }
        try {
            boolean scan = activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
            sb.append(" · 扫描权限 ").append(scan ? "有" : "无");
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    /* ---------------- 扫描 ---------------- */
    public void scan() {
        if (mAdapter == null) {
            pLog("此设备不支持蓝牙");
            return;
        }
        if (!mAdapter.isEnabled()) {
            pLog("蓝牙没开，先打开蓝牙");
            pToast("先打开手机蓝牙");
            try {
                activity.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Exception ignored) {
            }
            return;
        }
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (!need.isEmpty()) {
            activity.requestPermissions(need.toArray(new String[0]), 100);
            return;
        }
        startScan();
    }

    public void onPermissionResult(int requestCode, int[] grantResults) {
        if (requestCode != 100) {
            return;
        }
        boolean all = grantResults.length > 0;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                all = false;
            }
        }
        if (all) {
            startScan();
        } else {
            pLog("权限被拒绝，扫不了。请到系统设置 → 应用 → 拍拍360 里手动打开『附近设备』");
            pToast("权限被拒，去设置里手动开");
            pState("未连接");
        }
    }

    private void startScan() {
        if (scanning) {
            return;
        }
        mScanner = mAdapter.getBluetoothLeScanner();
        if (mScanner == null) {
            pLog("扫描不可用，检查蓝牙");
            pState("未连接");
            return;
        }
        found.clear();
        names.clear();
        scanning = true;
        pState("扫描中…先按云台电源键");
        pLog("开始扫描 BLE 设备，共 " + (SCAN_MS / 1000) + " 秒");
        pLog(diag());
        ScanSettings st = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            mScanner.startScan(null, st, scanCb);
        } catch (Exception e) {
            scanning = false;
            pLog("启动扫描失败：" + e);
            pState("未连接");
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                stopAndPick();
            }
        }, SCAN_MS);
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            String addr = d.getAddress();
            if (found.containsKey(addr)) {
                return;
            }
            String nm = null;
            try {
                nm = d.getName();
            } catch (Exception ignored) {
            }
            if (nm == null && result.getScanRecord() != null) {
                nm = result.getScanRecord().getDeviceName();
            }
            found.put(addr, d);
            names.put(addr, nm);
            pLog("发现 " + (nm == null || nm.isEmpty() ? "(无名)" : nm)
                    + "  " + addr + "  RSSI " + result.getRssi());
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            pLog("扫描失败 errorCode=" + errorCode + "（1=太频繁，2=无法开始，3=内部错误，4=被系统限制）");
            pState("未连接");
        }
    };

    private void stopAndPick() {
        if (!scanning) {
            return;
        }
        scanning = false;
        try {
            if (mScanner != null) {
                mScanner.stopScan(scanCb);
            }
        } catch (Exception ignored) {
        }

        if (found.isEmpty()) {
            pLog("扫描结束：0 个设备");
            pLog(diag());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pLog("Android 12+：确认已允许『附近设备』（含扫描/连接）；小米、华为等机型还要求把『定位服务』总开关打开");
            } else {
                pLog("Android 11 及以下：扫 BLE 必须打开『定位服务』总开关并允许位置权限");
            }
            pLog("云台闲置约 1 分钟就停广播：短按电源键让灯亮，趁亮着再点连接");
            pState("未连接");
            pToast("没扫到：先按电源键亮灯，并检查定位开关");
            return;
        }

        final List<BluetoothDevice> devs = new ArrayList<>(found.values());
        Collections.sort(devs, new Comparator<BluetoothDevice>() {
            @Override
            public int compare(BluetoothDevice a, BluetoothDevice b) {
                String na = names.get(a.getAddress());
                String nb = names.get(b.getAddress());
                int pa = (na != null && na.startsWith("TL")) ? 0 : 1;
                int pb = (nb != null && nb.startsWith("TL")) ? 0 : 1;
                return pa - pb;
            }
        });

        String[] items = new String[devs.size()];
        for (int i = 0; i < devs.size(); i++) {
            BluetoothDevice d = devs.get(i);
            String n = names.get(d.getAddress());
            items[i] = (n == null || n.isEmpty() ? "(无名)" : n) + "   " + d.getAddress();
        }

        pLog("扫描到 " + devs.size() + " 个设备");
        new AlertDialog.Builder(activity)
                .setTitle("选择云台")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        connectTo(devs.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ---------------- 建链路 ---------------- */
    private void connectTo(BluetoothDevice d) {
        closeGatt();
        mDevice = d;
        pState("连接中…");
        pLog("连接 " + safeName(d) + "  " + d.getAddress());
        try {
            mGatt = d.connectGatt(activity, false, gattCb, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            pLog("连接异常：" + e);
            pState("连接失败");
        }
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                pState("已连接，发现服务…");
                pLog("链路已建立，开始发现服务");
                try {
                    g.discoverServices();
                } catch (Exception e) {
                    pLog("discoverServices 异常：" + e);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (mGatt == g) {
                    mGatt = null;
                }
                chW = null;
                chN = null;
                pLog("链路断开 status=" + status);
                pDisconnected();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattCharacteristic w = null;
            BluetoothGattCharacteristic n = null;
            try {
                BluetoothGattService s = g.getService(SVC_UUID);
                if (s != null) {
                    w = s.getCharacteristic(W_UUID);
                    n = s.getCharacteristic(N_UUID);
                }
                if (w == null) {
                    for (BluetoothGattService ss : g.getServices()) {
                        for (BluetoothGattCharacteristic c : ss.getCharacteristics()) {
                            if (c.getUuid().equals(W_UUID)) {
                                w = c;
                            }
                            if (c.getUuid().equals(N_UUID)) {
                                n = c;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                pLog("遍历服务异常：" + e);
            }

            if (w == null) {
                pLog("没找到 fff1（status=" + status + "），可能不是 TL-6C");
                pState("连接失败：无 fff1");
                try {
                    g.close();
                } catch (Exception ignored) {
                }
                mGatt = null;
                return;
            }
            chW = w;
            chN = n;
            pLog("写特征 fff1 OK" + (n != null ? " · 通知 fff4 OK" : "（无 fff4，仍能发指令）"));
            if (n != null) {
                enableNotify(g, n);
            }
            pConnected(safeName(mDevice));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (d != null && d.getUuid().equals(CCCD_UUID)) {
                pLog("通知已开启（CCCD status=" + status + "）");
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            pNotify(c.getValue());
        }
    };

    private void enableNotify(BluetoothGatt g, BluetoothGattCharacteristic n) {
        try {
            g.setCharacteristicNotification(n, true);
            BluetoothGattDescriptor d = n.getDescriptor(CCCD_UUID);
            if (d == null) {
                pLog("没有 CCCD 描述符（不影响发指令）");
                return;
            }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(d);
            }
        } catch (Exception e) {
            pLog("开通知异常：" + e);
        }
    }

    /* ---------------- 发送 ---------------- */
    public static byte[] frame(int cmd) {
        byte[] f = BASE.clone();
        f[5] = (byte) cmd;
        f[6] = 0;
        f[7] = 1;
        return f;
    }

    public boolean send(byte[] v) {
        BluetoothGatt g = mGatt;
        BluetoothGattCharacteristic w = chW;
        if (g == null || w == null) {
            return false;
        }
        try {
            boolean resp = (w.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
            int type = resp ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            if (Build.VERSION.SDK_INT >= 33) {
                return g.writeCharacteristic(w, v, type) == BluetoothGatt.GATT_SUCCESS;
            } else {
                w.setWriteType(type);
                w.setValue(v);
                return g.writeCharacteristic(w);
            }
        } catch (Exception e) {
            pLog("写入异常：" + e);
            return false;
        }
    }

    public boolean toggleSpin() {
        return send(frame(CMD_SPIN));
    }

    public boolean step() {
        return send(frame(CMD_STEP));
    }

    public void disconnect() {
        closeGatt();
        pLog("已主动断开，BLE 链路已释放");
        pDisconnected();
    }

    private void closeGatt() {
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
    }

    public static String safeName(BluetoothDevice d) {
        if (d == null) {
            return "设备";
        }
        try {
            String n = d.getName();
            return (n == null || n.isEmpty()) ? "设备" : n;
        } catch (Exception e) {
            return "设备";
        }
    }

    public static String hexOf(byte[] b) {
        if (b == null || b.length == 0) {
            return "(空)";
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02X ", x & 0xFF));
        }
        return sb.toString().trim();
    }
}
