/** Mirrors CourseResponse.java */
export interface CourseResponse {
  id: string;
  name: string;
  code: string;
  semester: string;
  studentCount: number;
  createdAt: string; // ISO-8601 Instant
}

/** Mirrors CourseRequest.java */
export interface CourseRequest {
  name: string;
  code: string;
  semester: string;
}
