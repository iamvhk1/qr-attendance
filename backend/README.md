# QR Attendance System — Backend

A Spring Boot API for QR-based classroom attendance.  
A professor generates a rolling QR code. Students scan it during class. Attendance is recorded automatically.

---

## Prerequisites (one-time setup on any laptop)

| Tool | Minimum Version | Download |
|------|----------------|----------|
| **Java JDK** | 21 | https://adoptium.net/ |
| **Maven** | 3.6+ | Only needed if `./mvnw` fails — https://maven.apache.org/download.cgi |

> **No database to install.** The project uses H2 — a file-based database bundled inside the JAR. Data lives in `backend/data/` and persists across restarts.

Verify your Java version:
```bash
java -version   # must say 21 or higher
```

---

## Quickstart (any OS)

```bash
# 1. Clone / unzip the project
cd qr-attendance/backend

# Windows — double-click run.bat  OR  in a terminal:
run.bat

# Mac / Linux
chmod +x run.sh mvnw
./run.sh

# Or with Make (Mac / Linux / Git Bash)
make
```

The server starts on **http://localhost:8080**

---

## Available Commands

### Windows
```
run.bat           # start server (most common)
run.bat test      # run all tests
run.bat clean     # wipe database + build files (fresh start)
```

### Mac / Linux
```bash
./run.sh          # start server
./run.sh test     # run all tests
./run.sh package  # build a runnable JAR
./run.sh clean    # wipe database + build files
```

### Make (Git Bash / Mac / Linux)
```bash
make              # start server
make test         # run tests
make package      # build fat JAR
make clean        # fresh start
make help         # list all targets
```

### Running the built JAR directly (after make package)
```bash
java -jar target/qr-attendance-0.0.1-SNAPSHOT.jar
```
This is how you deploy to any server — no Maven needed, just Java 21.

---

## Full API Playbook

Use Postman, curl, or any HTTP client.
Base URL: `http://localhost:8080`

All protected endpoints require:
```
Authorization: Bearer <your-jwt-token>
```

---

### STEP 1 — Get an Invite Code (Admin)

One-time step to create the first professor account.

```bash
curl -s -X POST http://localhost:8080/api/admin/invite \
  -H "Content-Type: application/json" \
  -d '{"adminSecret": "changeme-admin-secret-2026"}' | python -m json.tool
```

Response:
```json
{ "inviteCode": "eyJhbGci..." }
```

---

### STEP 2 — Register a Professor

```bash
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "inviteCode": "PASTE_INVITE_CODE_HERE",
    "email": "prof@college.edu",
    "password": "SecurePass123!",
    "fullName": "Dr. Smith"
  }' | python -m json.tool
```

---

### STEP 3 — Login (Get JWT)

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "prof@college.edu", "password": "SecurePass123!"}' | python -m json.tool
```

Response:
```json
{ "token": "eyJhbGciOiJIUzUxMiJ9...", "type": "Bearer" }
```

Save your token:
```bash
# Mac/Linux
export TOKEN="eyJhbGciOiJIUzUxMiJ9..."

# PowerShell
$TOKEN="eyJhbGciOiJIUzUxMiJ9..."
```

---

### STEP 4 — Create a Course

```bash
curl -s -X POST http://localhost:8080/api/courses \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "Operating Systems", "code": "CS5013", "semester": "Aug-Dec 2026"}' \
  | python -m json.tool
```

Note the `id` — this is your COURSE_ID.

---

### STEP 5a — Add a Student Manually

```bash
curl -s -X POST http://localhost:8080/api/courses/COURSE_ID/students \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"rollNumber": "CS24B001", "name": "Alice Kumar"}' | python -m json.tool
```

---

### STEP 5b — Import Students from Excel

This is the main way to load students. Excel must have these headers in row 1:

```
rollNumber    name
CS24B001      Alice Kumar
CS24B002      Bob Singh
```

Import command:
```bash
curl -s -X POST http://localhost:8080/api/courses/COURSE_ID/students/import \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/students.xlsx" | python -m json.tool
```

Response:
```json
{ "added": 12, "removed": 0, "unchanged": 0, "totalAfterSync": 12 }
```

Mid-semester sync: just re-upload a modified Excel. Students removed from the file get removed from the course. Empty file = wipe all students.

---

### STEP 6 — View Students

```bash
curl -s http://localhost:8080/api/courses/COURSE_ID/students \
  -H "Authorization: Bearer $TOKEN" | python -m json.tool
