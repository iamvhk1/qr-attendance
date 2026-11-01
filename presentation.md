# 🎤 2-Minute Presentation Script (Updated for Render.com)
## QR-Based Classroom Attendance System — Architecture Overview

---

> **[Point to the Architecture Diagram throughout]**  
> Practice time: ~2 min | Word count: ~270 words

![QR Attendance Architecture Diagram](C:\Users\Harsha Karthikeya\.gemini\antigravity-ide\brain\2b988e76-1e6c-455e-8b05-ea86c3d371ec\qr_arch_render_fixed_1791797135738.jpg)

---

## 📌 Opening (0:00 – 0:15)

> *"Good [morning/afternoon]. My project is a QR-Based Classroom Attendance System — a fraud-resistant, real-time attendance platform built for classrooms."*

---

## 🏗️ Layer 1 — Frontend (0:15 – 0:35)

> *"Looking at the top of the diagram, the frontend is a React + Vite Single Page Application, deployed on Vercel. It has two distinct interfaces:*
>
> *The **Professor Dashboard** — where a professor logs in, manages their courses, starts attendance sessions, and watches a live real-time monitor.*
>
> *And the **Student Scan Interface** — a lightweight, mobile-friendly page that opens when a student scans the QR code. The student enters their roll number, and a dwell-time countdown begins — they must stay on the page to prove physical presence."*

---

## 🔄 The Core Data Flow — Attendance (0:35 – 1:10)

> *"Here's the critical data flow — and this is the interesting part.*
>
> *Step 1: The professor starts a session. The backend immediately generates a QR code containing a short-lived JWT — valid for only **15 seconds** — using the ZXing library. The QR refreshes automatically every 15 seconds. This means a student cannot screenshot and share the code.*
>
> *Step 2: A student scans the code and hits `/api/student/scan`. The backend's **QR & Session Management module** validates the token, checks the student is enrolled, and creates an attendance record in state: **PENDING**.*
>
> *Step 3 — the anti-cheat layer: Every **5 seconds**, the student's browser sends a heartbeat ping to `/api/student/heartbeat`. Each ping must include the correct **cryptographic nonce** — a rotating one-time secret — issued by the server in the previous response. If the nonce is wrong, the server rejects it. If the student closes the tab or the screen times out, the pings stop.*
>
> *Step 4: A background cron job runs every minute inside the **Presence Verification Engine**. It computes heartbeat coverage — the percentage of expected pings that actually arrived. If coverage is **≥ 80%**, attendance flips to **CONFIRMED**. If it drops below that threshold, it becomes **INVALIDATED**."*

---

## 🔴 Live Monitoring — Professor Side (1:10 – 1:30)

> *"Meanwhile, the Professor Dashboard gets live updates via **Server-Sent Events (SSE)** — the backend pushes time remaining and live attendance counts to the UI in real-time, without any polling from the frontend. If the professor needs to, they can manually override any student's attendance status directly."*

---

## 🗄️ Backend & Database Layer (1:30 – 1:50)

> *"The backend is a Spring Boot application deployed on **Render.com** as a cloud web service. It's structured in clean layers: Controllers handle HTTP, Services contain business logic, Repositories talk to the database via **Spring Data JPA**.*
>
> *The database connection is managed by **HikariCP**, a pool of 10 connections. So even if 100 students scan simultaneously, Supabase only ever sees 10 concurrent connections — the pool queues requests in memory, cycles through them rapidly, and handles the load gracefully."*

---

## 🎯 Closing (1:50 – 2:00)

> *"In summary — rolling QR tokens prevent code sharing, cryptographic heartbeats prove physical presence, and SSE gives the professor a live view of it all. Thank you."*

---

## 📋 Quick Reference: Data Flow in One Line

```
Professor starts session
  → Backend generates 15s rolling QR JWT (ZXing)
    → Student scans → /api/student/scan validates token → Attendance = PENDING
      → Heartbeats every 5s with rotating nonce → /api/student/heartbeat
        → Cron job checks coverage every 1min
          → ≥80% → CONFIRMED | <80% → INVALIDATED
```

---

## 🔑 Key Numbers to Remember

| Fact | Value |
|---|---|
| QR token validity | **15 seconds** (rolling) |
| Heartbeat interval | **Every 5 seconds** |
| Coverage threshold | **≥ 80%** to confirm attendance |
| DB connection pool | **10 connections** (HikariCP) |
| Grace period for missed heartbeats | **15 seconds** (3 missed pings) |
