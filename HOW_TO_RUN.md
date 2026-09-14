# 🚀 Royal Rummy — Run & Deployment Guide

This guide explains how to run the Rummy platform either **Locally (Recommended for Fast Development)** or via **Docker Compose (Clustered Production Stack)**.

---

## 📌 Quick Summary

| Feature | Method 1: Local Mode (No Docker) | Method 2: Docker Mode |
| :--- | :--- | :--- |
| **Best For** | Active coding, UI changes, instant testing | Full cluster testing, deployment simulation |
| **Startup Time** | ~5 seconds | ~1-3 minutes (initial build) |
| **Hot Reloading** | ✅ Instant (0.1s UI updates on save) | ❌ Requires container rebuild |
| **Databases** | Live MongoDB Atlas (from `.env`) | Local Mongo, Redis, Kafka in Docker |
| **System Overhead** | Very light on RAM / CPU | Moderate (Docker VM required) |

---

## 💻 Method 1: Local Development Mode (Fastest & Recommended)

Run Frontend and Backend natively on Windows with instant hot-reloading.

### Prerequisites:
- **Java 21** installed
- **Node.js (v18+)** installed
- If Docker is running, stop it first to free port `8081`:
  ```powershell
  cd d:\Projects\Rummy\Rummy\deployment\docker
  docker compose down
  ```

---

### Step 1: Start Backend (Java Spring Boot)
Open **Terminal 1 (PowerShell)**:
```powershell
cd d:\Projects\Rummy\Rummy\backend
.\mvnw.cmd spring-boot:run -pl game-service
```
* The backend automatically connects to the Live MongoDB Atlas configured in `.env`.
* **Backend API & WebSocket:** `http://localhost:8081` (`ws://localhost:8081/ws/game`)
* **Health Check:** [http://localhost:8081/actuator/health](http://localhost:8081/actuator/health)

---

### Step 2: Start Frontend (React + Vite)
Open **Terminal 2 (PowerShell)**:
```powershell
cd d:\Projects\Rummy\Rummy\frontend\rummy-web
npm run dev
```
* **Frontend Web Application:** Open **[http://localhost:5173](http://localhost:5173)** in your browser.
* ✨ Any changes made to React/CSS files will instantly update in your browser without reloading.

---

## 🐳 Method 2: Docker Compose Mode (Full Stack)

Run the complete multi-service stack (8 containers: Nginx Frontend, 2 Game Service Nodes, Redis, MongoDB, Kafka, Zookeeper, and Prometheus).

### Prerequisites:
- **Docker Desktop** must be running (green engine indicator in Docker Desktop).

---

### 1. Start the Stack (First time or after code changes)
Open PowerShell:
```powershell
cd d:\Projects\Rummy\Rummy\deployment\docker
docker compose up -d --build
```

### 2. Start the Stack (Normal daily start — Instant)
```powershell
cd d:\Projects\Rummy\Rummy\deployment\docker
docker compose up -d
```

### 3. Access URLs:
* **Web Game UI:** [http://localhost](http://localhost) (Port 80)
* **Game Service Node 1:** [http://localhost:8081/actuator/health](http://localhost:8081/actuator/health)
* **Game Service Node 2:** [http://localhost:8082/actuator/health](http://localhost:8082/actuator/health)
* **Prometheus Metrics:** [http://localhost:9090](http://localhost:9090)

---

### 4. Updating After Code Changes in Docker

Instead of rebuilding the entire stack, you can fast-rebuild only what you modified:

* **If you modified only the Frontend (React):**
  ```powershell
  docker compose up -d --build rummy-frontend
  ```
* **If you modified only the Backend (Java):**
  ```powershell
  docker compose up -d --build game-service-1 game-service-2
  ```

---

### 5. Daily Docker Lifecycle Commands

| Action | Command |
| :--- | :--- |
| **Start Stack** | `docker compose up -d` |
| **Check Status** | `docker compose ps` |
| **View Live Logs** | `docker compose logs -f [service_name]` |
| **Stop Stack** | `docker compose down` |
| **Stop & Clear Volumes** | `docker compose down -v` |

---

## ⚠️ Common Troubleshooting

### 1. `Bind for 0.0.0.0:6379 failed: port is already allocated`
An existing Redis instance or container is already using port 6379. Stop it with:
```powershell
docker stop redis
```
Then rerun:
```powershell
docker compose up -d
```

### 2. `error during connect: open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified.`
Docker Desktop is not started. Launch **Docker Desktop** from the Windows Start Menu, wait for the engine to start, and run the command again.
