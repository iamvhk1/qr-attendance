/** Mirrors StudentResponse.java */
export interface StudentResponse {
  id: string;
  rollNumber: string;
  name: string;
  email: string | null;
}

/** Mirrors StudentRequest.java */
export interface StudentRequest {
  rollNumber: string;
  name: string;
  email?: string;
}

/** Mirrors RosterSyncReport.java */
export interface RosterSyncReport {
  added: number;
  removed: number;
  unchanged: number;
}
