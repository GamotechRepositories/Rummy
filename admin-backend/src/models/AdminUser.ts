import mongoose, { Schema, Document } from 'mongoose';

export type AdminRole = 'SUPER_ADMIN' | 'GAME_OPERATOR' | 'AUDITOR';

export interface IAdminUser extends Document {
  username: string;
  passwordHash: string;
  displayName: string;
  role: AdminRole;
  isActive: boolean;
  lastLoginAt?: Date;
  createdAt: Date;
  updatedAt: Date;
}

const AdminUserSchema = new Schema<IAdminUser>(
  {
    username: { type: String, required: true, unique: true, index: true },
    passwordHash: { type: String, required: true },
    displayName: { type: String, required: true },
    role: { type: String, enum: ['SUPER_ADMIN', 'GAME_OPERATOR', 'AUDITOR'], default: 'GAME_OPERATOR' },
    isActive: { type: Boolean, default: true },
    lastLoginAt: { type: Date },
  },
  { timestamps: true }
);

export const AdminUser = mongoose.model<IAdminUser>('AdminUser', AdminUserSchema, 'admin_users');
