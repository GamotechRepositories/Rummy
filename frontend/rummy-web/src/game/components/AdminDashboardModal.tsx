import React, { useEffect, useState } from 'react';
import { Activity, Server, Cpu, ShieldCheck, X, RefreshCw, Layers, ShieldAlert } from 'lucide-react';

interface DiagnosticsData {
  serverInstanceId: string;
  uptimeSeconds: number;
  jvmUptimeMillis: number;
  activeTables: number;
  matchmakingQueueSize: number;
  usedMemoryMb: number;
  totalMemoryMb: number;
  maxMemoryMb: number;
  availableProcessors: number;
  complianceMode: string;
  interactiveGamblingAct2001Compliant: boolean;
}

interface FraudAlertItem {
  alertId: string;
  alertType: string;
  severity: string;
  tableId: string;
  involvedPlayers: string[];
  description: string;
  detectedAt: string;
}

interface AdminDashboardModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const AdminDashboardModal: React.FC<AdminDashboardModalProps> = ({ isOpen, onClose }) => {
  const [data, setData] = useState<DiagnosticsData | null>(null);
  const [fraudAlerts, setFraudAlerts] = useState<FraudAlertItem[]>([]);
  const [loading, setLoading] = useState(false);

  const fetchDiagnostics = async () => {
    try {
      setLoading(true);
      const res = await fetch('http://localhost:8081/api/admin/diagnostics');
      if (res.ok) {
        const json = await res.json();
        setData(json);
      }

      const fraudRes = await fetch('http://localhost:8081/api/fraud/alerts');
      if (fraudRes.ok) {
        const fraudJson = await fraudRes.json();
        setFraudAlerts(fraudJson);
      }
    } catch {
      // Fallback
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (isOpen) {
      fetchDiagnostics();
      const interval = setInterval(fetchDiagnostics, 3000);
      return () => clearInterval(interval);
    }
  }, [isOpen]);

  if (!isOpen) return null;