```

---

### STEP 7 — Start a QR Session

```bash
curl -s -X POST http://localhost:8080/api/sessions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"courseId": "COURSE_ID", "durationSeconds": 120}' | python -m json.tool
```

Response:
```json
{
  "id": "b1e81509-68cf-...",
  "status": "LIVE",
  "expiresAt": "2026-09-08T15:32:00Z"
}
```

Note the `id` — this is your SESSION_ID.

---

### STEP 8 — Fetch the QR Code PNG

```bash
# Download to file
curl -s http://localhost:8080/api/sessions/SESSION_ID/qr \
  -H "Authorization: Bearer $TOKEN" --output qr.png

# Open it
open qr.png        # Mac
start qr.png       # Windows
xdg-open qr.png    # Linux
```

The QR encodes a URL like:
```
http://localhost:5173/scan?token=eyJhbGci...
```

This token expires in 15 seconds. Poll every 14 seconds for a fresh QR:

```bash
# Bash — auto-refresh loop
while true; do
  curl -s http://localhost:8080/api/sessions/SESSION_ID/qr \
    -H "Authorization: Bearer $TOKEN" --output qr.png
  echo "Refreshed at $(date +%H:%M:%S)"
  sleep 14
done
```

Returns 409 Conflict when the session window closes.

---

### STEP 9 — Check Session Status

```bash
curl -s http://localhost:8080/api/sessions/SESSION_ID \
  -H "Authorization: Bearer $TOKEN" | python -m json.tool
```

```json
{ "status": "LIVE" }   // or "CLOSED"
```

---

### STEP 10 — View All Courses

```bash
curl -s http://localhost:8080/api/courses \
  -H "Authorization: Bearer $TOKEN" | python -m json.tool
```

---

## H2 Database Browser

Visit http://localhost:8080/h2-console in your browser.

Login:
- JDBC URL: `jdbc:h2:file:./data/qr-attendance-db`
- Username: `sa`
- Password: (leave blank)

---

## Excel File Rules

- Format: `.xlsx` only (not `.xls` or `.csv`)
- First sheet is used
- Row 1 must be headers: `rollNumber` and `name` (case-sensitive)
- Roll numbers are strings (leading zeros preserved)
- Duplicate roll numbers in file: first row wins
- Empty file: removes all students from course
- Column order does not matter

---

## Configuration Reference

Edit `src/main/resources/application.properties`:

| Property | Default | What it controls |
|----------|---------|-----------------|
| `server.port` | `8080` | Server port |
| `app.jwt.scan-expiration-ms` | `15000` | QR token lifetime (15 seconds) |
| `app.session.default-duration-seconds` | `120` | Session window (2 minutes) |
| `app.frontend.url` | `http://localhost:5173` | URL embedded in QR codes |
| `app.admin.secret` | `changeme-admin-secret-2026` | Admin invite secret |

---

## Common Errors

| Status | Meaning | Fix |
|--------|---------|-----|
| `401` | Token missing, expired, or wrong type | Re-login to get a fresh token |
| `403` | Accessing another professor's data | You can only see your own courses |
| `400` | Validation failed | Read the `message` field in response |
| `409` | Session already closed | 2-minute window expired; create new session |
| `404` | ID does not exist | Check the course/session ID |

---

## Project Structure

```
backend/
├── run.bat                  Windows launcher
├── run.sh                   Mac/Linux launcher
├── Makefile                 make targets
├── mvnw / mvnw.cmd          Maven wrapper (no install needed)
├── pom.xml                  Dependencies
├── data/                    H2 database (auto-created, gitignored)
└── src/main/java/com/qrattend/
    ├── controller/          HTTP endpoints
    ├── service/             Business logic
    ├── entity/              Database tables
    ├── dto/                 Request/Response shapes
    ├── repository/          Database queries
    ├── security/            JWT + Spring Security
    └── util/                QR generator, Excel parser
```
