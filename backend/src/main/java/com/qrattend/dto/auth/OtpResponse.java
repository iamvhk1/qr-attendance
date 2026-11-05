package com.qrattend.dto.auth;

/**
 * Response for {@code POST /api/auth/otp}.
 *
 * @param expiresInSeconds   how long the emailed code stays valid
 * @param resendAfterSeconds how long to wait before another code can be requested
 */
public record OtpResponse(long expiresInSeconds, long resendAfterSeconds) {}
