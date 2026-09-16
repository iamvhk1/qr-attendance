package com.qrattend.dto.scan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanResponse {

    private UUID attendanceId;
    private String initialNonce;
    private String attendanceToken; // JWT used for heartbeats
    private String status;
}
