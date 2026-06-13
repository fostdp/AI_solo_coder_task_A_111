package com.heritage.bridge.controller;

import com.heritage.bridge.dto.ApiResponse;
import com.heritage.bridge.dto.FemRequestDTO;
import com.heritage.bridge.entity.FemResult;
import com.heritage.bridge.service.FemSimulationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/simulation")
@Validated
@RequiredArgsConstructor
public class SimulationController {

    private final FemSimulationService femSimulationService;

    @PostMapping("/fem")
    public ResponseEntity<ApiResponse<FemResult>> femAnalysis(@RequestBody FemRequestDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(femSimulationService.analyze(dto)));
    }

    @GetMapping("/fem/{bridgeId}")
    public ResponseEntity<ApiResponse<FemResult>> latestFemResult(@PathVariable Long bridgeId) {
        return ResponseEntity.ok(ApiResponse.success(femSimulationService.findLatestByBridgeId(bridgeId)));
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<FemResult>> verify() {
        return ResponseEntity.ok(ApiResponse.success(femSimulationService.verify()));
    }
}
