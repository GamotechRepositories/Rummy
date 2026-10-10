import express from 'express';
import cors from 'cors';
import { ENV } from './config/env';
import { connectDB } from './config/database';
import { ensureDefaultAdmin } from './controllers/authController';
import adminRoutes from './routes/adminRoutes';

const app = express();

app.use(cors({ origin: true, credentials: true }));
app.use(express.json());

// API health check
app.get('/health', (req, res) => {
  res.json({
    status: 'ONLINE',
    service: 'Royal Rummy Standalone Admin Backend',
    timestamp: new Date().toISOString(),
  });
});

// Admin REST endpoints
app.use('/api/admin', adminRoutes);

// Connect DB & start server
async function bootstrap() {
  await connectDB();
  await ensureDefaultAdmin();

  const PORT = parseInt(ENV.PORT, 10) || 5050;
  app.listen(PORT, '0.0.0.0', () => {
    console.log(`🚀 [AdminBackend] Server listening on http://localhost:${PORT}`);
  });
}

bootstrap().catch((err) => {
  console.error('[AdminBackend] Fatal bootstrap error:', err);
  process.exit(1);
});
