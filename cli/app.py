import cmd
import json
import os
import sys
import time
import requests
import qrcode
import threading
from colorama import init, Fore, Style

init(autoreset=True)

API_BASE = "http://localhost:8080/api"
STATE_FILE = ".cli-state.json"

class QRCLI(cmd.Cmd):
    intro = Fore.CYAN + "\n=============================================\n" + \
            "   QR Attendance System — Interactive CLI\n" + \
            "=============================================\n" + Style.RESET_ALL + \
            "Type 'help' or '?' to list commands.\n"
    prompt = Fore.GREEN + 'qr-cli> ' + Style.RESET_ALL

    def __init__(self):
        super().__init__()
        self.state = self.load_state()
        if not self.state.get("token"):
            self.auto_setup()

    def load_state(self):
        if os.path.exists(STATE_FILE):
            with open(STATE_FILE, "r") as f:
                return json.load(f)
        return {}

    def save_state(self):
        with open(STATE_FILE, "w") as f:
            json.dump(self.state, f, indent=2)

    def req_headers(self):
        return {"Authorization": f"Bearer {self.state.get('token')}"}

    def auto_setup(self):
        print(Fore.YELLOW + "No saved session found. Running auto-setup...")
        
        # 1. Get invite
        try:
            res = requests.post(f"{API_BASE}/admin/invite", json={"adminSecret": "changeme-admin-secret-2026"})
            res.raise_for_status()
            invite_code = res.json()["inviteCode"]
        except Exception as e:
            print(Fore.RED + f"Failed to get invite (is the server running?): {e}")
            sys.exit(1)

        # 2. Register
        user_email = "prof.cli@college.edu"
        user_pass = "SecurePass123!"
        print(Fore.YELLOW + f"Registering default professor: {user_email}...")
        requests.post(f"{API_BASE}/auth/register", json={
            "inviteCode": invite_code,
            "email": user_email,
            "password": user_pass,
            "fullName": "CLI Professor"
        }) # Ignore if already exists

        # 3. Login
        print(Fore.YELLOW + "Logging in...")
        res = requests.post(f"{API_BASE}/auth/login", json={
            "email": user_email,
            "password": user_pass
        })
        res.raise_for_status()
        self.state["token"] = res.json()["token"]

        # 4. Create Course
        print(Fore.YELLOW + "Creating default course (CS101)...")
        res = requests.post(f"{API_BASE}/courses", headers=self.req_headers(), json={
            "name": "CLI Auto Course",
            "code": "CS101",
            "semester": "Fall 2026"
        })
        res.raise_for_status()
        self.state["course_id"] = res.json()["id"]

        self.save_state()
        print(Fore.GREEN + "Setup complete! Ready to accept commands.\n")

    # ─── Commands ──────────────────────────────────────────────

    def do_add(self, arg):
        """Add a student manually: add student <rollNumber> <fullName>"""
        parts = arg.split(maxsplit=2)
        if len(parts) < 3 or parts[0].lower() != "student":
            print(Fore.RED + "Usage: add student <rollNumber> <fullName>")
            return
        
        roll, name = parts[1], parts[2]
        res = requests.post(f"{API_BASE}/courses/{self.state['course_id']}/students", 
                            headers=self.req_headers(), 
                            json={"rollNumber": roll, "fullName": name})
        
        if res.status_code == 201:
            print(Fore.GREEN + f"Added student: {roll} - {name}")
        else:
            print(Fore.RED + f"Failed: {res.json().get('message', res.text)}")

    def do_import(self, arg):
        """Import students from Excel: import excel <path_to_excel_file>"""
        parts = arg.split(maxsplit=1)
        if len(parts) < 2 or parts[0].lower() != "excel":
            print(Fore.RED + "Usage: import excel <path_to_excel_file>")
            return
        
        filepath = parts[1].strip().strip('"').strip("'")
        if not os.path.exists(filepath):
            print(Fore.RED + f"File not found: {filepath}")
            return
        if not os.path.isfile(filepath):
            print(Fore.RED + f"Error: The path provided is a folder, not a file. Please include the filename (e.g. \\students.xlsx)")
            return
        
        print(Fore.YELLOW + f"Importing from {filepath}...")
        try:
            with open(filepath, 'rb') as f:
                res = requests.post(f"{API_BASE}/courses/{self.state['course_id']}/students/import",
                                    headers=self.req_headers(),
                                    files={'file': f})
            if res.status_code == 200:
                rep = res.json()
                print(Fore.GREEN + f"Sync complete! Added: {rep['added']}, Removed: {rep['removed']}, Total: {rep['totalAfterSync']}")
            else:
                print(Fore.RED + f"Import failed: {res.text}")
        except Exception as e:
            print(Fore.RED + f"Error: {e}")

    def do_list(self, arg):
        """List all students in the course: list students"""
        if arg.strip().lower() != "students":
            print(Fore.RED + "Usage: list students")
            return
        
        res = requests.get(f"{API_BASE}/courses/{self.state['course_id']}/students", headers=self.req_headers())
        if res.status_code == 200:
            students = res.json()
            print(Fore.CYAN + f"\n--- Enrolled Students ({len(students)}) ---")
            for s in students:
                print(f"[{s['rollNumber']}] {s['fullName']}")
            print("")
        else:
            print(Fore.RED + f"Failed to list students: {res.text}")

    def do_start(self, arg):
        """Start a QR session: start session [duration_seconds]"""
        parts = arg.split()
        if len(parts) < 1 or parts[0].lower() != "session":
            print(Fore.RED + "Usage: start session [duration_seconds]")
            return
        
        duration = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 120
        
        res = requests.post(f"{API_BASE}/sessions", headers=self.req_headers(), json={
            "courseId": self.state["course_id"],
            "durationSeconds": duration
        })
        
        if res.status_code == 201:
            session_id = res.json()["id"]
            print(Fore.GREEN + f"Session started! ID: {session_id}")
            self.live_qr_mode(session_id)
        else:
            print(Fore.RED + f"Failed to start session: {res.text}")

    def do_exit(self, arg):
        """Exit the CLI."""
        print("Goodbye!")
        return True
    
    def do_quit(self, arg):
        """Exit the CLI."""
        return self.do_exit(arg)

    # ─── Live QR Loop ──────────────────────────────────────────
    def live_qr_mode(self, session_id):
        print(Fore.YELLOW + "Entering Live QR Mode. Press Ctrl+C to end the session early.")
        time.sleep(1)
        
        try:
            while True:
                # 1. Fetch fresh QR URL
                res = requests.get(f"{API_BASE}/sessions/{session_id}/qr-data", headers=self.req_headers())
                if res.status_code == 409:
                    print(Fore.RED + "\n[Session Window Closed]\n")
                    break
                elif res.status_code != 200:
                    print(Fore.RED + f"\nError fetching QR: {res.text}\n")
                    break
                
                url = res.json()["url"]
                
                # 2. Clear terminal and print ASCII QR
                # Clear screen (Windows 'cls', Mac/Linux 'clear')
                os.system('cls' if os.name == 'nt' else 'clear')
                
                print(Fore.CYAN + f"=== LIVE QR SESSION ===")
                print(Fore.YELLOW + "Scan to mark attendance! (Refreshes every 14s)")
                print(Fore.WHITE + f"Press Ctrl+C to return to prompt.\n")
                
                qr = qrcode.QRCode(version=1, box_size=1, border=2)
                qr.add_data(url)
                qr.make(fit=True)
                # Print to terminal
                qr.print_ascii(invert=True)
                
                print(Fore.CYAN + f"\nRefreshed at: {time.strftime('%H:%M:%S')}")
                
                # 3. Wait 14s before refresh
                time.sleep(14)
                
        except KeyboardInterrupt:
            # Clear line on Ctrl+C
            print(Fore.YELLOW + "\nSession loop exited. Back to prompt.")

if __name__ == '__main__':
    try:
        QRCLI().cmdloop()
    except KeyboardInterrupt:
        print("\nExiting.")
