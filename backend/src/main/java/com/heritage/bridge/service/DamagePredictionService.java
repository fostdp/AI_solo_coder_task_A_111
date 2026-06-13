package com.heritage.bridge.service;

import com.heritage.bridge.dto.DamagePredictionRequestDTO;
import com.heritage.bridge.entity.Bridge;
import com.heritage.bridge.entity.DamagePrediction;
import com.heritage.bridge.entity.SensorData;
import com.heritage.bridge.repository.BridgeRepository;
import com.heritage.bridge.repository.DamagePredictionRepository;
import com.heritage.bridge.repository.SensorDataRepository;
import com.heritage.bridge.simulation.BayesianParisCalibrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DamagePredictionService {

    private final BayesianParisCalibrator calibrator;
    private final DamagePredictionRepository repository;
    private final BridgeRepository bridgeRepository;
    private final SensorDataRepository sensorDataRepository;

    @Value("${damage.prediction.paris-c-default:1.0e-12}")
    private double defaultC;

    @Value("${damage.prediction.paris-m-default:3.0}")
    private double defaultM;

    @Value("${damage.prediction.years-to-predict:5}")
    private int defaultYears;

    @Value("${damage.prediction.annual-temperature-cycles:365}")
    private int defaultCycles;

    @Value("${damage.prediction.critical-crack-length:20.0}")
    private double criticalLength;

    @Value("${damage.prediction.maintenance-threshold:10.0}")
    private double maintenanceThreshold;

    @Value("${damage.prediction.bayesian-mcmc-samples:10000}")
    private int defaultMcmcSamples;

    @Value("${damage.prediction.bayesian-mcmc-burnin:2000}")
    private int defaultBurnin;

    @Value("${damage.prediction.prior-c-mean:1.0e-12}")
    private double priorCmean;

    @Value("${damage.prediction.prior-c-std:5.0e-13}")
    private double priorCstd;

    @Value("${damage.prediction.prior-m-mean:3.0}")
    private double priorMmean;

    @Value("${damage.prediction.prior-m-std:0.5}")
    private double priorMstd;

    @Transactional
    public DamagePrediction calculate(DamagePredictionRequestDTO dto) {
        Bridge bridge = bridgeRepository.findById(dto.getBridgeId())
                .orElseThrow(() -> new IllegalArgumentException("桥梁不存在"));

        double cDefault = dto.getParisC() != null ? dto.getParisC().doubleValue() : defaultC;
        double mDefault = dto.getParisM() != null ? dto.getParisM().doubleValue() : defaultM;
        int years = dto.getYearsToPredict() != null ? dto.getYearsToPredict() : defaultYears;
        int cycles = dto.getAnnualCycles() != null ? dto.getAnnualCycles() : defaultCycles;
        double dS = dto.getStressAmplitude() != null ? dto.getStressAmplitude().doubleValue() : 5e6;
        boolean bayesian = Boolean.TRUE.equals(dto.getEnableBayesian());

        double C = cDefault;
        double M = mDefault;
        BayesianParisCalibrator.CalibrationResult cal = null;

        if (bayesian) {
            LocalDateTime from = LocalDateTime.now().minusYears(1);
            List<SensorData> histData = sensorDataRepository
                    .findBySensorIdAndTimestampBetweenOrderByTimestampAsc(
                            dto.getCrackSensorId(), from, LocalDateTime.now());
            List<double[]> history = calibrator.buildHistoryFromData(histData);
            if (history.size() < 2) {
                log.warn("历史数据不足(条),使用先验参数", history.size());
            }

            BayesianParisCalibrator.CalibrationInput in = new BayesianParisCalibrator.CalibrationInput();
            in.setInitialC(cDefault);
            in.setInitialM(mDefault);
            in.setPriorC_mean(dto.getPriorC_mean() != null ? dto.getPriorC_mean().doubleValue() : priorCmean);
            in.setPriorC_std(dto.getPriorC_std() != null ? dto.getPriorC_std().doubleValue() : priorCstd);
            in.setPriorM_mean(dto.getPriorM_mean() != null ? dto.getPriorM_mean().doubleValue() : priorMmean);
            in.setPriorM_std(dto.getPriorM_std() != null ? dto.getPriorM_std().doubleValue() : priorMstd);
            in.setMcmcSamples(dto.getMcmcSamples() != null ? dto.getMcmcSamples() : defaultMcmcSamples);
            in.setBurnin(dto.getMcmcBurnin() != null ? dto.getMcmcBurnin() : defaultBurnin);
            in.setStressAmplitude(dS);
            in.setAnnualCycles(cycles);
            in.setHistory(history);

            cal = calibrator.calibrate(in);
            C = cal.getC_post_mean();
            M = cal.getM_post_mean();
            log.info("贝叶斯标定完成 bridge={}: C={} (±{}), m={} (±{}), samples={}",
                    bridge.getName(), C, cal.getC_post_std(), M, cal.getM_post_std(), cal.getSamples());
        }

        double a0 = dto.getInitialLength() != null ? dto.getInitialLength().doubleValue() * 1e-3
                : getInitialCrackLength(dto.getCrackSensorId());

        List<DamagePrediction.YearPrediction> preds = new ArrayList<>();
        int maintenanceYear = -1;
        double cur = a0;
        double criticalM = criticalLength * 1e-3;
        double maintM = maintenanceThreshold * 1e-3;
        for (int y = 1; y <= years; y++) {
            double aNext = BayesianParisCalibrator.integrateParis(cur, C, M, dS, 1, cycles);
            String risk = assessRisk(aNext, criticalM, maintM);
            DamagePrediction.YearPrediction yp = new DamagePrediction.YearPrediction();
            yp.setYear(LocalDateTime.now().getYear() + y);
            yp.setLength(bd6(aNext * 1000));
            yp.setRisk(risk);
            preds.add(yp);
            if (aNext >= maintM && maintenanceYear < 0) maintenanceYear = yp.getYear();
            cur = aNext;
            if (cur > criticalM * 2) break;
        }

        DamagePrediction dp = new DamagePrediction();
        dp.setBridgeId(bridge.getId());
        dp.setCrackSensorId(dto.getCrackSensorId());
        dp.setInitialLength(bd6(a0 * 1000));
        dp.setParisC(bd12(C));
        dp.setParisM(bd4(M));
        dp.setPredictionData(preds);
        dp.setMaintenanceYear(maintenanceYear > 0 ? maintenanceYear : null);
        dp.setRecommendation(buildRecommendation(maintenanceYear, cur * 1000, criticalLength, bayesian));
        dp.setIsBayesian(bayesian);
        if (cal != null) {
            dp.setParisCPosteriorMean(bd12(cal.getC_post_mean()));
            dp.setParisCPosteriorStd(bd12(cal.getC_post_std()));
            dp.setParisMPosteriorMean(bd6(cal.getM_post_mean()));
            dp.setParisMPosteriorStd(bd6(cal.getM_post_std()));
            dp.setMcmcSamples(cal.getSamples());
        }
        return repository.save(dp);
    }

    private String assessRisk(double a, double critical, double maint) {
        if (a >= critical) return "danger";
        if (a >= maint) return "warning";
        return "low";
    }

    private String buildRecommendation(int maintenanceYear, double curLen, double criticalLen, boolean bayesian) {
        if (maintenanceYear > 0) {
            return String.format(
                    "建议于%d年前完成裂缝补强维修，当前预测裂缝扩展率%s，推荐方法：环氧注浆+碳纤维布加固%s",
                    maintenanceYear,
                    String.format("%.2fmm/年", Math.max(0, curLen / 5)),
                    bayesian ? "（参数已基于历史数据贝叶斯标定）" : "（采用经验默认参数，建议积累6个月以上数据后重新标定）");
        }
        return String.format("5年内预测最大裂缝%.2fmm低于维修阈值%.0fmm，建议持续监测，每季度复核",
                curLen, criticalLen);
    }

    private double getInitialCrackLength(Long sensorId) {
        return sensorDataRepository.findFirstBySensorIdOrderByTimestampDesc(sensorId)
                .map(sd -> Math.max(0.5e-3, sd.getValue().doubleValue() * 1e-3))
                .orElse(1.0e-3);
    }

    public DamagePrediction findLatestByBridgeId(Long bridgeId) {
        return repository.findTopByBridgeIdOrderByPredictedAtDesc(bridgeId)
                .orElseThrow(() -> new IllegalArgumentException("该桥梁无预测记录"));
    }

    public List<DamagePrediction> findByBridgeId(Long bridgeId) {
        return repository.findByBridgeIdOrderByPredictedAtDesc(bridgeId);
    }

    private BigDecimal bd4(double v) { return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP); }
    private BigDecimal bd6(double v) { return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP); }
    private BigDecimal bd12(double v) { return BigDecimal.valueOf(v).setScale(12, RoundingMode.HALF_UP); }
}
