import { Request, Response, NextFunction } from 'express';
import jwt from 'jsonwebtoken';
import { ENV } from '../config/env';

export interface AdminAuthRequest extends Request {
  adminUser?: {
    id: string;
    username: string;
    role: string;
  };
}

export function authMiddleware(req: AdminAuthRequest, res: Response, next: NextFunction): void {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    res.status(401).json({ success: false, message: 'Authorization token missing or malformed' });
    return;
  }

  const token = authHeader.substring(7);
  try {
    const decoded = jwt.verify(token, ENV.JWT_SECRET) as { id: string; username: string; role: string };
    req.adminUser = decoded;
    next();
  } catch (err) {
    res.status(401).json({ success: false, message: 'Invalid or expired session token' });
  }
}
