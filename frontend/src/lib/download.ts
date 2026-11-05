/**
 * Authenticated file download.
 *
 * Report endpoints need the professor's Bearer token, so a plain <a href> can't be
 * used. This fetches the file as a blob and saves it via a temporary object URL.
 * Errors are thrown as ApiError, matching apiFetch, so callers can show a toast.
 */

import { API_BASE, ApiError, clearStoredToken, getStoredToken } from './api';

export async function downloadFile(path: string, filename: string): Promise<void> {
  const token = getStoredToken();
  const headers = new Headers();
  if (token) headers.set('Authorization', `Bearer ${token}`);

  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, { headers });
  } catch {
    throw new ApiError(0, 'Network Error', 'Unable to reach the server. Check your connection.');
  }

  if (!res.ok) {
    if (res.status === 401) {
      clearStoredToken();
      window.location.replace('/login');
      throw new ApiError(401, 'Unauthorized', 'Session expired. Please log in again.');
    }
    let message = res.statusText || 'Download failed';
    try {
      const body = await res.json();
      message = body.message ?? message;
    } catch { /* not JSON */ }
    throw new ApiError(res.status, '', message);
  }

  const blob = await res.blob();
  const url = window.URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.URL.revokeObjectURL(url);
}
