#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
pp360-ble · 拍拍360（TL-6A/B/C）蓝牙云台命令行控制器
====================================================
协议来源：com.deepai.paipai v2.2.6 静态逆向（baksmali），本人独立完成，未使用第三方代码。

依赖：pip install bleak

用法：
  python pp360_ctl.py scan               # 扫描附近设备，★ 标记的即为云台
  python pp360_ctl.py step 5             # 走 5 步（每步 20°，等应答）
  python pp360_ctl.py circle             # 整圈 18 步
  python pp360_ctl.py deg 90             # 转到约 90°
  python pp360_ctl.py spin --secs 10     # 连续平滑旋转 10 秒后自动停
  python pp360_ctl.py stop               # 停止（0x06 翻转，0x0E 对本机无效）
  python pp360_ctl.py raw "11 01 55 01 02 02 00 01 11 88 88 88 00 00 00 0D 0A"
  python pp360_ctl.py step 3 --addr FC:58:FA:B1:12:D2   # 指定设备
"""
import argparse
import asyncio
import datetime

from bleak import BleakClient, BleakScanner

SERVICE = "00001910-0000-1000-8000-00805f9b34fb"
WRITE_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
NOTIFY_UUID = "0000fff4-0000-1000-8000-00805f9b34fb"

BASE = bytearray(b"\x11\x01\x55\x01\x02\x00\x00\x00\x11\x88\x88\x88\x00\x00\x00\x0d\x0a")
CMD_STEP, CMD_PHOTO, CMD_RESET, CMD_STOP = 0x02, 0x01, 0x04, 0x0E
CMD_SPIN = 0x06          # 连续旋转开关：每发一次翻转状态（0x0E 停不住）
RING = 18            # 一圈 18 步
STEP_DEG = 360 / RING
STEP_TIMEOUT = 3.0


def frame(cmd, speed=0, refresh=1):
    f = bytearray(BASE)
    f[5], f[6], f[7] = cmd, speed, refresh
    return bytes(f)


def ts():
    return datetime.datetime.now().strftime("%H:%M:%S")


async def scan():
    print("扫描中（10s）…")
    found = await BleakScanner.discover(timeout=10, return_adv=True)
    hit = None
    for dev, adv in sorted(found.values(), key=lambda t: t[1].rssi or -99, reverse=True):
        uuids = " ".join(str(u).lower() for u in (adv.service_uuids or []))
        name = dev.name or ""
        mark = "★" if ("1910" in uuids or name.upper().startswith("TL-")) else " "
        print(f" {mark} {adv.rssi:4} dBm  {dev.address}  {name or '(无名)'}")
        if mark == "★" and not hit:
            hit = dev.address
    if not hit:
        print("未发现云台。请确认设备已开机（短按电源键）。")
    return hit


async def find_device(addr=None):
    if addr:
        return addr
    found = await BleakScanner.discover(timeout=8, return_adv=True)
    for dev, adv in found.values():
        name = (dev.name or "").upper()
        uuids = " ".join(str(u).lower() for u in (adv.service_uuids or []))
        if name.startswith("TL-") or "1910" in uuids:
            print(f"发现云台：{dev.address}  {dev.name}")
            return dev
    raise RuntimeError("没找到云台，请先开机；或用 --addr 指定地址")


async def do_steps(addr, n, timeout=STEP_TIMEOUT):
    target = await find_device(addr)
    async with BleakClient(target, timeout=20) as cli:
        q = asyncio.Queue()
        await cli.start_notify(NOTIFY_UUID, lambda _c, d: q.put_nowait(bytes(d)))
        print(f"[{ts()}] 已连接，走 {n} 步（约 {n * STEP_DEG:.0f}°）")
        ok = 0
        for i in range(n):
            while not q.empty():
                q.get_nowait()
            t0 = datetime.datetime.now()
            await cli.write_gatt_char(WRITE_UUID, frame(CMD_STEP, 0, 1), response=True)
            try:
                ack = await asyncio.wait_for(q.get(), timeout)
                ok += 1
                dt = (datetime.datetime.now() - t0).total_seconds()
                print(f"  步{i + 1:02d}/{n}  到位 {dt:.2f}s  ack=0x{ack[10]:02X}")
            except asyncio.TimeoutError:
                print(f"  步{i + 1:02d}/{n}  {timeout:.0f}s 无应答")
            await asyncio.sleep(0.12)
        # 0x0E 对本机（固件 V3.9）无效，步进结束后不发停止帧
        print(f"[{ts()}] 完成 {ok}/{n} 步")


async def do_spin(addr, secs):
    target = await find_device(addr)
    async with BleakClient(target, timeout=20) as cli:
        print(f"[{ts()}] 发 0x06 启动连续旋转，持续 {secs:.1f}s")
        await cli.write_gatt_char(WRITE_UUID, frame(CMD_SPIN, 0, 1), response=True)
        await asyncio.sleep(secs)
        await cli.write_gatt_char(WRITE_UUID, frame(CMD_SPIN, 0, 1), response=True)
        print(f"[{ts()}] 已再发 0x06，旋转停止（0x06 为翻转指令）")


async def do_stop(addr):
    target = await find_device(addr)
    async with BleakClient(target, timeout=20) as cli:
        await cli.write_gatt_char(WRITE_UUID, frame(CMD_SPIN, 0, 1), response=True)
        print(f"[{ts()}] 已发 0x06（翻转一次）。若正在转则停止，若已停则会开始转——再执行一次即可复位")


async def main():
    ap = argparse.ArgumentParser(description="拍拍360 云台控制器")
    ap.add_argument("action", choices=["scan", "step", "circle", "deg", "spin", "stop", "raw"])
    ap.add_argument("value", nargs="?", help="步数 / 角度 / 十六进制帧")
    ap.add_argument("--addr", help="设备地址，省略则自动发现")
    ap.add_argument("--speed", type=int, default=0, help="档位，TL-6C 只支持 0")
    ap.add_argument("--secs", type=float, default=10.0, help="spin 持续秒数")
    ap.add_argument("--timeout", type=float, default=STEP_TIMEOUT, help="单步等待秒数")
    a = ap.parse_args()

    if a.action == "scan":
        await scan()
        return

    if a.action == "spin":
        await do_spin(a.addr, a.secs)
        return

    if a.action == "stop":
        await do_stop(a.addr)
        return

    if a.action in ("step", "circle", "deg"):
        if a.action == "step":
            n = int(a.value or 1)
        elif a.action == "circle":
            n = RING
        else:
            n = max(1, round(float(a.value or 0) / STEP_DEG))
        await do_steps(a.addr, n, a.timeout)
        return

    target = await find_device(a.addr)
    async with BleakClient(target, timeout=20) as cli:
        data = bytes.fromhex((a.value or "").replace(" ", ""))
        if len(data) != 17:
            raise SystemExit("帧必须为 17 字节")
        await cli.write_gatt_char(WRITE_UUID, data, response=True)
        print(f"[{ts()}] 已发送 {data.hex(' ').upper()}")


if __name__ == "__main__":
    asyncio.run(main())
