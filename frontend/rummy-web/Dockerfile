# Multi-Stage Dockerfile for Rummy Web Frontend (React + TypeScript + Vite)
# -------------------------------------------------------------
# Stage 1: Build Stage
# -------------------------------------------------------------
FROM node:20-alpine AS builder
WORKDIR /app

# Copy dependency manifests
COPY package.json package-lock.json ./
RUN npm ci

# Copy source files
COPY . .

# Run production build
RUN npm run build

# -------------------------------------------------------------
# Stage 2: Production Nginx Runtime
# -------------------------------------------------------------
FROM nginx:alpine AS runner

# Copy customized Nginx configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy compiled static assets from builder stage
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=20s --timeout=3s --retries=3 \
  CMD wget -q -O - http://localhost/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
