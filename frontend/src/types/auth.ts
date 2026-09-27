/** Mirrors LoginResponse.java */
export interface LoginResponse {
  token: string;
  professorId: string;
  email: string;
  fullName: string;
}

/** Mirrors RegisterResponse.java */
export interface RegisterResponse {
  professorId: string;
  email: string;
  fullName: string;
}

/** Mirrors ErrorResponse.java — emitted by GlobalExceptionHandler */
export interface ApiErrorBody {
  status: number;
  error: string;   // e.g. "Bad Request"
  message: string; // human-readable, may contain semicolon-joined field errors
}

/** Stored auth state (subset of LoginResponse) */
export interface ProfessorInfo {
  id: string;
  email: string;
  fullName: string;
}
