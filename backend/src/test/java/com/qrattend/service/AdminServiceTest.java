package com.qrattend.service;

import com.qrattend.dto.admin.InviteResponse;
import com.qrattend.exception.InvalidCredentialsException;
import com.qrattend.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private AdminService adminService;

    private static final String TEST_SECRET = "test-admin-secret-123";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(adminService, "adminSecret", TEST_SECRET);
    }

    @Test
    void generateInvite_ValidSecret_ReturnsInviteResponse() {
        given(jwtUtil.generateInviteToken()).willReturn("mock-invite-token");

        InviteResponse response = adminService.generateInvite(TEST_SECRET);

        assertThat(response.inviteCode()).isEqualTo("mock-invite-token");
    }

    @Test
    void generateInvite_InvalidSecret_ThrowsInvalidCredentialsException() {
        assertThatThrownBy(() -> adminService.generateInvite("wrong-secret"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Invalid admin secret");
    }
}
