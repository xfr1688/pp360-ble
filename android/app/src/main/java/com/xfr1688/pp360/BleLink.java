package com.xfr1688.pp360;

import android.Manifest;
import android.app.Activity;
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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 拍拍360（TL-6C）BLE 链路层。
 *
 * 只负责三件事：扫设备（结果以列表回调给界面）、建链路、发 17 字节帧。
 * 所有回调都统一切到主线程，界面层不用自己 post。
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

    /* ---------------- 扫描结果 ---------------- */
    public static class Dev {
        public final String address;
        public final String name;
        public final int rssi;

        public Dev(String address, String name, int rssi) {
            this.address = address;
            this.name = name;
            this.rssi = rssi;
        }

        public boolean isTl() {
            return name != null && name.toUpperCase(Locale.US).startsWith("TL");
        }

        public String label() {
            return (name == null || name.trim().isEmpty()) ? "未命名设备" : name.trim();
        }

        public String mac() {
            return address == null ? "" : address.toUpperCase(Locale.US);
        }
    }

    public interface Callback {
        void onLog(String msg);
        void onState(String state);
        void onScanFound(Dev dev);
        void onScanFinished(List<Dev> devices);
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

    private final Map<String, Dev> found = new HashMap<>();
    private boolean scanning = false;
    private int mRssi = 0;

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

    private void pFound(final Dev d) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                cb.onScanFound(d);
            }
        });
    }

    private void pFinished(final List<Dev> list) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                cb.onScanFinished(list);
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

    public boolean isSupported() {
        return mAdapter != null;
    }

    public boolean isConnected() {
        return mGatt != null && chW != null;
    }

    public String deviceName() {
        return safeName(mDevice);
    }

    public String deviceMac() {
        try {
            return mDevice == null ? "" : mDevice.getAddress().toUpperCase(Locale.US);
        } catch (Exception e) {
            return "";
        }
    }

    public int deviceRssi() {
        return mRssi;
    }

    /* ---------------- 诊断 ---------------- */
    public String diag() {
        StringBuilder sb = new StringBuilder();
        sb.append("SDK ").append(Build.VERSION.SDK_INT);
        sb.append(" · 蓝牙").append((mAdapter != null && mAdapter.isEnabled()) ? "开" : "关");
        try {
            sb.append(" · 已配对 ").append(mAdapter.getBondedDevices().size()).append(" 台");
        } catch (Exception e) {
            sb.append(" · 已配对读不到");
        }
        try {
            boolean loc = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            sb.append(" · 位置权限").append(loc ? "有" : "无");
        } catch (Exception ignored) {
        }
        try {
            boolean scan = activity.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
            sb.append(" · 扫描权限").append(scan ? "有" : "无");
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
            pLog("蓝牙未开启");
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
            pLog("权限被拒绝：去 设置 → 应用 → 拍拍360 打开『附近设备』");
            pState("权限未授予");
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
        scanning = true;
        pState("搜索中");
        pLog("开始扫描 " + (SCAN_MS / 1000) + " 秒 · " + diag());
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
                finishScan();
            }
        }, SCAN_MS);
    }

    /** 提前结束扫描（切页面、用户点选设备时） */
    public void stopScan() {
        finishScan();
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            String addr = d.getAddress();
            if (addr == null || found.containsKey(addr)) {
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
            Dev dev = new Dev(addr, nm, result.getRssi());
            found.put(addr, dev);
            pFound(dev);
        }

        @Override
        public void onScanFailed(int code) {
            scanning = false;
            pLog("扫描失败 code=" + code + "（1 太频繁 / 2 无法开始 / 3 内部错误 / 4 被系统限制）");
            pFinished(new ArrayList<Dev>());
        }
    };

    private void finishScan() {
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
        List<Dev> list = new ArrayList<>(found.values());
        Collections.sort(list, new Comparator<Dev>() {
            @Override
            public int compare(Dev a, Dev b) {
                if (a.isTl() != b.isTl()) {
                    return a.isTl() ? -1 : 1;
                }
                return b.rssi - a.rssi;
            }
        });
        pLog("扫描结束：" + list.size() + " 个设备");
        pFinished(list);
    }

    /* ---------------- 建链路 ---------------- */
    public void connect(String address) {
        BluetoothDevice d;
        try {
            d = mAdapter.getRemoteDevice(address);
        } catch (Exception e) {
            pLog("地址无效：" + address);
            return;
        }
        connectTo(d);
    }

    private void connectTo(BluetoothDevice d) {
        closeGatt();
        mDevice = d;
        mRssi = 0;
        pState("连接中");
        pLog("连接 " + safeName(d) + "  " + safeAddress(d));
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
                pState("发现服务");
                pLog("链路已建立，发现服务…");
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
                pLog("没找到 fff1（status=" + status + "），不是 TL-6C 或固件不同");
                pState("连接失败");
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
        pLog("已断开");
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
            return (n == null || n.trim().isEmpty()) ? "设备" : n.trim();
        } catch (Exception e) {
            return "设备";
        }
    }

    private static String safeAddress(BluetoothDevice d) {
        try {
            return d == null ? "" : d.getAddress().toUpperCase(Locale.US);
        } catch (Exception e) {
            return "";
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
