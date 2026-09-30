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
public class HeartbeatRequest {

    @NotBlank(message = "Nonce is required")
    private String nonce;

    /** True if navigator.webdriver is set — automated browser detection. */
    private boolean webdriver;

}
