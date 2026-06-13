#!/usr/bin/env python3
"""
古代石拱桥传感器模拟器
模拟 10 座石拱桥的振动传感器、位移计、裂缝计、温度传感器
每 10 分钟通过 HTTP 上报数据
"""
import math
import random
import time
import json
import requests
from datetime import datetime, timedelta
from dataclasses import dataclass, field
from typing import List, Dict

API_BASE = "http://localhost:8080/api"
UPLOAD_URL = f"{API_BASE}/data/batch"
BRIDGES_URL = "http://localhost:8080/api/bridges"
SENSORS_URL = "http://localhost:8080/api/sensors"

BRIDGE_CONFIGS = [
    {"id": 1, "name": "赵州桥", "spans": 1, "base_strain": 80, "base_settlement": 2.5, "base_crack": 1.2},
    {"id": 2, "name": "卢沟桥", "spans": 11, "base_strain": 95, "base_settlement": 4.2, "base_crack": 2.1},
    {"id": 3, "name": "广济桥", "spans": 19, "base_strain": 70, "base_settlement": 3.0, "base_crack": 1.5},
    {"id": 4, "name": "洛阳桥", "spans": 46, "base_strain": 60, "base_settlement": 3.5, "base_crack": 1.0},
    {"id": 5, "name": "宝带桥", "spans": 53, "base_strain": 85, "base_settlement": 5.8, "base_crack": 3.2},
    {"id": 6, "name": "灞桥", "spans": 16, "base_strain": 110, "base_settlement": 9.5, "base_crack": 4.8},
    {"id": 7, "name": "安平桥", "spans": 362, "base_strain": 55, "base_settlement": 4.0, "base_crack": 1.8},
    {"id": 8, "name": "五亭桥", "spans": 3, "base_strain": 75, "base_settlement": 2.0, "base_crack": 0.8},
    {"id": 9, "name": "十字桥", "spans": 5, "base_strain": 100, "base_settlement": 6.5, "base_crack": 2.5},
    {"id": 10, "name": "风雨桥", "spans": 5, "base_strain": 65, "base_settlement": 3.8, "base_crack": 1.1},
]

SENSOR_TYPES = {
    "strain": {"unit": "μ ε", "name_prefix": "应变计", "count_per_arch": 4},
    "displacement": {"unit": "mm", "name_prefix": "位移计", "count_per_pier": 2},
    "crack": {"unit": "mm", "name_prefix": "裂缝计", "count_per_arch": 1},
    "temperature": {"unit": "°C", "name_prefix": "温度传感器", "count_total": 2},
    "vibration": {"unit": "mm/s²", "name_prefix": "振动传感器", "count_per_arch": 2},
}


@dataclass
class SensorSim:
    id: int
    code: str
    bridge_id: int
    type: str
    name: str
    base_value: float
    noise: float
    trend: float = 0.0
    last_value: float = 0.0
    position: Dict[str, float] = field(default_factory=dict)

    def generate(self, t: float, temperature: float) -> float:
        season = math.sin(2 * math.pi * (t / 365.0))
        daily = math.sin(2 * math.pi * (t * 24 % 24 / 24.0))
        temp_effect = 0 if self.type == "temperature" else (temperature - 15) * 0.8
        drift = self.trend * t

        if self.type == "strain":
            value = self.base_value + season * 15 + daily * 3 + temp_effect * 0.5 + drift
        elif self.type == "displacement":
            value = self.base_value + season * 1.5 + daily * 0.3 + drift
        elif self.type == "crack":
            value = self.base_value + season * 0.2 + daily * 0.05 + drift * 1.2
        elif self.type == "temperature":
            value = 15 + season * 18 + daily * 5
        elif self.type == "vibration":
            value = 0.5 + abs(daily) * 0.8 + random.gauss(0, 0.1)
        else:
            value = self.base_value

        value += random.gauss(0, self.noise)
        self.last_value = max(0, value) if self.type != "temperature" else value
        return self.last_value


