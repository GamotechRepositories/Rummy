import React, { useEffect, useState } from 'react';
import { useGameStore } from '../store/useGameStore';
import { ShieldCheck, Clock, AlertTriangle, PhoneCall, CheckCircle2, Lock, X } from 'lucide-react';

interface ResponsibleGamblingModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const ResponsibleGamblingModal: React.FC<ResponsibleGamblingModalProps> = ({ isOpen, onClose }) => {
  const { playerId } = useGameStore();
  const [sessionMinutes, setSessionMinutes] = useState<number>(120);
  const [lossLimit, setLossLimit] = useState<number>(5000);
  const [realityCheck, setRealityCheck] = useState<number>(30);
  const [isSelfExcluded, setIsSelfExcluded] = useState<boolean>(false);
  const [coolOffExpiresAt, setCoolOffExpiresAt] = useState<string>('NONE');
  const [loading, setLoading] = useState<boolean>(false);
  const [feedback, setFeedback] = useState<string | null>(null);

  const fetchSettings = async () => {
    try {
      setLoading(true);
      const res = await fetch(`http://localhost:8081/api/responsible-gambling/settings?playerId=${playerId}`);
      if (res.ok) {
        const data = await res.json();
        setSessionMinutes(data.dailySessionLimitMinutes ?? 120);
        setLossLimit(data.dailyTokenLossLimit ?? 5000);
        setRealityCheck(data.realityCheckIntervalMinutes ?? 30);
        setIsSelfExcluded(data.isSelfExcluded ?? false);
        setCoolOffExpiresAt(data.coolOffExpiresAt ?? 'NONE');
      }
    } catch {
      // Fallback local mode
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (isOpen) {
      fetchSettings();
    }
  }, [isOpen, playerId]);

  const saveLimits = async () => {
    try {
      setLoading(true);
      const res = await fetch(
        `http://localhost:8081/api/responsible-gambling/limits?playerId=${playerId}&sessionMinutes=${sessionMinutes}&lossLimit=${lossLimit}&realityCheckMinutes=${realityCheck}`,
        { method: 'POST' }
      );
      if (res.ok) {
        setFeedback('Responsible play limits updated successfully!');
        setTimeout(() => setFeedback(null), 4000);
      }
    } catch {
      setFeedback('Error saving limits.');
    } finally {
      setLoading(false);
    }
  };

  const applyCoolOff = async (hours: number) => {
    try {
      setLoading(true);
      const res = await fetch(
        `http://localhost:8081/api/responsible-gambling/cool-off?playerId=${playerId}&hours=${hours}`,
        { method: 'POST' }
      );
      if (res.ok) {
        const data = await res.json();
        setCoolOffExpiresAt(data.coolOffExpiresAt);
        setFeedback(`Cool-off break activated for ${hours} hours.`);
        setTimeout(() => setFeedback(null), 4000);
      }
    } catch {
      setFeedback('Failed to activate cool-off.');
    } finally {
      setLoading(false);
    }
  };

