# Render.com — Royal Rummy Live Deploy

Minimal production setup: **MongoDB Atlas** + **Backend Web Service** + **Frontend Static Site**.

---

## 0) One-time prep

1. Push latest code to GitHub (including `apiConfig.ts` env support).
2. Keep **MongoDB Atlas** ready (you already use it locally).
3. In Atlas → Network Access → allow `0.0.0.0/0` (or Render IPs) for the cluster.

---

## 1) Deploy Backend (Web Service)

Render Dashboard → **New** → **Web Service** → connect your GitHub repo.

| Setting | Value |
|--------|--------|
| Name | `rummy-backend` |
| Root Directory | `backend` |
| Runtime | **Docker** |
| Dockerfile Path | `./Dockerfile` |
| Instance | Free or Starter (Java needs RAM; Free may be slow/cold) |

### Environment variables (Backend)

```
SPRING_DATA_MONGODB_URI=mongodb+srv://USER:PASS@CLUSTER/rummy_db?retryWrites=true&w=majority
SERVER_PORT=8081
JAVA_OPTS=-XX:MaxRAMPercentage=70.0 -XX:+UseG1GC
```

### Health check

- Path: `/actuator/health`

After deploy you get something like:

`https://rummy-backend.onrender.com`

Test:

```
https://rummy-backend.onrender.com/actuator/health
```

---

## 2) Deploy Frontend (Static Site)

Render → **New** → **Static Site** → same repo.

| Setting | Value |
|--------|--------|
| Name | `rummy-web` |
| Root Directory | `frontend/rummy-web` |
| Build Command | `npm ci && npm run build` |
| Publish Directory | `dist` |

### Environment variables (Frontend — build time)

Replace with your real backend URL:

```
VITE_API_BASE_URL=https://rummy-backend.onrender.com
VITE_WS_URL=wss://rummy-backend.onrender.com/ws/game
```

> Note: `wss://` (not `ws://`) because Render is HTTPS.
>
> **Do NOT write** `wss://https://...` — that breaks WebSocket with `ERR_NAME_NOT_RESOLVED`.
>
> Correct example for your service:
> ```
> VITE_API_BASE_URL=https://rummy-backend-bj5l.onrender.com
> VITE_WS_URL=wss://rummy-backend-bj5l.onrender.com/ws/game
> ```

Redeploy frontend after setting these env vars.

Frontend URL example:

`https://rummy-web.onrender.com`

---

## 3) Order of deploy

1. Atlas Mongo OK  
2. Backend live + health green  
3. Frontend with `VITE_*` pointing to backend  
4. Open frontend URL → Play Now

---

## 4) Important notes

- **Free Render** services sleep after idle → first open can take 30–60s (Java cold start).
- WebSocket works on Render Web Services; use `wss://`.
- Do **not** put Mongo password in Git — only Render env vars.
- If matchmaking/API fails in browser: check DevTools → Network (CORS / wrong `VITE_API_BASE_URL`).
- If WS fails: confirm `VITE_WS_URL` uses `wss://.../ws/game`.

---

## 5) Optional: Docker frontend instead of Static

If you prefer the existing Nginx Dockerfile:

- Root Directory: `frontend/rummy-web`
- Runtime: Docker  
- You must still pass API/WS URLs at **build** time (`ARG`/`ENV` + Vite), or change Nginx to proxy `/api` and `/ws` to the backend service name (harder across two Render services).

**Recommended for you:** Static Site + env vars (simpler).

---

## 6) Local check before deploy

```powershell
cd e:\Rummy\frontend\rummy-web
$env:VITE_API_BASE_URL="https://rummy-backend.onrender.com"
$env:VITE_WS_URL="wss://rummy-backend.onrender.com/ws/game"
npm run build
```

Build must succeed before trusting Render build.
