/** Mirrors StudentResponse.java */
export interface StudentResponse {
  id: string;
  rollNumber: string;
  fullName: string;
}

/** Mirrors StudentRequest.java */
export interface StudentRequest {
  rollNumber: string;
  fullName: string;
}

/** Mirrors RosterSyncReport.java */
export interface RosterSyncReport {
  added: number;
  removed: number;
  unchanged: number;
}
