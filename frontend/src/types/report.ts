/** A per-session cell in the course attendance matrix. */
export type AttendanceCell = 'CONFIRMED' | 'INVALIDATED' | 'PENDING' | 'ABSENT';

/** Mirrors CourseAttendanceReport.SessionColumn.java */
export interface ReportSessionColumn {
  id: string;
  createdAt: string; // ISO-8601 Instant
  status: 'LIVE' | 'CLOSED';
  /** False for live sessions — shown, but not counted toward anyone's total. */
  counted: boolean;
  confirmedCount: number;
}

/** Mirrors CourseAttendanceReport.StudentRow.java */
export interface ReportStudentRow {
  rollNumber: string;
  fullName: string;
  attended: number;
  total: number;
  /** null when no sessions have been counted yet. */
  percentage: number | null;
  belowThreshold: boolean;
  /** Same order as CourseAttendanceReport.sessions. */
  cells: AttendanceCell[];
}

/** Mirrors CourseAttendanceReport.Summary.java */
export interface ReportSummary {
  studentCount: number;
  countedSessions: number;
  liveSessions: number;
  averagePercentage: number | null;
  belowThresholdCount: number;
  threshold: number;
}

/** Mirrors CourseAttendanceReport.java — GET /api/reports/courses/{id} */
export interface CourseAttendanceReport {
  courseId: string;
  courseName: string;
  courseCode: string;
  sessions: ReportSessionColumn[];
  students: ReportStudentRow[];
  summary: ReportSummary;
}