class BridgeSimulator:
    def __init__(self, bridge_cfg: dict, start_time: datetime = None):
        self.cfg = bridge_cfg
        self.bridge_id = bridge_cfg["id"]
        self.bridge_name = bridge_cfg["name"]
        self.sensors: List[SensorSim] = []
        self.t = 0.0
        self._init_sensors()

    def _init_sensors(self):
        sid = (self.bridge_id - 1) * 100
        spans = self.cfg["spans"]
        piers = spans + 1

        n_strain = min(spans * 4, 16)
        for i in range(n_strain):
            sid += 1
            self.sensors.append(SensorSim(
                id=sid,
                code=f"ST-{self.bridge_id:03d}-{i+1:03d}",
                bridge_id=self.bridge_id,
                type="strain",
                name=f"拱券应变计-{i+1}",
                base_value=self.cfg["base_strain"] * (0.8 + 0.4 * random.random()),
                noise=2.0,
                trend=0.05 * (random.random() - 0.3),
                position={"x": (i / n_strain - 0.5) * 30, "y": 5 + random.random() * 2, "z": random.random() * 4 - 2}
            ))

        for i in range(min(piers * 2, 8)):
            sid += 1
            self.sensors.append(SensorSim(
                id=sid,
                code=f"DP-{self.bridge_id:03d}-{i+1:03d}",
                bridge_id=self.bridge_id,
                type="displacement",
                name=f"桥墩位移计-{i+1}",
                base_value=self.cfg["base_settlement"] * (0.7 + 0.6 * random.random()),
                noise=0.15,
                trend=0.02 * (random.random() + 0.5),
                position={"x": -15 + i * 5, "y": 0.5, "z": random.random() * 3 - 1.5}
            ))

        n_crack = max(2, int(spans * 0.5))
        for i in range(min(n_crack, 6)):
            sid += 1
            self.sensors.append(SensorSim(
                id=sid,
                code=f"CK-{self.bridge_id:03d}-{i+1:03d}",
                bridge_id=self.bridge_id,
                type="crack",
                name=f"裂缝计-{i+1}",
                base_value=self.cfg["base_crack"] * (0.5 + random.random()),
                noise=0.05,
                trend=0.003 + 0.01 * random.random(),
                position={"x": (random.random() - 0.5) * 20, "y": 2 + random.random() * 3, "z": random.random() * 3 - 1.5}
            ))

        for i in range(2):
            sid += 1
            self.sensors.append(SensorSim(
                id=sid,
                code=f"TP-{self.bridge_id:03d}-{i+1:03d}",
                bridge_id=self.bridge_id,
                type="temperature",
                name=f"环境温度-{i+1}",
                base_value=15.0,
                noise=0.3,
                position={"x": 0, "y": 8, "z": i * 3 - 1.5}
            ))

        for i in range(min(spans * 2, 8)):
            sid += 1
            self.sensors.append(SensorSim(
                id=sid,
                code=f"VB-{self.bridge_id:03d}-{i+1:03d}",
                bridge_id=self.bridge_id,
                type="vibration",
                name=f"振动传感器-{i+1}",
                base_value=0.5,
                noise=0.1,
                position={"x": -14 + i * 4, "y": 4, "z": random.random() * 2 - 1}
            ))

    def step(self, dt_days: float = 1.0 / 144):
        self.t += dt_days
        temp_sensor = next((s for s in self.sensors if s.type == "temperature"), None)
        temp = temp_sensor.generate(self.t, 15) if temp_sensor else 15
        readings = []
        for s in self.sensors:
            if s.type == "temperature":
                continue
            val = s.generate(self.t, temp)
            readings.append({
                "sensorCode": s.code,
                "bridgeId": self.bridge_id,
                "value": round(val, 4),
                "temperature": round(temp, 2),
                "timestamp": None
            })
        if temp_sensor:
            readings.append({
                "sensorCode": temp_sensor.code,
                "bridgeId": self.bridge_id,
                "value": round(temp, 2),
                "temperature": round(temp, 2),
                "timestamp": None
            })
        return readings


class HeritageSimulator:
    def __init__(self):
        self.bridges = [BridgeSimulator(cfg) for cfg in BRIDGE_CONFIGS]
        self.current_time = datetime.now()

    def step_all(self, dt_minutes: int = 10):
        dt_days = dt_minutes / (24 * 60)
        self.current_time += timedelta(minutes=dt_minutes)
        all_readings = []
        for b in self.bridges:
            readings = b.step(dt_days)
            ts = self.current_time.strftime("%Y-%m-%dT%H:%M:%S")
            for r in readings:
                r["timestamp"] = ts
            all_readings.extend(readings)
        return all_readings

    def upload(self, data: list) -> bool:
        try:
            resp = requests.post(UPLOAD_URL, json=data, timeout=10)
            if resp.status_code == 200:
                result = resp.json()
                print(f"[{self.current_time}] 上报成功: {len(data)} 条, code={resp.status_code}, count={result.get('data', '?')}")
                return True
            else:
                print(f"[{self.current_time}] 上报失败: HTTP {resp.status_code}: {resp.text[:200]}")
                return False
        except Exception as e:
            print(f"[{self.current_time}] 上报异常: {e}")
            return False

    def run_realtime(self, speedup: int = 1):
        print(f"=== 古桥传感器模拟器启动 ===")
        print(f"监测桥梁数: {len(self.bridges)}")
        total_sensors = sum(len(b.sensors) for b in self.bridges)
        print(f"传感器总数: {total_sensors}")
        print(f"上报频率: 每 10 分钟 (加速 {speedup}x)")
        print(f"API 地址: {UPLOAD_URL}")
        print("=" * 40)

        interval_sec = 600 / speedup
        while True:
            try:
                data = self.step_all(10)
                self.upload(data)
                time.sleep(interval_sec)
            except KeyboardInterrupt:
                print("\n模拟器已停止")
                break

    def run_historcal(self, days: int = 365):
        """历史数据回灌：从1年前开始，每10分钟一条，共 ~52560 条/桥"""
        print(f"开始回灌 {days} 天历史数据...")
        start = datetime.now() - timedelta(days=days)
        self.current_time = start
        total = 0
        steps = days * 24 * 6  # 每天144个10分钟
        batch_size = 500
        batch = []

        for i in range(steps):
            data = self.step_all(10)
            batch.extend(data)
            if len(batch) >= batch_size:
                self.upload(batch)
                total += len(batch)
                batch = []
            if i % 100 == 0:
                progress = i / steps * 100
                print(f"进度: {progress:.1f}% ({i}/{steps}), 已上报 {total} 条")

        if batch:
            self.upload(batch)
            total += len(batch)
        print(f"历史数据回灌完成, 共 {total} 条")


def main():
    import argparse
    parser = argparse.ArgumentParser(description="古代石拱桥传感器模拟器")
    parser.add_argument("--mode", choices=["realtime", "historical"], default="realtime",
                        help="运行模式: realtime实时模拟, historical历史数据回灌")
    parser.add_argument("--speedup", type=int, default=1, help="实时模式加速倍数")
    parser.add_argument("--days", type=int, default=365, help="历史模式回灌天数")
    parser.add_argument("--api", default="http://localhost:8080/api/data", help="API基础地址")
    args = parser.parse_args()

    global UPLOAD_URL
    UPLOAD_URL = f"{args.api}/batch"

    sim = HeritageSimulator()
    if args.mode == "historical":
        sim.run_historcal(args.days)
    else:
        sim.run_realtime(args.speedup)


if __name__ == "__main__":
    main()
