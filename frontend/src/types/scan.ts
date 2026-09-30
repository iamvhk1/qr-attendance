/**
 * Type definitions for Phase 3 — Student scan / heartbeat flow.
 * Mirrors StudentScanController DTOs exactly.
 */

// ── POST /api/student/scan ─────────────────────────────────────────────────

/** Body sent to POST /api/student/scan (ROLE_SCAN JWT in Authorization header). */
export interface ScanRequest {
  rollNumber: string;
  studentName?: string;
}

/** Response from POST /api/student/scan. */
export interface ScanResponse {
  attendanceId: string;   // UUID
  initialNonce: string;
  attendanceToken: string; // 2-hour ROLE_ATTENDANCE JWT — store in memory, never localStorage
  status: string;          // 'PENDING' at this stage
}

// ── POST /api/student/heartbeat ────────────────────────────────────────────

/** Body sent to POST /api/student/heartbeat (ROLE_ATTENDANCE JWT in Authorization header). */
export interface HeartbeatRequest {
  nonce: string;
  webdriver: boolean;
}

/** Response from POST /api/student/heartbeat. */
export interface HeartbeatResponse {
  nextNonce: string;
  status: string; // 'PENDING' | 'CONFIRMED' | 'INVALIDATED'
}
