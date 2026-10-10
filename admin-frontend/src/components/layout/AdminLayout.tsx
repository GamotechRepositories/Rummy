import React, { useState, useEffect } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import {
  LayoutDashboard,
  Coins,
  Layers,
  Flame,
  Sparkles,
  Eye,
  History,
  Sliders,
  LogOut,
  Server,
  Users,
  Radio,
  PowerOff,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { api } from '../../api/client';

export const AdminLayout: React.FC = () => {
  const { user, logout } = useAuth();
  const [telemetry, setTelemetry] = useState<any>({
    activePlayersCCU: 0,
    liveTables: 0,
    queueSize: 0,
    isDraining: false,
    serverInstanceId: 'game-node-1',
  });
  const [drainingLoading, setDrainingLoading] = useState(false);

  const fetchTelemetry = async () => {
    try {
      const res = await api.get('/overview/metrics');
      if (res.data?.telemetry) {
        setTelemetry(res.data.telemetry);
      }
    } catch {
      // offline or silent retry
    }
  };

  useEffect(() => {
    fetchTelemetry();
    const interval = setInterval(fetchTelemetry, 5000);
    return () => clearInterval(interval);
  }, []);

  const handleToggleDrain = async () => {
    if (!window.confirm(`Are you sure you want to ${telemetry.isDraining ? 'CANCEL drain mode' : 'START graceful node draining'}?`)) {
      return;
    }
    setDrainingLoading(true);
    try {
      await api.post('/overview/drain', { drain: !telemetry.isDraining });
      await fetchTelemetry();
    } catch (err: any) {
      alert(`Drain action failed: ${err.message}`);
    } finally {
      setDrainingLoading(false);
    }
  };

  return (
    <div className="admin-shell">
      {/* Sidebar */}
      <aside className="admin-sidebar">
        <div className="admin-sidebar-header">
          <div className="admin-brand-icon">👑</div>
          <div>
            <div className="admin-brand-title">ROYAL RUMMY</div>
            <div className="admin-brand-sub">Admin Command Center</div>
          </div>
        </div>

        <nav className="admin-nav">
          <div className="admin-nav-group-label">OVERVIEW</div>
          <NavLink to="/" end className={({ isActive }) => `admin-nav-item ${isActive ? 'active' : ''}`}>
            <LayoutDashboard size={16} />
            <span>Executive Overview</span>
          </NavLink>

          <div className="admin-nav-group-label">GAME VARIANTS</div>
          <NavLink to="/variants/points" className={({ isActive }) => `admin-nav-item ${isActive ? 'active' : ''}`}>
            <Coins size={16} color="var(--accent-emerald)" />
            <span>Points Rummy</span>
          </NavLink>
          <NavLink to="/variants/deals" className={({ isActive }) => `admin-nav-item ${isActive ? 'active' : ''}`}>
            <Layers size={16} color="var(--accent-cyan)" />
            <span>Deals Rummy</span>
          </NavLink>
          <NavLink to="/variants/pool" className={({ isActive }) => `admin-nav-item ${isActive ? 'active' : ''}`}>
            <Flame size={16} color="var(--accent-amber)" />
            <span>Pool Rummy</span>
          </NavLink>
          <NavLink to="/variants/21-card" className={({ isActive }) => `admin-nav-item ${isActive ? 'active' : ''}`}>
            <Sparkles size={16} color="var(--accent-violet)" />
            <span>21-Card Rummy</span>
          </NavLink>

          <div className="admin-nav-group-label">OPERATIONS & CONTROL</div>
          <NavLink to="/tables" className={({ isActive }) => `admin-nav-item ${isActive ? 'active' : ''}`}>
            <Eye size={16} />
            <span>Live Tables</span>
          </NavLink>
          <NavLink to="/replay" className={({ isActive }) => `admin-nav-item ${isActive ? 'active' : ''}`}>
            <History size={16} />
            <span>Dispute Replay</span>
          </NavLink>
          <NavLink to="/config" className={({ isActive }) => `admin-nav-item ${isActive ? 'active' : ''}`}>
            <Sliders size={16} />
            <span>Rules & Rake Config</span>
          </NavLink>
        </nav>
      </aside>

      {/* Main Panel */}
      <div className="admin-main">
        {/* Topbar Telemetry Header */}
        <header className="admin-topbar">
          <div className="topbar-telemetry">
            <div className="telemetry-pill">
              <span className={`live-dot ${telemetry.liveTables > 0 ? '' : 'amber'}`} />
              <span>CCU:</span>
              <strong>{telemetry.activePlayersCCU}</strong>
            </div>

            <div className="telemetry-pill">
              <Radio size={14} color="var(--accent-emerald)" />
              <span>RAM Tables:</span>
              <strong>{telemetry.liveTables}</strong>
            </div>

            <div className="telemetry-pill">
              <Users size={14} color="var(--text-dim)" />
              <span>Matchmaking Queue:</span>
              <strong>{telemetry.queueSize}</strong>
            </div>

            <div className="telemetry-pill">
              <Server size={14} color="var(--accent-gold)" />
              <span>Node:</span>
              <strong style={{ fontSize: '11px' }}>{telemetry.serverInstanceId}</strong>
            </div>

            {telemetry.isDraining && (
              <span className="badge badge-rose" style={{ animation: 'pulse-dot 1.5s infinite' }}>
                DRAINING IN PROGRESS
              </span>
            )}
          </div>

          <div className="topbar-actions">
            {user?.role === 'SUPER_ADMIN' && (
              <button
                className={`btn ${telemetry.isDraining ? 'btn-danger' : ''}`}
                onClick={handleToggleDrain}
                disabled={drainingLoading}
                title="Graceful Node Draining"
                style={{ fontSize: '11.5px', padding: '4px 10px' }}
              >
                <PowerOff size={13} />
                {telemetry.isDraining ? 'Cancel Drain' : 'Drain Node'}
              </button>
            )}

            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', borderLeft: '1px solid var(--border-subtle)', paddingLeft: '12px' }}>
              <span className="badge badge-amber">{user?.role}</span>
              <span style={{ fontWeight: 600, color: '#fff', fontSize: '12px' }}>{user?.displayName || user?.username}</span>
              <button onClick={logout} className="btn" style={{ padding: '4px 8px' }} title="Sign Out">
                <LogOut size={14} />
              </button>
            </div>
          </div>
        </header>

        {/* Content Body */}
        <main className="admin-content">
          <Outlet />
        </main>
      </div>
    </div>
  );
};
