import { Response, NextFunction } from 'express';
import { AdminAuthRequest } from './authMiddleware';

export function requireRole(...roles: string[]) {
  return (req: AdminAuthRequest, res: Response, next: NextFunction): void => {
    if (!req.adminUser) {
      res.status(401).json({ success: false, message: 'Unauthenticated' });
      return;
    }

    if (!roles.includes(req.adminUser.role) && req.adminUser.role !== 'SUPER_ADMIN') {
      res.status(403).json({ success: false, message: 'Insufficient administrative privileges' });
      return;
    }

    next();
  };
}