  const selfExclude = async () => {
    if (!window.confirm('Are you sure you want to self-exclude? You will not be able to join game tables.')) {
      return;
    }
    try {
      setLoading(true);
      const res = await fetch(
        `http://localhost:8081/api/responsible-gambling/self-exclude?playerId=${playerId}&days=30&reason=PlayerInitiated`,
        { method: 'POST' }
      );
      if (res.ok) {
        setIsSelfExcluded(true);
        setFeedback('Account self-excluded for 30 days.');
      }
    } catch {
      setFeedback('Failed to process self-exclusion.');
    } finally {
      setLoading(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div style={{
      position: 'fixed',
      inset: 0,
      zIndex: 9999,
      backgroundColor: 'rgba(5, 10, 20, 0.85)',
      backdropFilter: 'blur(8px)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      padding: '16px'
    }}>
      <div style={{
        backgroundColor: '#0c1322',
        border: '1px solid rgba(16, 185, 129, 0.3)',
        borderRadius: '20px',
        width: '100%',
        maxWidth: '680px',
        boxShadow: '0 25px 60px rgba(0, 0, 0, 0.7), 0 0 30px rgba(16, 185, 129, 0.15)',
        overflow: 'hidden',
        color: '#f8fafc',
        fontFamily: 'Inter, system-ui, sans-serif'
      }}>
        {/* Header */}
        <div style={{
          background: 'linear-gradient(135deg, rgba(16, 185, 129, 0.2), rgba(6, 78, 59, 0.4))',
          padding: '20px 24px',
          borderBottom: '1px solid rgba(16, 185, 129, 0.2)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center'
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
            <div style={{
              background: 'linear-gradient(135deg, #10b981, #059669)',
              padding: '10px',
              borderRadius: '12px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center'
            }}>
              <ShieldCheck size={24} color="#ffffff" />
            </div>
            <div>
              <h2 style={{ fontSize: '20px', fontWeight: 700, margin: 0, letterSpacing: '-0.02em' }}>
                Safe Play & Player Protection
              </h2>
              <p style={{ margin: '2px 0 0', fontSize: '12px', color: '#94a3b8' }}>
                Player Protection & Responsible Gaming Controls
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            style={{
              background: 'rgba(255, 255, 255, 0.05)',
              border: 'none',
              borderRadius: '50%',
              width: '36px',
              height: '36px',
              cursor: 'pointer',
              color: '#94a3b8',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center'
            }}
          >
            <X size={20} />
          </button>
        </div>

        {/* Feedback Alert */}
        {feedback && (
          <div style={{
            margin: '16px 24px 0',
            padding: '12px 16px',
            borderRadius: '10px',
            backgroundColor: 'rgba(16, 185, 129, 0.15)',
            border: '1px solid rgba(16, 185, 129, 0.4)',
            color: '#34d399',
            fontSize: '13px',
            display: 'flex',
            alignItems: 'center',
            gap: '8px'
          }}>
            <CheckCircle2 size={16} />
            <span>{feedback}</span>
          </div>
        )}

        {/* Content */}
        <div style={{ padding: '24px', display: 'flex', flexDirection: 'column', gap: '20px', maxHeight: '70vh', overflowY: 'auto' }}>
          {/* Status Banners */}
          {isSelfExcluded && (
            <div style={{
              backgroundColor: 'rgba(239, 68, 68, 0.15)',
              border: '1px solid rgba(239, 68, 68, 0.4)',
              borderRadius: '12px',
              padding: '14px 18px',
              display: 'flex',
              alignItems: 'center',
              gap: '12px',
              color: '#f87171'
            }}>
              <Lock size={22} />
              <div style={{ fontSize: '13px' }}>
                <strong>Self-Exclusion Active:</strong> Your account is currently prevented from entering real-time rummy tables.
              </div>
            </div>
          )}

          {coolOffExpiresAt !== 'NONE' && (
            <div style={{
              backgroundColor: 'rgba(245, 158, 11, 0.15)',
              border: '1px solid rgba(245, 158, 11, 0.4)',
              borderRadius: '12px',
              padding: '14px 18px',
              display: 'flex',
              alignItems: 'center',
              gap: '12px',
              color: '#fbbf24'
            }}>
              <Clock size={22} />
              <div style={{ fontSize: '13px' }}>
                <strong>Cool-off Break Active:</strong> Active until {new Date(coolOffExpiresAt).toLocaleString()}.
              </div>
            </div>
          )}

          {/* Section 1: Play & Time Limits */}
          <div style={{
            backgroundColor: 'rgba(255, 255, 255, 0.03)',
            borderRadius: '14px',
            padding: '18px',
            border: '1px solid rgba(255, 255, 255, 0.06)'
          }}>
            <h3 style={{ fontSize: '14px', fontWeight: 600, margin: '0 0 14px', color: '#38bdf8', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <Clock size={16} /> Daily Play & Reality Check Limits
            </h3>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '12px' }}>
              <div>
                <label style={{ fontSize: '12px', color: '#94a3b8', display: 'block', marginBottom: '6px' }}>Max Daily Time (mins)</label>
                <input
                  type="number"
                  value={sessionMinutes}
                  onChange={(e) => setSessionMinutes(Number(e.target.value))}
                  style={{
                    width: '100%',
                    backgroundColor: 'rgba(0,0,0,0.4)',
                    border: '1px solid rgba(255,255,255,0.1)',
                    borderRadius: '8px',
                    padding: '8px 12px',
                    color: '#fff',
                    fontSize: '14px'
                  }}
                />
              </div>
              <div>
                <label style={{ fontSize: '12px', color: '#94a3b8', display: 'block', marginBottom: '6px' }}>Token Loss Cap</label>
                <input
                  type="number"
                  value={lossLimit}
                  onChange={(e) => setLossLimit(Number(e.target.value))}
                  style={{
                    width: '100%',
                    backgroundColor: 'rgba(0,0,0,0.4)',
                    border: '1px solid rgba(255,255,255,0.1)',
                    borderRadius: '8px',
                    padding: '8px 12px',
                    color: '#fff',
                    fontSize: '14px'
                  }}
                />
              </div>
              <div>
                <label style={{ fontSize: '12px', color: '#94a3b8', display: 'block', marginBottom: '6px' }}>Reality Check (mins)</label>
                <select
                  value={realityCheck}
                  onChange={(e) => setRealityCheck(Number(e.target.value))}
                  style={{
                    width: '100%',
                    backgroundColor: '#0f172a',
                    border: '1px solid rgba(255,255,255,0.1)',
                    borderRadius: '8px',
                    padding: '8px 12px',
                    color: '#fff',
                    fontSize: '14px'
                  }}
                >
                  <option value={15}>Every 15 mins</option>
                  <option value={30}>Every 30 mins</option>
                  <option value={60}>Every 60 mins</option>
                </select>
              </div>
            </div>
            <button
              onClick={saveLimits}
              disabled={loading}
              style={{
                marginTop: '14px',
                backgroundColor: '#10b981',
                color: '#fff',
                border: 'none',
                borderRadius: '8px',
                padding: '8px 18px',
                fontSize: '13px',
                fontWeight: 600,
                cursor: 'pointer'
              }}
            >
              Update Limits
            </button>
          </div>

          {/* Section 2: Cool-off Break */}
          <div style={{
            backgroundColor: 'rgba(255, 255, 255, 0.03)',
            borderRadius: '14px',
            padding: '18px',
            border: '1px solid rgba(255, 255, 255, 0.06)'
          }}>
            <h3 style={{ fontSize: '14px', fontWeight: 600, margin: '0 0 8px', color: '#fbbf24', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <AlertTriangle size={16} /> Take a Break (Cool-off)
            </h3>
            <p style={{ fontSize: '12px', color: '#94a3b8', margin: '0 0 12px' }}>
              Temporarily pause table matchmaking for yourself to maintain balance.
            </p>
            <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
              <button
                onClick={() => applyCoolOff(24)}
                disabled={loading}
                style={{
                  backgroundColor: 'rgba(245, 158, 11, 0.15)',
                  border: '1px solid rgba(245, 158, 11, 0.3)',
                  color: '#fbbf24',
                  borderRadius: '8px',
                  padding: '8px 14px',
                  fontSize: '12px',
                  fontWeight: 600,
                  cursor: 'pointer'
                }}
              >
                24 Hours Break
              </button>
              <button
                onClick={() => applyCoolOff(168)}
                disabled={loading}
                style={{
                  backgroundColor: 'rgba(245, 158, 11, 0.15)',
                  border: '1px solid rgba(245, 158, 11, 0.3)',
                  color: '#fbbf24',
                  borderRadius: '8px',
                  padding: '8px 14px',
                  fontSize: '12px',
                  fontWeight: 600,
                  cursor: 'pointer'
                }}
              >
                7 Days Break
              </button>
              <button
                onClick={() => applyCoolOff(720)}
                disabled={loading}
                style={{
                  backgroundColor: 'rgba(245, 158, 11, 0.15)',
                  border: '1px solid rgba(245, 158, 11, 0.3)',
                  color: '#fbbf24',
                  borderRadius: '8px',
                  padding: '8px 14px',
                  fontSize: '12px',
                  fontWeight: 600,
                  cursor: 'pointer'
                }}
              >
                30 Days Break
              </button>
              <button
                onClick={selfExclude}
                disabled={loading || isSelfExcluded}
                style={{
                  backgroundColor: 'rgba(239, 68, 68, 0.15)',
                  border: '1px solid rgba(239, 68, 68, 0.3)',
                  color: '#f87171',
                  borderRadius: '8px',
                  padding: '8px 14px',
                  fontSize: '12px',
                  fontWeight: 600,
                  cursor: 'pointer'
                }}
              >
                Self-Exclude Account
              </button>
            </div>
          </div>

          {/* Section 3: Player Support Resources */}
          <div style={{
            backgroundColor: 'rgba(16, 185, 129, 0.05)',
            border: '1px solid rgba(16, 185, 129, 0.2)',
            borderRadius: '14px',
            padding: '16px 18px',
            display: 'flex',
            alignItems: 'center',
            gap: '14px'
          }}>
            <div style={{
              backgroundColor: 'rgba(16, 185, 129, 0.2)',
              borderRadius: '50%',
              padding: '10px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: '#34d399'
            }}>
              <PhoneCall size={20} />
            </div>
            <div>
              <div style={{ fontSize: '13px', fontWeight: 600, color: '#f8fafc' }}>
                Player Support & Responsible Play (24/7 Helpline)
              </div>
              <div style={{ fontSize: '12px', color: '#94a3b8', marginTop: '2px' }}>
                Access confidential support, gameplay time management, and account safety tools.
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
