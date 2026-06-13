package com.heritage.bridge.service;

import com.heritage.bridge.dto.SensorDataUploadDTO;
import com.heritage.bridge.dto.TrendDataDTO;
import com.heritage.bridge.entity.Sensor;
import com.heritage.bridge.entity.SensorData;
import com.heritage.bridge.repository.SensorDataRepository;
import com.heritage.bridge.repository.SensorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataService {

    private final SensorDataRepository dataRepository;
    private final SensorRepository sensorRepository;
    private final AlertService alertService;

    @Transactional
    public SensorData ingest(SensorDataUploadDTO dto) {
        Sensor sensor = sensorRepository.findByCode(dto.getSensorCode())
                .orElseThrow(() -> new IllegalArgumentException("传感器编码不存在: " + dto.getSensorCode()));

        SensorData sd = new SensorData();
        sd.setSensorId(sensor.getId());
        sd.setBridgeId(dto.getBridgeId() != null ? dto.getBridgeId() : sensor.getBridgeId());
        sd.setValue(dto.getValue());
        sd.setTemperature(dto.getTemperature());
        sd.setTimestamp(dto.getTimestamp() != null ? dto.getTimestamp() : LocalDateTime.now());

        SensorData saved = dataRepository.save(sd);

        alertService.evaluateAsync(sd, sensor);
        return saved;
    }

    @Transactional
    public List<SensorData> batchIngest(List<SensorDataUploadDTO> dtos) {
        List<SensorData> result = new ArrayList<>();
        for (SensorDataUploadDTO dto : dtos) {
            try {
                result.add(ingest(dto));
            } catch (Exception e) {
                log.warn("数据入库失败 sensor={}, err={}", dto.getSensorCode(), e.getMessage());
            }
        }
        return result;
    }

    public List<SensorData> findLatestByBridgeId(Long bridgeId) {
        LocalDateTime since = LocalDateTime.now().minusHours(2);
        List<SensorData> all = dataRepository.findLatestByBridgeId(bridgeId, since);
        Map<Long, SensorData> latest = new LinkedHashMap<>();
        for (SensorData sd : all) {
            latest.putIfAbsent(sd.getSensorId(), sd);
        }
        return new ArrayList<>(latest.values());
    }

    public List<SensorData> findHistory(Long bridgeId, Long sensorId, LocalDateTime from, LocalDateTime to) {
        return dataRepository.findByBridgeIdAndSensorIdAndTimestampBetweenOrderByTimestampAsc(
                bridgeId, sensorId, from, to);
    }

    public List<TrendDataDTO> getSensorTrend(Long sensorId, LocalDateTime from) {
        List<SensorData> raw = dataRepository.findTrendDataBySensorId(sensorId, from);
        if (raw.size() <= 365 * 24 * 6) {
            return raw.stream()
                    .map(sd -> new TrendDataDTO(sd.getTimestamp(), sd.getValue(), sd.getValue()))
                    .collect(Collectors.toList());
        }
        List<Object[]> daily = dataRepository.findDailyTrendData(
                raw.get(0).getBridgeId(), sensorId, from);
        return daily.stream().map(r -> new TrendDataDTO(
                ((java.sql.Timestamp) r[2]).toLocalDateTime(),
                (BigDecimal) r[3],
                (BigDecimal) r[3]
        )).collect(Collectors.toList());
    }

    public SensorData findLatestBySensorId(Long sensorId) {
        return dataRepository.findFirstBySensorIdOrderByTimestampDesc(sensorId).orElse(null);
    }

    @Scheduled(cron = "0 5 0 * * ?")
    @Transactional
    public void dailyHealthCheck() {
        log.info("启动每日健康检查任务");
        List<Sensor> all = sensorRepository.findAll();
        for (Sensor s : all) {
            SensorData latest = findLatestBySensorId(s.getId());
            if (latest != null) {
                alertService.evaluateSync(latest, s);
            }
        }
        log.info("每日健康检查完成, 处理传感器={}", all.size());
    }
}
