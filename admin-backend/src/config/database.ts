import mongoose from 'mongoose';
import { ENV } from './env';

export async function connectDB(): Promise<void> {
  try {
    await mongoose.connect(ENV.MONGODB_URI, {
      serverSelectionTimeoutMS: 5000,
    });
    console.log(`[AdminDB] Connected to MongoDB at ${ENV.MONGODB_URI}`);
  } catch (err: any) {
    console.error(`[AdminDB] MongoDB connection error:`, err.message);
  }
}
