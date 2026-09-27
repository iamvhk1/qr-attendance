/**
 * AuthContext — manages professor JWT and identity across the app.
 *
 * Token is persisted in localStorage so sessions survive page refreshes.
 * Falls back to in-memory state if localStorage is unavailable (Safari private mode).
 */
import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { useNavigate } from 'react-router-dom';
import { getStoredToken, setStoredToken, clearStoredToken } from './api';
import type { LoginResponse, ProfessorInfo } from '../types/auth';

// ── Types ────────────────────────────────────────────────────────

interface AuthContextValue {
  token: string | null;
  professor: ProfessorInfo | null;
  isAuthenticated: boolean;
  login: (resp: LoginResponse) => void;
  logout: () => void;
}

// ── Context ──────────────────────────────────────────────────────

const AuthContext = createContext<AuthContextValue | null>(null);

// ── Storage keys ─────────────────────────────────────────────────

const PROFESSOR_KEY = 'professor_info';

function loadProfessorFromStorage(): ProfessorInfo | null {
  try {
    const raw = localStorage.getItem(PROFESSOR_KEY);
    return raw ? (JSON.parse(raw) as ProfessorInfo) : null;
  } catch {
    return null;
  }
}

function saveProfessorToStorage(info: ProfessorInfo): void {
  try {
    localStorage.setItem(PROFESSOR_KEY, JSON.stringify(info));
  } catch { /* ignore */ }
}

function clearProfessorFromStorage(): void {
  try {
    localStorage.removeItem(PROFESSOR_KEY);
  } catch { /* ignore */ }
}

// ── Provider ─────────────────────────────────────────────────────

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const navigate = useNavigate();

  // Restore state from localStorage on mount
  const [token, setToken] = useState<string | null>(() => getStoredToken());
  const [professor, setProfessor] = useState<ProfessorInfo | null>(() =>
    loadProfessorFromStorage(),
  );

  // Guard: if token is gone but professor info is stale (or vice versa), clear both
  useEffect(() => {
    if ((token && !professor) || (!token && professor)) {
      clearStoredToken();
      clearProfessorFromStorage();
      setToken(null);
      setProfessor(null);
    }
  }, []); // run once on mount only

  const login = useCallback(
    (resp: LoginResponse) => {
      const info: ProfessorInfo = {
        id: resp.professorId,
        email: resp.email,
        fullName: resp.fullName,
      };
      setStoredToken(resp.token);
      saveProfessorToStorage(info);
      setToken(resp.token);
      setProfessor(info);
      navigate('/courses');
    },
    [navigate],
  );

  const logout = useCallback(() => {
    clearStoredToken();
    clearProfessorFromStorage();
    setToken(null);
    setProfessor(null);
    navigate('/login');
  }, [navigate]);

  const value = useMemo<AuthContextValue>(
    () => ({
      token,
      professor,
      isAuthenticated: token !== null && professor !== null,
      login,
      logout,
    }),
    [token, professor, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

// ── Hook ─────────────────────────────────────────────────────────

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return ctx;
}
