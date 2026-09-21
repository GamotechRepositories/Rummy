# Royal Rummy — Full Project Deployment Guide

Deploy the **complete stack** on **any server**: AWS EC2, DigitalOcean, Azure VM, GCP, bare metal, or a home VPS.

This is the **server-agnostic** guide. For Render-only shortcuts see [`RENDER_DEPLOY.md`](./RENDER_DEPLOY.md). Local dev see [`HOW_TO_RUN.md`](./HOW_TO_RUN.md).

---

## 1. What you are deploying

| Piece | Tech | Port (internal) | Role |
|--------|------|-----------------|------|
| **Frontend** | React + Vite → static files (Nginx) | `80` / `443` | Game UI |
| **Backend** | Java 21 Spring Boot `game-service` | `8081` | REST + WebSocket `/ws/game` |
| **MongoDB** | Atlas (recommended) or self-hosted | `27017` | Games, wallet, users |
| **Redis** | Optional | `6379` | Shared matchmaking + table→server routing (multi-node) |

```text
Internet
   │
   ▼
┌─────────────────────────────┐
│  Nginx (HTTPS :443)         │
│  /        → frontend static │
│  /api/*   → backend :8081   │
│  /ws/*    → backend :8081   │  (WebSocket upgrade)
│  /actuator → backend        │
└──────────────┬──────────────┘
               │
               ▼
        game-service :8081
               │
       ┌───────┴────────┐
       ▼                ▼
  MongoDB Atlas      Redis (optional)
```

**Single-server rule (simplest production):**  
1× EC2/VM + Nginx + 1× `game-service` + MongoDB Atlas.  
Keep `RUMMY_REDIS_ENABLED=false`.

**Multi-server scale:** Redis ON + sticky WebSocket sessions (see §10).

---

## 2. Prerequisites (any cloud)

- Ubuntu 22.04 LTS (or similar) VM  
- Public IP + DNS name (e.g. `play.yourdomain.com`)  
- Open ports: **22** (SSH), **80**, **443**  
- MongoDB Atlas cluster (or install Mongo on the same/private host)  
- Domain DNS `A` record → server public IP  

### Minimum VM size

| Load | AWS EC2 | RAM |
|------|---------|-----|
| Demo / low traffic | `t3.small` | 2 GB |
| Small production | `t3.medium` | 4 GB |
| Heavier tables | `t3.large`+ | 8 GB+ |

Java needs memory — avoid 1 GB for backend.

---

## 3. Recommended production layout (AWS EC2 example)

Works the same on any Linux VPS — replace “EC2” with your VM.

### 3.1 Create server

1. AWS Console → **EC2** → Launch instance  
2. AMI: **Ubuntu 22.04**  
3. Instance type: `t3.medium` (or above)  
4. Security group inbound:

| Type | Port | Source |
|------|------|--------|
| SSH | 22 | Your IP |
| HTTP | 80 | `0.0.0.0/0` |
| HTTPS | 443 | `0.0.0.0/0` |

5. Attach Elastic IP (optional but recommended)  
6. SSH in:

```bash
ssh -i your-key.pem ubuntu@YOUR_PUBLIC_IP
```

### 3.2 Install base packages

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y git curl unzip nginx certbot python3-certbot-nginx ufw

# Firewall
sudo ufw allow OpenSSH
sudo ufw allow 'Nginx Full'
sudo ufw enable
```

Choose **one** backend run style below:

- **A — Docker (easiest on any server)** → §4  
- **B — Native Java + Node build** → §5  

---

## 4. Option A — Docker deploy (any server)

### 4.1 Install Docker

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu
# log out and back in
docker --version
```

### 4.2 Clone repo

```bash
cd /opt
sudo git clone https://github.com/YOUR_ORG/Rummy.git rummy
sudo chown -R ubuntu:ubuntu /opt/rummy
cd /opt/rummy
```

### 4.3 Environment file for backend

```bash
sudo mkdir -p /etc/rummy
sudo nano /etc/rummy/backend.env
```

Paste (edit values):

```env
SPRING_DATA_MONGODB_URI=mongodb+srv://USER:PASS@CLUSTER/rummy_db?retryWrites=true&w=majority
SERVER_PORT=8081
JAVA_OPTS=-XX:MaxRAMPercentage=70.0 -XX:+UseG1GC
RUMMY_REDIS_ENABLED=false
RUMMY_KAFKA_ENABLED=false
SERVER_INSTANCE_ID=game-node-1
MATCHMAKING_AI_FALLBACK_MS=30000
```

### 4.4 Build & run backend container

```bash
cd /opt/rummy/backend
docker build -t rummy-backend:latest .

docker run -d \
  --name rummy-backend \
  --restart unless-stopped \
  -p 127.0.0.1:8081:8081 \
  --env-file /etc/rummy/backend.env \
  rummy-backend:latest
```

Health check:

```bash
curl http://127.0.0.1:8081/actuator/health
```

Expect `"status":"UP"` (Mongo must be reachable from this server — Atlas Network Access allow this EC2 IP or `0.0.0.0/0`).

### 4.5 Build frontend (with live API/WS URLs)

Vite bakes env at **build time**:

```bash
cd /opt/rummy/frontend/rummy-web

# Use YOUR domain (HTTPS after certbot). Temporary HTTP first is OK for testing.
export VITE_API_BASE_URL=https://play.yourdomain.com
export VITE_WS_URL=wss://play.yourdomain.com/ws/game

npm ci
npm run build
```

Serve `dist/` via Nginx (§6) **or** build the frontend Docker image:

```bash
# Pass build-args if your Dockerfile is extended; default Dockerfile runs npm run build
# Prefer host build + Nginx copy for clear VITE_* control:
sudo mkdir -p /var/www/rummy
sudo cp -r dist/* /var/www/rummy/
```

---

## 5. Option B — Native install (no Docker)

### 5.1 Install Java 21 + Node 20

```bash
sudo apt install -y openjdk-21-jre-headless
# Or Temurin 21 from Adoptium

curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs
java -version
node -v
```

### 5.2 Build backend jar

```bash
cd /opt/rummy/backend
./mvnw -pl game-service -am clean package -DskipTests
```

Jar path:

`game-service/target/game-service-*.jar`

### 5.3 systemd service

```bash
sudo nano /etc/systemd/system/rummy-backend.service
```