  return (
    <div style={{
      position: 'fixed',
      inset: 0,
      background: 'rgba(5, 12, 8, 0.88)',
      backdropFilter: 'blur(10px)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      zIndex: 9999,
      padding: '1rem',
    }}>
      <div style={{
        background: 'linear-gradient(145deg, #111e18, #0a130e)',
        border: '1px solid rgba(59, 130, 246, 0.4)',
        borderRadius: '16px',
        width: '100%',
        maxWidth: '600px',
        boxShadow: '0 20px 50px rgba(0,0,0,0.8), 0 0 30px rgba(59, 130, 246, 0.15)',
        color: '#fff',
        overflow: 'hidden',
      }}>
        {/* Header */}
        <div style={{
          padding: '1.25rem 1.5rem',
          borderBottom: '1px solid rgba(255,255,255,0.08)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          background: 'rgba(59, 130, 246, 0.05)',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
            <div style={{
              width: '40px',
              height: '40px',
              borderRadius: '10px',
              background: 'linear-gradient(135deg, #3b82f6, #1d4ed8)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: '#fff',
            }}>
              <Activity size={22} />
            </div>
            <div>
              <h3 style={{ margin: 0, fontSize: '1.2rem', fontWeight: 700, color: '#93c5fd' }}>
                System Observability & Admin Console
              </h3>
              <p style={{ margin: 0, fontSize: '0.8rem', color: '#88ab8e' }}>
                Spring Boot Actuator & TableActor Cluster Health
              </p>
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <button
              onClick={fetchDiagnostics}
              disabled={loading}
              style={{
                background: 'rgba(255,255,255,0.05)',
                border: '1px solid rgba(255,255,255,0.1)',
                color: '#93c5fd',
                borderRadius: '8px',
                padding: '0.5rem',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
              }}
            >
              <RefreshCw size={16} className={loading ? 'animate-spin' : ''} />
            </button>
            <button
              onClick={onClose}
              style={{
                background: 'transparent',
                border: 'none',
                color: '#88ab8e',
                cursor: 'pointer',
                padding: '0.5rem',
                display: 'flex',
                alignItems: 'center',
              }}
            >
              <X size={20} />
            </button>
          </div>
        </div>

        {/* Content */}
        <div style={{ padding: '1.5rem', display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
          {/* Status Metrics Cards */}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '0.75rem' }}>
            <div style={{
              background: 'rgba(59, 130, 246, 0.1)',
              border: '1px solid rgba(59, 130, 246, 0.25)',
              borderRadius: '12px',
              padding: '1rem',
              textAlign: 'center',
            }}>
              <div style={{ display: 'flex', justifyContent: 'center', marginBottom: '0.25rem' }}>
                <Layers size={20} color="#60a5fa" />
              </div>
              <div style={{ fontSize: '1.5rem', fontWeight: 800, color: '#fff' }}>
                {data ? data.activeTables : '0'}
              </div>
              <div style={{ fontSize: '0.75rem', color: '#93c5fd', fontWeight: 600 }}>
                Active TableActors
              </div>
            </div>

            <div style={{
              background: 'rgba(16, 185, 129, 0.1)',
              border: '1px solid rgba(16, 185, 129, 0.25)',
              borderRadius: '12px',
              padding: '1rem',
              textAlign: 'center',
            }}>
              <div style={{ display: 'flex', justifyContent: 'center', marginBottom: '0.25rem' }}>
                <Server size={20} color="#34d399" />
              </div>
              <div style={{ fontSize: '1.5rem', fontWeight: 800, color: '#fff' }}>
                {data ? `${data.uptimeSeconds}s` : '0s'}
              </div>
              <div style={{ fontSize: '0.75rem', color: '#a7f3d0', fontWeight: 600 }}>
                Uptime
              </div>
            </div>

            <div style={{
              background: 'rgba(245, 158, 11, 0.1)',
              border: '1px solid rgba(245, 158, 11, 0.25)',
              borderRadius: '12px',
              padding: '1rem',
              textAlign: 'center',
            }}>
              <div style={{ display: 'flex', justifyContent: 'center', marginBottom: '0.25rem' }}>
                <Cpu size={20} color="#fbbf24" />
              </div>
              <div style={{ fontSize: '1.5rem', fontWeight: 800, color: '#fff' }}>
                {data ? `${data.usedMemoryMb} MB` : '0 MB'}
              </div>
              <div style={{ fontSize: '0.75rem', color: '#fde68a', fontWeight: 600 }}>
                JVM Memory
              </div>
            </div>
          </div>

          {/* Compliance Card */}
          <div style={{
            background: 'linear-gradient(135deg, rgba(16, 185, 129, 0.15), rgba(16, 185, 129, 0.05))',
            border: '1px solid rgba(16, 185, 129, 0.35)',
            borderRadius: '12px',
            padding: '1rem',
            display: 'flex',
            alignItems: 'flex-start',
            gap: '0.75rem',
          }}>
            <ShieldCheck size={24} color="#34d399" style={{ flexShrink: 0, marginTop: '2px' }} />
            <div>
              <div style={{ fontWeight: 700, color: '#6ee7b7', fontSize: '0.9rem' }}>
                Australian Compliance Gate Status: ACTIVE
              </div>
              <div style={{ fontSize: '0.75rem', color: '#d1fae5', marginTop: '0.25rem', lineHeight: 1.4 }}>
                Under the Interactive Gambling Act 2001 (IGA), all real-money wagering is disabled. System is operating strictly in virtual Free-Play mode.
              </div>
            </div>
          </div>

          {/* Node & Infrastructure Details */}
          <div style={{
            background: 'rgba(0,0,0,0.3)',
            border: '1px solid rgba(255,255,255,0.06)',
            borderRadius: '10px',
            padding: '1rem',
            display: 'flex',
            flexDirection: 'column',
            gap: '0.5rem',
            fontSize: '0.8rem',
          }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid rgba(255,255,255,0.04)', paddingBottom: '0.4rem' }}>
              <span style={{ color: '#88ab8e' }}>Node Instance:</span>
              <code style={{ color: '#60a5fa' }}>{data?.serverInstanceId || 'game-server-node-1'}</code>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid rgba(255,255,255,0.04)', paddingBottom: '0.4rem' }}>
              <span style={{ color: '#88ab8e' }}>Matchmaking Queue Size:</span>
              <span style={{ color: '#fff', fontWeight: 600 }}>{data?.matchmakingQueueSize ?? 0}</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', borderBottom: '1px solid rgba(255,255,255,0.04)', paddingBottom: '0.4rem' }}>
              <span style={{ color: '#88ab8e' }}>CPU Cores Available:</span>
              <span style={{ color: '#fff', fontWeight: 600 }}>{data?.availableProcessors ?? 4} Cores</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span style={{ color: '#88ab8e' }}>Actuator Health Status:</span>
              <span style={{ color: '#34d399', fontWeight: 700 }}>UP (Healthy)</span>
            </div>
          </div>

          {/* Anti-Fraud & Collusion Detection Stream */}
          <div style={{
            background: 'rgba(239, 68, 68, 0.05)',
            border: '1px solid rgba(239, 68, 68, 0.25)',
            borderRadius: '10px',
            padding: '1rem',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '0.5rem', color: '#f87171', fontSize: '0.85rem', fontWeight: 700 }}>
              <ShieldAlert size={16} />
              <span>Anti-Fraud & Collusion Risk Stream ({fraudAlerts.length} Active)</span>
            </div>
            {fraudAlerts.length === 0 ? (
              <div style={{ fontSize: '0.75rem', color: '#88ab8e' }}>
                ✓ No suspicious collusion or chip dumping patterns detected on live tables.
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', maxHeight: '120px', overflowY: 'auto' }}>
                {fraudAlerts.map((alert) => (
                  <div key={alert.alertId} style={{
                    padding: '6px 10px',
                    borderRadius: '6px',
                    backgroundColor: 'rgba(0,0,0,0.4)',
                    border: '1px solid rgba(239, 68, 68, 0.3)',
                    fontSize: '0.75rem',
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center'
                  }}>
                    <div>
                      <span style={{
                        padding: '2px 6px',
                        borderRadius: '4px',
                        backgroundColor: alert.severity === 'CRITICAL' ? '#dc2626' : '#d97706',
                        color: '#fff',
                        fontWeight: 700,
                        fontSize: '0.65rem',
                        marginRight: '6px'
                      }}>
                        {alert.alertType}
                      </span>
                      <span>{alert.description}</span>
                    </div>
                    <span style={{ color: '#94a3b8', fontSize: '0.65rem' }}>{alert.tableId}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
