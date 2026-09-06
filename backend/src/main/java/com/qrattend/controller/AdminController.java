package com.qrattend.controller;

import com.qrattend.dto.admin.InviteRequest;
import com.qrattend.dto.admin.InviteResponse;
import com.qrattend.service.AdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints — publicly accessible but protected by a shared admin secret.
 *
 * <p>The admin secret is configured in {@code application.properties}
 * ({@code app.admin.secret}). No JWT is required for these endpoints.</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    /**
     * Generates an invite code for professor registration.
     *
     * @param request contains the admin secret
     * @return 200 OK with the invite code (a signed INVITE JWT)
     */
    @PostMapping("/invite")
    public ResponseEntity<InviteResponse> generateInvite(@Valid @RequestBody InviteRequest request) {
        InviteResponse response = adminService.generateInvite(request.adminSecret());
        return ResponseEntity.ok(response);
    }
}