```ini
[Unit]
Description=Royal Rummy game-service
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/opt/rummy/backend
EnvironmentFile=/etc/rummy/backend.env
ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/rummy/backend/game-service/target/game-service-1.0.0-SNAPSHOT.jar
Restart=always
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

Adjust jar filename if version differs (`ls game-service/target/*.jar`).

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now rummy-backend
sudo systemctl status rummy-backend
curl http://127.0.0.1:8081/actuator/health
```

### 5.4 Frontend build

Same as §4.5 — `npm ci && npm run build` with `VITE_*`, copy to `/var/www/rummy`.

---

## 6. Nginx reverse proxy + HTTPS (required for production)

WebSocket **must** use `wss://` on HTTPS sites. Browser blocks insecure `ws://` on `https://` pages.

### 6.1 Nginx site config

```bash
sudo nano /etc/nginx/sites-available/rummy
```

```nginx
server {
    listen 80;
    server_name play.yourdomain.com;

    root /var/www/rummy;
    index index.html;

    # SPA fallback
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Backend REST
    location /api/ {
        proxy_pass http://127.0.0.1:8081/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Actuator (optional — lock down in real prod)
    location /actuator/ {
        proxy_pass http://127.0.0.1:8081/actuator/;
        proxy_set_header Host $host;
    }

    # WebSocket game gateway
    location /ws/ {
        proxy_pass http://127.0.0.1:8081/ws/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
```

Enable:

```bash
sudo ln -sf /etc/nginx/sites-available/rummy /etc/nginx/sites-enabled/rummy
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx
```

### 6.2 Let’s Encrypt HTTPS

```bash
sudo certbot --nginx -d play.yourdomain.com
```

Certbot updates Nginx for `443` automatically.

### 6.3 Frontend env after HTTPS

Rebuild frontend so Vite points at HTTPS/WSS **same host** (Nginx proxies both):

```bash
cd /opt/rummy/frontend/rummy-web
export VITE_API_BASE_URL=https://play.yourdomain.com
export VITE_WS_URL=wss://play.yourdomain.com/ws/game
npm ci && npm run build
sudo rm -rf /var/www/rummy/*
sudo cp -r dist/* /var/www/rummy/
```

> **Do not** set `VITE_WS_URL=wss://https://...` — that breaks DNS.

---

## 7. Full environment reference

### Backend (`/etc/rummy/backend.env`)

| Variable | Required | Example / notes |
|----------|----------|-----------------|
| `SPRING_DATA_MONGODB_URI` | Yes | Atlas connection string |
| `SERVER_PORT` | No | `8081` |
| `JAVA_OPTS` | No | `-XX:MaxRAMPercentage=70.0 -XX:+UseG1GC` |
| `RUMMY_REDIS_ENABLED` | No | `false` single node; `true` multi-node |
| `REDIS_URL` | If Redis on | `redis://:pass@host:6379` |
| `SPRING_PROFILES_ACTIVE` | Optional | `prod` enables Redis profile defaults |
| `SERVER_INSTANCE_ID` | Multi-node | `game-node-1` |
| `MATCHMAKING_AI_FALLBACK_MS` | No | `30000` (humans get more time to match) |
| `MATCHMAKING_MAX_QUEUE_MS` | No | `90000` |
| `RUMMY_KAFKA_ENABLED` | No | keep `false` unless Kafka deployed |

### Frontend (build-time only)

| Variable | Example |
|----------|---------|
| `VITE_API_BASE_URL` | `https://play.yourdomain.com` |
| `VITE_WS_URL` | `wss://play.yourdomain.com/ws/game` |

If API and UI share one Nginx domain (recommended), both point to that domain.

---

## 8. MongoDB Atlas checklist

1. Create cluster + database user  
2. Network Access → allow **EC2 public IP** (or `0.0.0.0/0` for quick demo)  
3. Connection string into `SPRING_DATA_MONGODB_URI`  
4. Test from server:

```bash
# optional
sudo apt install -y mongodb-mongosh
mongosh "YOUR_URI"
```

---

## 9. Docker Compose full stack (dev/staging on one machine)

Repo already has:

`deployment/docker/docker-compose.yml`

```bash
cd /opt/rummy/deployment/docker
docker compose up -d --build
```

This brings Mongo, Redis, Kafka, multiple game nodes, frontend — good for staging, heavier than minimal EC2 prod.

For **lean EC2 production**, prefer §4 + §6 (Atlas Mongo, no local Mongo/Kafka).

---

## 10. Scaling to multiple servers (later)

Master architecture (Phase 17–19):

1. Managed **Redis** (ElastiCache / Redis Cloud)  
2. On every game node:

```env
SPRING_PROFILES_ACTIVE=prod
RUMMY_REDIS_ENABLED=true
REDIS_URL=redis://...
SERVER_INSTANCE_ID=game-node-N
```

3. Load balancer in front of game nodes with **sticky sessions** for WebSocket  
4. Wrong-node joins return `TABLE_NOT_ON_THIS_SERVER`

Until then: **one** `game-service` process only.

---

## 11. Deploy / update procedure (day-2)

```bash
cd /opt/rummy
git pull

# Backend (Docker)
cd backend
docker build -t rummy-backend:latest .
docker stop rummy-backend && docker rm rummy-backend
docker run -d --name rummy-backend --restart unless-stopped \
  -p 127.0.0.1:8081:8081 --env-file /etc/rummy/backend.env rummy-backend:latest

# Frontend
cd /opt/rummy/frontend/rummy-web
export VITE_API_BASE_URL=https://play.yourdomain.com
export VITE_WS_URL=wss://play.yourdomain.com/ws/game
npm ci && npm run build
sudo rm -rf /var/www/rummy/* && sudo cp -r dist/* /var/www/rummy/
```

Native systemd:

```bash
cd /opt/rummy/backend && ./mvnw -pl game-service -am clean package -DskipTests
sudo systemctl restart rummy-backend
```

---

## 12. Smoke test checklist

1. `https://play.yourdomain.com` loads UI  
2. `https://play.yourdomain.com/actuator/health` → UP  
3. Browser DevTools → Network: WS `wss://play.yourdomain.com/ws/game` → Connected  
4. Two browsers, **same** stake/variant, Play within ~30s → same table  
5. Solo wait → AI partner after fallback timeout  
6. Mobile + desktop OK  

---

## 13. Troubleshooting

| Symptom | Fix |
|---------|-----|
| Page loads, API fails | Wrong `VITE_API_BASE_URL` or Nginx `/api` proxy |
| WS `1006` / failed | Missing Nginx `Upgrade` headers; use `wss://` on HTTPS |
| Health DOWN | Mongo URI / Atlas IP allowlist |
| Always bots, never 2 humans | Different stakes; or AI fallback too fast; both must queue same key |
| Cold / OOM | Bigger instance; raise RAM; check `JAVA_OPTS` |
| `TABLE_NOT_ON_THIS_SERVER` | Multi-node without sticky LB / Redis routing mismatch |

Logs:

```bash
docker logs -f rummy-backend
# or
sudo journalctl -u rummy-backend -f
sudo tail -f /var/log/nginx/error.log
```

---

## 14. Security basics (do before real users)

- SSH key only, disable password login  
- Don’t commit `.env` / secrets to git  
- Restrict `/actuator` to VPN/admin IP in Nginx  
- Atlas strong password + limited network  
- Keep Ubuntu + Docker images updated  
- Backups: Atlas continuous backup ON  

---

## 15. Quick decision guide

| Goal | Do this |
|------|---------|
| Fastest path on EC2 / any VPS | Docker backend §4 + Nginx §6 + Atlas |
| No Docker | Native Java §5 + Nginx §6 |
| Click-ops PaaS | [`RENDER_DEPLOY.md`](./RENDER_DEPLOY.md) |
| Local coding | [`HOW_TO_RUN.md`](./HOW_TO_RUN.md) |
| Many game servers | §10 Redis + sticky WS |

---

**End state:** Users open `https://play.yourdomain.com`, Nginx serves UI and proxies `/api` + `/ws` to `game-service` on `8081`, data lives in MongoDB Atlas. That is a full production deploy of this project on AWS EC2 or any equivalent server.
