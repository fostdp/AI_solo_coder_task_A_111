package com.heritage.bridge.controller;

import com.heritage.bridge.dto.ApiResponse;
import com.heritage.bridge.dto.DamagePredictionRequestDTO;
import com.heritage.bridge.entity.DamagePrediction;
import com.heritage.bridge.service.DamagePredictionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/damage")
@Validated
@RequiredArgsConstructor
public class DamageController {

    private final DamagePredictionService damagePredictionService;

    @PostMapping("/calculate")
    public ResponseEntity<ApiResponse<DamagePrediction>> calculate(@RequestBody DamagePredictionRequestDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(damagePredictionService.calculate(dto)));
    }

    @GetMapping("/predict/{bridgeId}")
    public ResponseEntity<ApiResponse<DamagePrediction>> latestPrediction(@PathVariable Long bridgeId) {
        return ResponseEntity.ok(ApiResponse.success(damagePredictionService.findLatestByBridgeId(bridgeId)));
    }
}
