import dotenv from 'dotenv';
dotenv.config();

export const ENV = {
  PORT: process.env.PORT || '5050',
  MONGODB_URI: process.env.MONGODB_URI || 'mongodb://localhost:27017/rummy_db',
  JWT_SECRET: process.env.JWT_SECRET || 'super_secret_royal_rummy_admin_jwt_key_2026_xyz',
  GAME_SERVICE_URL: process.env.GAME_SERVICE_URL || 'http://localhost:8081',
  ADMIN_API_KEY: process.env.ADMIN_API_KEY || 'admin-secret-key',
};
