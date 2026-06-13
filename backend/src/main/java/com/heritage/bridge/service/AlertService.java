package com.heritage.bridge.service;

import com.heritage.bridge.alert.MqttAlertPublisher;
import com.heritage.bridge.entity.Alert;
import com.heritage.bridge.entity.AlertThreshold;
import com.heritage.bridge.entity.Sensor;
import com.heritage.bridge.entity.SensorData;
import com.heritage.bridge.repository.AlertRepository;
import com.heritage.bridge.repository.AlertThresholdRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRepository alertRepository;
    private final AlertThresholdRepository thresholdRepository;
    private final MqttAlertPublisher mqttPublisher;

    @Value("${alert.thresholds.settlement-danger:10.0}")
    private double settlementDanger;
    @Value("${alert.thresholds.settlement-warning:5.0}")
    private double settlementWarning;
    @Value("${alert.thresholds.crack-rate-danger:1.0}")
    private double crackRateDanger;
    @Value("${alert.thresholds.crack-rate-warning:0.5}")
    private double crackRateWarning;
    @Value("${alert.thresholds.strain-danger:150.0}")
    private double strainDanger;
    @Value("${alert.thresholds.strain-warning:100.0}")
    private double strainWarning;

    @Async
    public void evaluateAsync(SensorData data, Sensor sensor) {
        try {
            evaluateSync(data, sensor);
        } catch (Exception e) {
            log.error("异步告警检测失败: {}", e.getMessage());
        }
    }

    @Transactional
    public Alert evaluateSync(SensorData data, Sensor sensor) {
        if (data == null || sensor == null) return null;
        String type = sensor.getType();
        double v = data.getValue().doubleValue();
        switch (type) {
            case "displacement":
                if (Math.abs(v) > settlementDanger) {
                    return fire(sensor.getBridgeId(), sensor.getId(), "settlement",
                            "danger",
                            String.format("桥墩沉降超限: %.2fmm (阈值%.1fmm)", v, settlementDanger),
                            v, settlementDanger);
                } else if (Math.abs(v) > settlementWarning) {
                    return fire(sensor.getBridgeId(), sensor.getId(), "settlement",
                            "warning",
                            String.format("桥墩沉降预警: %.2fmm (阈值%.1fmm)", v, settlementWarning),
                            v, settlementWarning);
                }
                break;
            case "crack":
                if (v > crackRateDanger) {
                    return fire(sensor.getBridgeId(), sensor.getId(), "crack_rate",
                            "danger",
                            String.format("裂缝扩展速率危险: %.3fmm/月 (阈值%.1fmm/月)", v, crackRateDanger),
                            v, crackRateDanger);
                } else if (v > crackRateWarning) {
                    return fire(sensor.getBridgeId(), sensor.getId(), "crack_rate",
                            "warning",
                            String.format("裂缝扩展速率预警: %.3fmm/月 (阈值%.1fmm/月)", v, crackRateWarning),
                            v, crackRateWarning);
                }
                break;
            case "strain":
                if (Math.abs(v) > strainDanger) {
                    return fire(sensor.getBridgeId(), sensor.getId(), "strain",
                            "danger",
                            String.format("拱券应变超限: %.1f微应变 (阈值%.0f)", v, strainDanger),
                            v, strainDanger);
                } else if (Math.abs(v) > strainWarning) {
                    return fire(sensor.getBridgeId(), sensor.getId(), "strain",
                            "warning",
                            String.format("拱券应变预警: %.1f微应变 (阈值%.0f)", v, strainWarning),
                            v, strainWarning);
                }
                break;
            case "temperature":
                // 检测温度变化速率
                break;
        }
        return null;
    }

    @Transactional
    public Alert fire(Long bridgeId, Long sensorId, String type, String level,
                      String message, double value, double threshold) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime oneHourAgo = now.minusHours(1);
        List<Alert> recent = alertRepository.findByBridgeIdAndTypeAndTimestampAfter(
                bridgeId, type, oneHourAgo);
        if (recent != null && recent.stream()
                .anyMatch(a -> level.equals(a.getLevel()))) {
            log.debug("相同级别告警在1小时内已存在 bridge={}, type={}, level={}", bridgeId, type, level);
            return null;
        }
        Alert a = new Alert();
        a.setBridgeId(bridgeId);
        a.setSensorId(sensorId);
        a.setType(type);
        a.setLevel(level);
        a.setMessage(message);
        a.setValue(bd(value));
        a.setThreshold(bd(threshold));
        a.setTimestamp(now);
        a.setAcknowledged(false);
        Alert saved = alertRepository.save(a);
        log.warn("生成告警 bridge={}, sensor={}, type={}, level={}, msg={}",
                bridgeId, sensorId, type, level, message);
        mqttPublisher.publish(saved);
        return saved;
    }

    public List<Alert> findAll() {
        return alertRepository.findAll();
    }

    public List<Alert> findByBridgeId(Long bridgeId) {
        return alertRepository.findByBridgeIdOrderByTimestampDesc(bridgeId);
    }

    public List<Alert> findByLevel(String level) {
        return alertRepository.findByLevelOrderByTimestampDesc(level);
    }

    public List<Alert> findUnacknowledged() {
        return alertRepository.findByAcknowledgedOrderByTimestampDesc(false);
    }

    @Transactional
    public Alert acknowledge(Long id, String user) {
        alertRepository.acknowledgeAlert(id, LocalDateTime.now(), user);
        return alertRepository.findById(id).orElse(null);
    }

    public Long countUnacknowledged() {
        return alertRepository.countAllUnacknowledged();
    }

    public Long countUnacknowledged(Long bridgeId) {
        return alertRepository.countUnacknowledgedByBridgeId(bridgeId);
    }

    public List<AlertThreshold> listThresholds() {
        return thresholdRepository.findAll();
    }

    @Transactional
    public AlertThreshold updateThreshold(Long id, AlertThreshold t) {
        AlertThreshold existing = thresholdRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("阈值配置不存在"));
        existing.setWarningValue(t.getWarningValue());
        existing.setDangerValue(t.getDangerValue());
        existing.setDescription(t.getDescription());
        return thresholdRepository.save(existing);
    }

    private BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(8, RoundingMode.HALF_UP);
    }
}
