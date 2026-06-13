package com.heritage.bridge.controller;

import com.heritage.bridge.dto.ApiResponse;
import com.heritage.bridge.dto.SensorDataUploadDTO;
import com.heritage.bridge.dto.TrendDataDTO;
import com.heritage.bridge.entity.SensorData;
import com.heritage.bridge.service.DataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/data")
@Validated
@RequiredArgsConstructor
public class DataController {

    private final DataService dataService;

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<SensorData>> upload(@RequestBody SensorDataUploadDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(dataService.ingest(dto)));
    }

    @GetMapping("/bridges/{bridgeId}/latest")
    public ResponseEntity<ApiResponse<List<SensorData>>> latest(@PathVariable Long bridgeId) {
        return ResponseEntity.ok(ApiResponse.success(dataService.findLatestByBridgeId(bridgeId)));
    }

    @GetMapping("/bridges/{bridgeId}/history")
    public ResponseEntity<ApiResponse<List<SensorData>>> history(
            @PathVariable Long bridgeId,
            @RequestParam(required = false) Long sensorId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        LocalDateTime start = startTime != null ? LocalDateTime.parse(startTime) : LocalDateTime.now().minusDays(7);
        LocalDateTime end = endTime != null ? LocalDateTime.parse(endTime) : LocalDateTime.now();
        Long sid = sensorId != null ? sensorId : 0L;
        return ResponseEntity.ok(ApiResponse.success(dataService.findHistory(bridgeId, sid, start, end)));
    }

    @GetMapping("/sensors/{sensorId}/trend")
    public ResponseEntity<ApiResponse<List<TrendDataDTO>>> trend(@PathVariable Long sensorId,
            @RequestParam(defaultValue = "365") Integer days) {
        LocalDateTime from = LocalDateTime.now().minusDays(days != null ? days : 365);
        return ResponseEntity.ok(ApiResponse.success(dataService.getSensorTrend(sensorId, from)));
    }

    @PostMapping("/batch")
    public ResponseEntity<ApiResponse<Integer>> batchUpload(@RequestBody List<SensorDataUploadDTO> dtos) {
        return ResponseEntity.ok(ApiResponse.success(dataService.batchIngest(dtos).size()));
    }
}
