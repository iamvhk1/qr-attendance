/** Mirrors SessionResponse.java */
export interface SessionResponse {
  id: string;
  courseId: string;
  professorId: string;
  createdAt: string;
  expiresAt: string;
  extendedCount: number;
  closedAt: string | null;
  status: 'LIVE' | 'CLOSED';
}

/** Mirrors SessionRequest.java — durationSeconds is optional (server defaults to 120) */
export interface SessionRequest {
  courseId: string;
  durationSeconds?: number;
}

/** Mirrors AttendanceResponse.java */
export interface AttendanceResponse {
  id: string;
  rollNumber: string;
  studentName: string;
  status: 'PENDING' | 'CONFIRMED' | 'INVALIDATED';
  manuallyAdded: boolean;
  overrideReason: string | null;
  heartbeatCoverage: number | null; // null while session is live
  markedAt: string;
}

/** Mirrors QrDataResponse.java */
export interface QrDataResponse {
  url: string;
}
