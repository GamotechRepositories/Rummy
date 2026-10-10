import { Request, Response } from 'express';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { AdminUser } from '../models/AdminUser';
import { AuditLog } from '../models/AuditLog';
import { ENV } from '../config/env';
import { AdminAuthRequest } from '../middleware/authMiddleware';

// Automatically seed super admin on startup if no admin exists
export async function ensureDefaultAdmin(): Promise<void> {
  try {
    const count = await AdminUser.countDocuments();
    if (count === 0) {
      const salt = await bcrypt.genSalt(10);
      const hash = await bcrypt.hash('Admin@12345', salt);
      await AdminUser.create({
        username: 'admin',
        passwordHash: hash,
        displayName: 'Super Administrator',
        role: 'SUPER_ADMIN',
        isActive: true,
      });
      console.log('[AdminAuth] Default super admin created: "admin" / "Admin@12345"');
    }
  } catch (err: any) {
    console.error('[AdminAuth] Error seeding default admin:', err.message);
  }
}

export async function login(req: Request, res: Response): Promise<void> {
  try {
    const { username, password } = req.body;
    if (!username || !password) {
      res.status(400).json({ success: false, message: 'Username and password are required' });
      return;
    }

    const user = await AdminUser.findOne({ username: username.trim().toLowerCase() });
    if (!user || !user.isActive) {
      res.status(401).json({ success: false, message: 'Invalid administrative credentials' });
      return;
    }

    const isValid = await bcrypt.compare(password, user.passwordHash);
    if (!isValid) {
      res.status(401).json({ success: false, message: 'Invalid administrative credentials' });
      return;
    }

    user.lastLoginAt = new Date();
    await user.save();

    const token = jwt.sign(
      {
        id: user._id.toString(),
        username: user.username,
        role: user.role,
        displayName: user.displayName,
      },
      ENV.JWT_SECRET,
      { expiresIn: '24h' }
    );

    await AuditLog.create({
      adminId: user._id.toString(),
      adminUsername: user.username,
      action: 'ADMIN_LOGIN',
      resource: 'AUTH',
      ipAddress: req.ip,
      timestamp: new Date(),
    });

    res.json({
      success: true,
      token,
      user: {
        id: user._id,
        username: user.username,
        displayName: user.displayName,
        role: user.role,
      },
    });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}

export async function getProfile(req: AdminAuthRequest, res: Response): Promise<void> {
  try {
    if (!req.adminUser) {
      res.status(401).json({ success: false, message: 'Unauthorized' });
      return;
    }

    const user = await AdminUser.findById(req.adminUser.id).select('-passwordHash');
    if (!user) {
      res.status(404).json({ success: false, message: 'Admin user not found' });
      return;
    }

    res.json({ success: true, user });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}
