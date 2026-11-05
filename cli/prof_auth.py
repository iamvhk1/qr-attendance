"""Shared helper: log in as a professor, registering (with email verification) if needed.

Registration needs a 6-digit code emailed to an @iitm.ac.in address. Against a local
backend (app.mail.provider=log, the default) the code is printed in the backend console
instead of being emailed, so you can copy it from there.

Credentials come from the environment, with local-dev defaults:
    QR_PROF_EMAIL     (default: prof.cli@iitm.ac.in)
    QR_PROF_PASSWORD  (default: SecurePass123!)
"""
import os

import requests

DEFAULT_EMAIL = "prof.cli@iitm.ac.in"
DEFAULT_PASSWORD = "SecurePass123!"


def credentials():
    return (os.environ.get("QR_PROF_EMAIL", DEFAULT_EMAIL),
            os.environ.get("QR_PROF_PASSWORD", DEFAULT_PASSWORD))


def login(api_base, email, password):
    """Returns a JWT, or None if the credentials are not accepted."""
    res = requests.post(f"{api_base}/auth/login", json={"email": email, "password": password})
    if res.status_code == 401:
        return None
    res.raise_for_status()
    return res.json()["token"]


def ensure_professor(api_base, full_name, email=None, password=None):
    """Logs in, registering the account first if it doesn't exist. Returns (token, email, password)."""
    default_email, default_password = credentials()
    email = email or default_email
    password = password or default_password

    token = login(api_base, email, password)
    if token:
        return token, email, password

    print(f"No account for {email} yet - registering (email verification required).")
    res = requests.post(f"{api_base}/auth/otp", json={"email": email})
    if res.status_code == 409:
        raise SystemExit(f"{email} is already registered but the password was rejected. "
                         "Set QR_PROF_PASSWORD to the right password.")
    if res.status_code == 429:
        raise SystemExit(f"Too many codes requested: {res.json().get('message')}")
    if not res.ok:
        raise SystemExit(f"Could not request a verification code ({res.status_code}): {res.text}")

    print(f"A 6-digit code was sent to {email}.")
    print("Running the backend locally? The code is printed in the backend console.")
    code = input("Enter the code: ").strip()

    res = requests.post(f"{api_base}/auth/register", json={
        "email": email, "otp": code, "password": password, "fullName": full_name,
    })
    if not res.ok:
        raise SystemExit(f"Registration failed ({res.status_code}): {res.json().get('message', res.text)}")

    token = login(api_base, email, password)
    if not token:
        raise SystemExit("Registered, but login failed.")
    return token, email, password
