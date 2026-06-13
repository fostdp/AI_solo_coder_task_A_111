package com.heritage.bridge.service;

import com.heritage.bridge.dto.FemRequestDTO;
import com.heritage.bridge.entity.Bridge;
import com.heritage.bridge.entity.FemResult;
import com.heritage.bridge.repository.BridgeRepository;
import com.heritage.bridge.repository.FemResultRepository;
import com.heritage.bridge.repository.SensorDataRepository;
import com.heritage.bridge.repository.SensorRepository;
import com.heritage.bridge.simulation.FemSolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FemSimulationService {

    private final FemSolver femSolver;
    private final FemResultRepository femResultRepository;
    private final BridgeRepository bridgeRepository;
    private final SensorRepository sensorRepository;
    private final SensorDataRepository sensorDataRepository;

    @Value("${simulation.fem.default-mc-samples:1000}")
    private int defaultMcSamples;

    @Value("${simulation.fem.default-modulus-cov:0.15}")
    private double defaultModulusCov;

    @Value("${simulation.fem.default-strength-cov:0.20}")
    private double defaultStrengthCov;

    @Value("${simulation.fem.temperature-delta:25.0}")
    private double defaultTempDelta;

    @Value("${simulation.fem.traffic-load:30000.0}")
    private double defaultTrafficLoad;

    @Transactional
    public FemResult analyze(FemRequestDTO dto) {
        Bridge bridge = bridgeRepository.findById(dto.getBridgeId())
                .orElseThrow(() -> new IllegalArgumentException("桥梁不存在: " + dto.getBridgeId()));

        FemSolver.SolverParams p = new FemSolver.SolverParams();
        p.setElementCount(dto.getElementCount() != null ? dto.getElementCount() : FemSolver.DEFAULT_ELEMENTS);
        p.setEmean(bridge.getStoneModulus().doubleValue() * 1e9);
        p.setNu(bridge.getStonePoisson().doubleValue());
        p.setFcMean(bridge.getStoneStrength().doubleValue() * 1e6);
        p.setRiseSpanRatio(bridge.getRiseSpanRatio().doubleValue());
        p.setSpanLength(bridge.getSpanLength().doubleValue());
        p.setPierThickness(bridge.getPierThickness().doubleValue());
        p.setStoneStrength(bridge.getStoneStrength().doubleValue());
        p.setTrafficLoad(dto.getCustomLoad() != null ? dto.getCustomLoad().doubleValue() : defaultTrafficLoad);
        p.setTemperatureDelta(dto.getTemperatureDelta() != null ? dto.getTemperatureDelta().doubleValue() : defaultTempDelta);
        boolean stochastic = Boolean.TRUE.equals(dto.getEnableStochastic());
        p.setStochastic(stochastic);
        p.setMcSamples(dto.getMonteCarloSamples() != null ? dto.getMonteCarloSamples() : defaultMcSamples);
        p.setModulusCov(dto.getModulusCov() != null ? dto.getModulusCov().doubleValue() : defaultModulusCov);
        p.setStrengthCov(dto.getStrengthCov() != null ? dto.getStrengthCov().doubleValue() : defaultStrengthCov);

        log.info("开始FEM分析: bridge={}, stochastic={}, Emean={}GPa, COV={}",
                bridge.getName(), stochastic, bridge.getStoneModulus(), p.getModulusCov());

        FemSolver.Result r = femSolver.solve(bridge, p);

        FemResult fr = new FemResult();
        fr.setBridgeId(bridge.getId());
        fr.setLoadType(dto.getLoadType());
        fr.setNodeData(r.getNodes());
        fr.setMaxStress(bd(r.getMaxStress()));
        fr.setMaxStrain(bd(r.getMaxStrain()));
        fr.setSafetyFactor(bd4(r.getSafetyFactor()));
        fr.setIsStochastic(stochastic);
        if (stochastic) {
            fr.setMcSamples(p.getMcSamples());
            fr.setStressP95(bd(r.getStressP95()));
            fr.setStressP99(bd(r.getStressP99()));
            fr.setPfFailure(bd10(r.getPfFailure()));
            fr.setModulusCov(bd4(p.getModulusCov()));
        }
        FemResult saved = femResultRepository.save(fr);
        log.info("FEM分析完成: bridge={}, maxStress={}Pa, SF={}, P_f={}",
                bridge.getName(), r.getMaxStress(), r.getSafetyFactor(), r.getPfFailure());
        return saved;
    }

    public FemResult findLatestByBridgeId(Long bridgeId) {
        return femResultRepository.findTopByBridgeIdOrderByCalculatedAtDesc(bridgeId)
                .orElseThrow(() -> new IllegalArgumentException("该桥梁无仿真结果: " + bridgeId));
    }

    @Transactional(readOnly = true)
    public FemResult verify() {
        long bridgeId = bridgeRepository.findAll().stream().findFirst()
                .map(Bridge::getId).orElse(1L);
        FemResult deterministic = analyze(buildReq(bridgeId, false));
        return deterministic;
    }

    private FemRequestDTO buildReq(Long bridgeId, boolean stochastic) {
        FemRequestDTO d = new FemRequestDTO();
        d.setBridgeId(bridgeId);
        d.setLoadType("static");
        d.setEnableStochastic(stochastic);
        if (stochastic) {
            d.setMonteCarloSamples(defaultMcSamples);
            d.setModulusCov(bd4(defaultModulusCov));
            d.setStrengthCov(bd4(defaultStrengthCov));
        }
        return d;
    }

    private BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(8, RoundingMode.HALF_UP);
    }
    private BigDecimal bd4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }
    private BigDecimal bd10(double v) {
        return BigDecimal.valueOf(v).setScale(10, RoundingMode.HALF_UP);
    }

    public List<FemResult> findByBridgeId(Long bridgeId) {
        return femResultRepository.findByBridgeIdOrderByCalculatedAtDesc(bridgeId);
    }
}
