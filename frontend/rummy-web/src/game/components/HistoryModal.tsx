import React, { useEffect, useState } from 'react';
import type { PlayerHistoryResponse } from '../types/game';

interface HistoryModalProps {
  playerId: string;
  isOpen: boolean;
  onClose: () => void;
}

export const HistoryModal: React.FC<HistoryModalProps> = ({ playerId, isOpen, onClose }) => {
  const [data, setData] = useState<PlayerHistoryResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  const fetchHistory = async () => {
    if (!playerId) return;
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`http://localhost:8081/api/history/player/${encodeURIComponent(playerId)}`);
      if (!res.ok) {
        throw new Error(`Failed to load history (HTTP ${res.status})`);
      }
      const json: PlayerHistoryResponse = await res.json();
      setData(json);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Unknown error';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (isOpen) {
      fetchHistory();
    }
  }, [isOpen, playerId]);

  if (!isOpen) return null;

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        backgroundColor: 'rgba(0, 0, 0, 0.75)',
        backdropFilter: 'blur(6px)',
        zIndex: 1000,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '1rem',
      }}
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        style={{
          width: '100%',
          maxWidth: '720px',
          maxHeight: '85vh',
          backgroundColor: '#0d1f18',
          border: '2px solid rgba(212, 175, 55, 0.4)',
          borderRadius: '16px',
          boxShadow: '0 25px 50px -12px rgba(0, 0, 0, 0.8), 0 0 30px rgba(212, 175, 55, 0.15)',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
        }}
      >
        {/* Modal Header */}
        <div
          style={{
            padding: '1.25rem 1.5rem',
            borderBottom: '1px solid rgba(212, 175, 55, 0.2)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            background: 'linear-gradient(180deg, rgba(212, 175, 55, 0.12) 0%, transparent 100%)',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
            <span style={{ fontSize: '1.5rem' }}>📜</span>
            <div>
              <h2
                style={{
                  margin: 0,
                  fontSize: '1.25rem',
                  fontFamily: 'Cinzel, serif',
                  color: '#d4af37',
                  letterSpacing: '0.05em',
                }}
              >
                Career Match History & Profile
              </h2>
              <span style={{ fontSize: '0.8rem', color: '#94a3b8' }}>
                Audited match records for ID: <code style={{ color: '#fcd34d' }}>{playerId}</code>
              </span>
            </div>
          </div>
          <button
            onClick={onClose}
            style={{
              background: 'transparent',
              border: 'none',
              color: '#94a3b8',
              fontSize: '1.5rem',
              cursor: 'pointer',
              padding: '0.25rem 0.5rem',
              borderRadius: '6px',
              transition: 'color 0.15s ease',
            }}
            aria-label="Close"
          >
            ✕
          </button>
        </div>

        {/* Modal Body */}
        <div style={{ padding: '1.5rem', overflowY: 'auto', flex: 1 }}>
          {loading && (
            <div style={{ textAlign: 'center', padding: '3rem 1rem', color: '#94a3b8' }}>
              <div style={{ fontSize: '2rem', marginBottom: '0.5rem' }}>⏳</div>
              <div>Retrieving career match records...</div>
            </div>
          )}

          {error && !loading && (
            <div
              style={{
                backgroundColor: 'rgba(239, 68, 68, 0.15)',
                border: '1px solid rgba(239, 68, 68, 0.4)',
                borderRadius: '8px',
                padding: '1rem',
                color: '#fca5a5',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
              }}
            >
              <span>{error}</span>
              <button
                onClick={fetchHistory}
                style={{
                  padding: '0.35rem 0.75rem',
                  backgroundColor: '#ef4444',
                  color: '#fff',
                  border: 'none',
                  borderRadius: '6px',
                  cursor: 'pointer',
                  fontWeight: 600,
                  fontSize: '0.8rem',
                }}
              >
                Retry
              </button>
            </div>
          )}

          {!loading && !error && data && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
              {/* Career Stats Grid */}
              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(4, 1fr)',
                  gap: '0.75rem',
                }}
              >
                <div
                  style={{
                    backgroundColor: 'rgba(255, 255, 255, 0.03)',
                    border: '1px solid rgba(212, 175, 55, 0.2)',
                    borderRadius: '10px',
                    padding: '0.85rem',
                    textAlign: 'center',
                  }}
                >
                  <div style={{ fontSize: '0.75rem', color: '#94a3b8', textTransform: 'uppercase' }}>
                    Virtual Points
                  </div>
                  <div
                    style={{
                      fontSize: '1.35rem',
                      fontWeight: 700,
                      color: '#fbbf24',
                      marginTop: '0.25rem',
                      fontFamily: 'Outfit, sans-serif',
                    }}
                  >
                    🪙 {data.profile.virtualPoints.toLocaleString()}
                  </div>
                </div>

                <div
                  style={{
                    backgroundColor: 'rgba(255, 255, 255, 0.03)',
                    border: '1px solid rgba(212, 175, 55, 0.2)',
                    borderRadius: '10px',
                    padding: '0.85rem',
                    textAlign: 'center',
                  }}
                >
                  <div style={{ fontSize: '0.75rem', color: '#94a3b8', textTransform: 'uppercase' }}>
                    Matches Played
                  </div>
                  <div
                    style={{
                      fontSize: '1.35rem',
                      fontWeight: 700,
                      color: '#f8fafc',
                      marginTop: '0.25rem',
                    }}
                  >
                    {data.profile.gamesPlayed}
                  </div>
                </div>

                <div
                  style={{
                    backgroundColor: 'rgba(255, 255, 255, 0.03)',
                    border: '1px solid rgba(212, 175, 55, 0.2)',
                    borderRadius: '10px',
                    padding: '0.85rem',
                    textAlign: 'center',
                  }}
                >
                  <div style={{ fontSize: '0.75rem', color: '#94a3b8', textTransform: 'uppercase' }}>
                    Matches Won
                  </div>
                  <div
                    style={{
                      fontSize: '1.35rem',
                      fontWeight: 700,
                      color: '#4ade80',
                      marginTop: '0.25rem',
                    }}
                  >
                    {data.profile.gamesWon}
                  </div>
                </div>

                <div
                  style={{
                    backgroundColor: 'rgba(255, 255, 255, 0.03)',
                    border: '1px solid rgba(212, 175, 55, 0.2)',
                    borderRadius: '10px',
                    padding: '0.85rem',
                    textAlign: 'center',
                  }}
                >
                  <div style={{ fontSize: '0.75rem', color: '#94a3b8', textTransform: 'uppercase' }}>
                    Win Rate
                  </div>
                  <div
                    style={{
                      fontSize: '1.35rem',
                      fontWeight: 700,
                      color: data.winRate >= 50 ? '#34d399' : '#f87171',
                      marginTop: '0.25rem',
                    }}
                  >
                    {data.winRate}%
                  </div>
                </div>
              </div>

              {/* Match Table */}
              <div>
                <h3
                  style={{
                    margin: '0 0 0.75rem 0',
                    fontSize: '0.95rem',
                    color: '#e2e8f0',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                  }}
                >
                  <span>Recent Round Results</span>
                  <button
                    onClick={fetchHistory}
                    style={{
                      background: 'none',
                      border: 'none',
                      color: '#38bdf8',
                      cursor: 'pointer',
                      fontSize: '0.8rem',
                      fontWeight: 500,
                    }}
                  >
                    🔄 Refresh
                  </button>
                </h3>

                {data.results.length === 0 ? (
                  <div
                    style={{
                      padding: '2.5rem 1rem',
                      textAlign: 'center',
                      backgroundColor: 'rgba(0, 0, 0, 0.2)',
                      borderRadius: '10px',
                      border: '1px dashed rgba(212, 175, 55, 0.25)',
                      color: '#94a3b8',
                    }}
                  >
                    <div style={{ fontSize: '1.8rem', marginBottom: '0.5rem' }}>🂠</div>
                    <div>No completed matches recorded yet.</div>
                    <div style={{ fontSize: '0.8rem', marginTop: '0.25rem', color: '#64748b' }}>
                      Play a round against the Sydney Sam bot to record your first audited match!
                    </div>
                  </div>
                ) : (
                  <div
                    style={{
                      overflowX: 'auto',
                      borderRadius: '10px',
                      border: '1px solid rgba(255, 255, 255, 0.08)',
                    }}
                  >
                    <table
                      style={{
                        width: '100%',
                        borderCollapse: 'collapse',
                        fontSize: '0.85rem',
                        textAlign: 'left',
                      }}
                    >
                      <thead>
                        <tr
                          style={{
                            backgroundColor: 'rgba(255, 255, 255, 0.05)',
                            color: '#94a3b8',
                            borderBottom: '1px solid rgba(255, 255, 255, 0.1)',
                          }}
                        >
                          <th style={{ padding: '0.75rem 1rem' }}>Game ID</th>
                          <th style={{ padding: '0.75rem 1rem' }}>Table</th>
                          <th style={{ padding: '0.75rem 1rem' }}>Outcome</th>
                          <th style={{ padding: '0.75rem 1rem' }}>Penalty Pts</th>
                          <th style={{ padding: '0.75rem 1rem' }}>Date & Time</th>
                        </tr>
                      </thead>
                      <tbody>
                        {data.results.map((r, idx) => {
                          const date = new Date(r.createdAt);
                          const dateStr = date.toLocaleDateString(undefined, {
                            month: 'short',
                            day: 'numeric',
                          });
                          const timeStr = date.toLocaleTimeString(undefined, {
                            hour: '2-digit',
                            minute: '2-digit',
                          });

                          return (
                            <tr
                              key={r.id || idx}
                              style={{
                                borderBottom: '1px solid rgba(255, 255, 255, 0.04)',
                                backgroundColor: idx % 2 === 0 ? 'transparent' : 'rgba(255, 255, 255, 0.02)',
                              }}
                            >
                              <td style={{ padding: '0.75rem 1rem', fontFamily: 'monospace', color: '#cbd5e1' }}>
                                {r.gameId}
                              </td>
                              <td style={{ padding: '0.75rem 1rem', color: '#94a3b8' }}>
                                {r.tableId}
                              </td>
                              <td style={{ padding: '0.75rem 1rem' }}>
                                {r.won ? (
                                  <span
                                    style={{
                                      display: 'inline-block',
                                      padding: '0.2rem 0.55rem',
                                      borderRadius: '9999px',
                                      fontSize: '0.75rem',
                                      fontWeight: 700,
                                      backgroundColor: 'rgba(34, 197, 94, 0.15)',
                                      color: '#4ade80',
                                      border: '1px solid rgba(34, 197, 94, 0.4)',
                                    }}
                                  >
                                    🏆 VICTORY
                                  </span>
                                ) : (
                                  <span
                                    style={{
                                      display: 'inline-block',
                                      padding: '0.2rem 0.55rem',
                                      borderRadius: '9999px',
                                      fontSize: '0.75rem',
                                      fontWeight: 600,
                                      backgroundColor: 'rgba(239, 68, 68, 0.15)',
                                      color: '#f87171',
                                      border: '1px solid rgba(239, 68, 68, 0.3)',
                                    }}
                                  >
                                    {r.status === 'DROPPED' ? 'DROPPED' : 'LOST'}
                                  </span>
                                )}
                              </td>
                              <td
                                style={{
                                  padding: '0.75rem 1rem',
                                  fontWeight: 600,
                                  color: r.finalScore === 0 ? '#4ade80' : '#fca5a5',
                                }}
                              >
                                {r.finalScore} pts
                              </td>
                              <td style={{ padding: '0.75rem 1rem', color: '#64748b' }}>
                                {dateStr} {timeStr}
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        {/* Modal Footer */}
        <div
          style={{
            padding: '1rem 1.5rem',
            borderTop: '1px solid rgba(255, 255, 255, 0.08)',
            display: 'flex',
            justifyContent: 'flex-end',
            backgroundColor: 'rgba(0, 0, 0, 0.25)',
          }}
        >
          <button
            onClick={onClose}
            style={{
              padding: '0.5rem 1.25rem',
              backgroundColor: 'rgba(212, 175, 55, 0.15)',
              border: '1px solid rgba(212, 175, 55, 0.4)',
              color: '#d4af37',
              borderRadius: '8px',
              fontWeight: 600,
              cursor: 'pointer',
              transition: 'all 0.15s ease',
            }}
          >
            Close History
          </button>
        </div>
      </div>
    </div>
  );
};
