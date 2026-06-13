package com.heritage.bridge.controller;

import com.heritage.bridge.common.ApiResponse;
import com.heritage.bridge.entity.Alert;
import com.heritage.bridge.entity.AlertThreshold;
import com.heritage.bridge.service.AlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
@CrossOrigin("*")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Alert>>> list(
            @RequestParam(required = false) Long bridgeId,
            @RequestParam(required = false) String level) {
        List<Alert> result;
        if (bridgeId != null) {
            result = alertService.findByBridgeId(bridgeId);
        } else if (level != null) {
            result = alertService.findByLevel(level);
        } else {
            result = alertService.findAll();
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/unacknowledged")
    public ResponseEntity<ApiResponse<List<Alert>>> unacknowledged() {
        return ResponseEntity.ok(ApiResponse.success(alertService.findUnacknowledged()));
    }

    @GetMapping("/unacknowledged/count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> unackCount(
            @RequestParam(required = false) Long bridgeId) {
        Long count = bridgeId != null ? alertService.countUnacknowledged(bridgeId)
                : alertService.countUnacknowledged();
        return ResponseEntity.ok(ApiResponse.success(Map.of("count", count)));
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<ApiResponse<Alert>> acknowledge(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        String user = body != null && body.containsKey("user") ? body.get("user") : "system";
        return ResponseEntity.ok(ApiResponse.success(alertService.acknowledge(id, user)));
    }

    @GetMapping("/thresholds")
    public ResponseEntity<ApiResponse<List<AlertThreshold>>> thresholds() {
        return ResponseEntity.ok(ApiResponse.success(alertService.listThresholds()));
    }

    @PutMapping("/thresholds/{id}")
    public ResponseEntity<ApiResponse<AlertThreshold>> updateThreshold(
            @PathVariable Long id,
            @RequestBody AlertThreshold threshold) {
        return ResponseEntity.ok(ApiResponse.success(alertService.updateThreshold(id, threshold)));
    }
}
