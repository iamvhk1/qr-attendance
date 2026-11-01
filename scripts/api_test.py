#!/usr/bin/env python3
"""
Direct API Test script corresponding to Section 5 in commands.txt.
Executes the full professor onboarding and course creation flow:
  1. POST /api/admin/invite  (Generate invite code)
  2. POST /api/auth/register (Register professor)
  3. POST /api/auth/login    (Login and retrieve JWT)
  4. POST /api/courses       (Create authenticated course)
"""
import sys
import json
import urllib.request
import urllib.error

BASE_URL = "http://localhost:8080"
ADMIN_SECRET = "changeme-admin-secret-2026"
PROF_NAME = "Dr. Alan Turing"
PROF_EMAIL = "turing@university.edu"
PROF_PASS = "Password123!"
COURSE_CODE = "CS101"
COURSE_NAME = "Data Structures & Algorithms"
COURSE_SEM = "Fall 2026"

def request_json(url, data=None, headers=None):
    if headers is None:
        headers = {}
    headers["Content-Type"] = "application/json"
    req = urllib.request.Request(
        url,
        data=json.dumps(data).encode("utf-8") if data else None,
        headers=headers,
        method="POST"
    )
    try:
        with urllib.request.urlopen(req) as resp:
            body = resp.read().decode("utf-8")
            return resp.status, json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8")
        try:
            parsed = json.loads(body)
        except Exception:
            parsed = {"raw": body}
        return e.code, parsed
    except urllib.error.URLError as e:
        print(f"\n[ERROR] Could not connect to backend at {BASE_URL}: {e.reason}")
        print("Please ensure the backend is running first (e.g. 'make backend-run').")
        sys.exit(1)

def main():
    print("============================================================")
    print("      QR ATTENDANCE SYSTEM — DIRECT API SMOKE TEST          ")
    print(f"      Target: {BASE_URL}                                    ")
    print("============================================================\n")

    # Step 1: Generate Admin Invite Code
    print("[Step 1/4] Generating Admin Invite Code...")
    status, res = request_json(
        f"{BASE_URL}/api/admin/invite",
        {"adminSecret": ADMIN_SECRET}
    )
    if status != 200 or "inviteCode" not in res:
        print(f"[FAIL] Generate invite code failed (HTTP {status}): {res}")
        sys.exit(1)
    invite_code = res["inviteCode"]
    print(f"[OK] Invite Code generated: {invite_code[:20]}...\n")

    # Step 2: Register Professor
    print("[Step 2/4] Registering Professor (Alan Turing)...")
    status, res = request_json(
        f"{BASE_URL}/api/auth/register",
        {
            "fullName": PROF_NAME,
            "email": PROF_EMAIL,
            "password": PROF_PASS,
            "inviteCode": invite_code
        }
    )
    if status in (200, 201):
        print(f"[OK] Registered professor successfully: {res.get('email', PROF_EMAIL)}\n")
    elif status in (400, 409):
        print(f"[NOTE] Professor already registered (HTTP {status}): {res.get('message', res)}. Proceeding to login.\n")
    else:
        print(f"[FAIL] Registration failed (HTTP {status}): {res}")
        sys.exit(1)

    # Step 3: Login as Professor
    print("[Step 3/4] Logging in as Professor...")
    status, res = request_json(
        f"{BASE_URL}/api/auth/login",
        {
            "email": PROF_EMAIL,
            "password": PROF_PASS
        }
    )
    if status != 200 or "token" not in res:
        print(f"[FAIL] Login failed (HTTP {status}): {res}")
        sys.exit(1)
    jwt_token = res["token"]
    print(f"[OK] Logged in successfully. JWT Token: {jwt_token[:25]}...\n")

    # Step 4: Create a Course (Authenticated)
    print("[Step 4/4] Creating Course (CS101)...")
    status, res = request_json(
        f"{BASE_URL}/api/courses",
        {
            "code": COURSE_CODE,
            "name": COURSE_NAME,
            "semester": COURSE_SEM
        },
        headers={"Authorization": f"Bearer {jwt_token}"}
    )
    if status in (200, 201):
        print(f"[OK] Course created successfully: {res.get('code', COURSE_CODE)} - {res.get('name', COURSE_NAME)}\n")
    elif status in (400, 409):
        print(f"[NOTE] Course already exists (HTTP {status}): {res.get('message', res)}\n")
    else:
        print(f"[FAIL] Course creation failed (HTTP {status}): {res}")
        sys.exit(1)

    print("============================================================")
    print("      ALL API TESTS COMPLETED SUCCESSFULLY!                 ")
    print("============================================================")

if __name__ == "__main__":
    main()
