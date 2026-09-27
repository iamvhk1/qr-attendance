/**
 * Typed fetch wrapper for the QR Attendance API.
 *
 * - Attaches Authorization: Bearer <token> automatically.
 * - Parses the ErrorResponse body from GlobalExceptionHandler on non-2xx.
 * - On 401, clears stored token and redirects to /login.
 * - Handles 204 No Content (returns undefined without trying to parse JSON).
 */

const BASE = '/api'; // proxied to :8080 by vite.config.ts in dev

const TOKEN_KEY = 'professor_token';

// ── Token helpers ────────────────────────────────────────────────

export function getStoredToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    // localStorage unavailable (e.g. Safari private mode)
    return null;
  }
}

export function setStoredToken(token: string): void {
  try {
    localStorage.setItem(TOKEN_KEY, token);
  } catch {
    // ignore — in-memory only session will still work
  }
}

export function clearStoredToken(): void {
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {
    // ignore
  }
}

// ── Error type ───────────────────────────────────────────────────

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly errorText: string,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

// ── Core fetch wrapper ───────────────────────────────────────────

export async function apiFetch<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const token = getStoredToken();

  const headers = new Headers(options.headers as HeadersInit | undefined);
  // Only set JSON content-type if not already set (allows overrides)
  if (!headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  let res: Response;
  try {
    res = await fetch(`${BASE}${path}`, { ...options, headers });
  } catch {
    throw new ApiError(0, 'Network Error', 'Unable to reach the server. Check your connection.');
  }

  if (!res.ok) {
    if (res.status === 401) {
      clearStoredToken();
      // Hard redirect — React Router state will be lost but this is correct for auth expiry
      window.location.replace('/login');
      // Throw anyway so callers can clean up (unreachable after redirect in practice)
      throw new ApiError(401, 'Unauthorized', 'Session expired. Please log in again.');
    }

    let message = res.statusText;
    let errorText = '';
    try {
      const body = await res.json();
      message = body.message ?? message;
      errorText = body.error ?? '';
    } catch {
      // body was not JSON — keep statusText
    }
    throw new ApiError(res.status, errorText, message);
  }

  // 204 No Content — nothing to parse
  if (res.status === 204) return undefined as T;

  return res.json() as Promise<T>;
}

/**
 * File upload wrapper — does NOT set Content-Type so the browser
 * can calculate the multipart/form-data boundary automatically.
 * Used for POST /api/courses/:id/students/import.
 */
export async function apiUpload<T>(path: string, formData: FormData): Promise<T> {
  const token = getStoredToken();

  const headers = new Headers();
  if (token) headers.set('Authorization', `Bearer ${token}`);
  // Deliberately NO Content-Type header — browser sets multipart boundary

  let res: Response;
  try {
    res = await fetch(`${BASE}${path}`, { method: 'POST', headers, body: formData });
  } catch {
    throw new ApiError(0, 'Network Error', 'Unable to reach the server. Check your connection.');
  }

  if (!res.ok) {
    if (res.status === 401) {
      clearStoredToken();
      window.location.replace('/login');
      throw new ApiError(401, 'Unauthorized', 'Session expired. Please log in again.');
    }
    let message = res.statusText;
    let errorText = '';
    try {
      const body = await res.json();
      message = body.message ?? message;
      errorText = body.error ?? '';
    } catch { /* ignore */ }
    throw new ApiError(res.status, errorText, message);
  }

  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}
