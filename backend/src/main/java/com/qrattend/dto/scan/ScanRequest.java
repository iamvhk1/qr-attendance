package com.qrattend.dto.scan;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanRequest {

    @NotBlank(message = "Roll number is required")
    private String rollNumber;
    
    // Optional, can be provided by frontend
    private String studentName;
}
