import React, { useState, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import { Search, History, Play, Pause, ChevronRight, Award, User, Clock, AlertCircle } from 'lucide-react';

export const HandReplayPage: React.FC = () => {
  const [searchParams, setSearchParams] = useSearchParams();
  const [gameIdInput, setGameIdInput] = useState(searchParams.get('gameId') || '');
  const [replayData, setReplayData] = useState<any>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [stepIndex, setStepIndex] = useState(0);
  const [isPlaying, setIsPlaying] = useState(false);

  const loadReplay = async (id: string) => {
    if (!id.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const res = await api.get(`/replay/${id.trim()}`);
      if (res.data?.success) {
        setReplayData(res.data);
        setStepIndex(res.data.events?.length ? res.data.events.length - 1 : 0);
      } else {
        setError('Match replay not found');
      }
    } catch (err: any) {
      setError(err.response?.data?.message || err.message || 'Failed to load match replay');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    const queryId = searchParams.get('gameId');
    if (queryId) {
      setGameIdInput(queryId);
      loadReplay(queryId);
    }
  }, [searchParams]);

  useEffect(() => {
    let timer: any;
    if (isPlaying && replayData?.events?.length) {
      timer = setInterval(() => {
        setStepIndex((prev) => {
          if (prev >= replayData.events.length - 1) {
            setIsPlaying(false);
            return prev;
          }
          return prev + 1;
        });
      }, 1000);
    }
    return () => clearInterval(timer);
  }, [isPlaying, replayData]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    if (gameIdInput.trim()) {
      setSearchParams({ gameId: gameIdInput.trim() });
      loadReplay(gameIdInput.trim());
    }
  };

  const game = replayData?.game;
  const events = replayData?.events || [];
  const results = replayData?.results || [];
  const currentEvent = events[stepIndex];

  return (
    <div>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '18px' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <History size={18} color="var(--accent-gold)" />
            <h1 style={{ fontSize: '18px', fontWeight: 700, color: '#fff' }}>
              Hand Dispute & Chronological Replay Engine
            </h1>
          </div>
          <p style={{ fontSize: '12px', color: 'var(--text-muted)', marginTop: '2px' }}>
            Step-by-step game event auditing, draw/discard timeline & meld verification
          </p>
        </div>

        {/* Search bar */}
        <form onSubmit={handleSearch} style={{ display: 'flex', gap: '6px' }}>
          <div style={{ position: 'relative' }}>
            <Search size={14} color="var(--text-dim)" style={{ position: 'absolute', left: '10px', top: '9px' }} />
            <input
              type="text"
              placeholder="Enter Game ID..."
              value={gameIdInput}
              onChange={(e) => setGameIdInput(e.target.value)}
              className="font-mono"
              style={{
                background: 'var(--bg-card)',
                border: '1px solid var(--border-subtle)',
                borderRadius: '6px',
                padding: '6px 12px 6px 32px',
                color: '#fff',
                fontSize: '12px',
                outline: 'none',
                width: '260px',
              }}
            />
          </div>
          <button type="submit" className="btn btn-gold" style={{ fontSize: '12px' }}>
            Inspect
          </button>
        </form>
      </div>

      {error && (
        <div style={{ background: 'rgba(244, 63, 94, 0.12)', border: '1px solid rgba(244, 63, 94, 0.35)', color: '#fda4af', padding: '12px 14px', borderRadius: '6px', marginBottom: '16px', display: 'flex', alignItems: 'center', gap: '8px' }}>
          <AlertCircle size={16} />
          <span>{error}</span>
        </div>
      )}

      {loading && <div style={{ color: 'var(--text-muted)', padding: '24px' }}>Fetching game event stream from MongoDB...</div>}

      {replayData && (
        <div>
          {/* Match Overview Bar */}
          <div className="table-panel" style={{ padding: '16px', marginBottom: '16px' }}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: '14px', fontSize: '12px' }}>
              <div>
                <span style={{ color: 'var(--text-dim)', textTransform: 'uppercase', fontSize: '10.5px' }}>Game ID</span>
                <div className="font-mono" style={{ color: '#fff', fontWeight: 600 }}>{game?.gameId || gameIdInput}</div>
              </div>
              <div>
                <span style={{ color: 'var(--text-dim)', textTransform: 'uppercase', fontSize: '10.5px' }}>Variant</span>
                <div><span className="badge badge-emerald">{game?.rulesetId || 'POINTS_13'}</span></div>
              </div>
              <div>
                <span style={{ color: 'var(--text-dim)', textTransform: 'uppercase', fontSize: '10.5px' }}>Winner</span>
                <div className="font-mono" style={{ color: 'var(--accent-gold)', fontWeight: 600 }}>
                  <Award size={13} style={{ display: 'inline', marginRight: '4px' }} />
                  {game?.winnerPlayerId || 'N/A'}
                </div>
              </div>
              <div>
                <span style={{ color: 'var(--text-dim)', textTransform: 'uppercase', fontSize: '10.5px' }}>Duration</span>
                <div className="font-mono" style={{ color: '#fff' }}>
                  <Clock size={13} style={{ display: 'inline', marginRight: '4px' }} />
                  {game?.durationSeconds || 0} seconds
                </div>
              </div>
              <div>
                <span style={{ color: 'var(--text-dim)', textTransform: 'uppercase', fontSize: '10.5px' }}>Cut Wild Joker</span>
                <div className="font-mono" style={{ color: 'var(--accent-amber)', fontWeight: 600 }}>
                  {game?.cutJoker || 'None'}
                </div>
              </div>
            </div>
          </div>

          {/* Replay Scrubbing Controls */}
          {events.length > 0 && (
            <div className="table-panel" style={{ padding: '16px', marginBottom: '16px' }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '10px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                  <button
                    onClick={() => setIsPlaying(!isPlaying)}
                    className="btn btn-gold"
                    style={{ padding: '5px 12px', fontSize: '11.5px' }}
                  >
                    {isPlaying ? <Pause size={13} /> : <Play size={13} />}
                    <span>{isPlaying ? 'Pause' : 'Play Timeline'}</span>
                  </button>
                  <span style={{ color: 'var(--text-muted)', fontSize: '12px' }}>
                    Step <strong style={{ color: '#fff' }}>{stepIndex + 1}</strong> of <strong style={{ color: '#fff' }}>{events.length}</strong>
                  </span>
                </div>

                {currentEvent && (
                  <span className="badge badge-cyan" style={{ fontSize: '11px' }}>
                    CURRENT: {currentEvent.eventType}
                  </span>
                )}
              </div>

              {/* Slider Scrub Bar */}
              <input
                type="range"
                min={0}
                max={events.length - 1}
                value={stepIndex}
                onChange={(e) => {
                  setStepIndex(parseInt(e.target.value, 10));
                  setIsPlaying(false);
                }}
                style={{ width: '100%', accentColor: 'var(--accent-gold)', cursor: 'pointer' }}
              />

              {/* Current Step Event Payload Details */}
              {currentEvent && (
                <div style={{ marginTop: '12px', background: 'rgba(0,0,0,0.3)', border: '1px solid var(--border-subtle)', borderRadius: '6px', padding: '10px 14px' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '4px', fontSize: '11.5px' }}>
                    <span style={{ color: 'var(--text-dim)' }}>
                      Seq #{currentEvent.sequence} · Actor: <strong style={{ color: '#fff' }}>{currentEvent.playerId || 'SYSTEM'}</strong>
                    </span>
                    <span className="font-mono" style={{ color: 'var(--text-dim)' }}>
                      {new Date(currentEvent.timestamp).toLocaleTimeString()}
                    </span>
                  </div>
                  {currentEvent.payloadJson && (
                    <pre className="font-mono" style={{ fontSize: '11px', color: '#cbd5e1', overflowX: 'auto', background: 'transparent', padding: '4px 0' }}>
                      {currentEvent.payloadJson}
                    </pre>
                  )}
                </div>
              )}
            </div>
          )}

          {/* Full Event Stream Table */}
          <div className="table-panel">
            <div className="table-panel-header">
              <span className="table-panel-title">Full Event Stream ({events.length} recorded events)</span>
              <span className="badge badge-emerald">IMMUTABLE LOG</span>
            </div>
            <div style={{ overflowX: 'auto', maxHeight: '350px' }}>
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Seq #</th>
                    <th>Event Type</th>
                    <th>Player</th>
                    <th>Timestamp</th>
                    <th>Payload Details</th>
                  </tr>
                </thead>
                <tbody>
                  {events.map((ev: any, idx: number) => (
                    <tr
                      key={ev._id || idx}
                      onClick={() => setStepIndex(idx)}
                      style={{ cursor: 'pointer', background: idx === stepIndex ? 'rgba(251, 191, 36, 0.08)' : undefined }}
                    >
                      <td className="font-mono" style={{ color: idx === stepIndex ? 'var(--accent-gold)' : '#fff', fontWeight: 600 }}>
                        #{ev.sequence}
                      </td>
                      <td>
                        <span className={`badge ${ev.eventType.includes('Showdown') || ev.eventType.includes('Declare') ? 'badge-rose' : 'badge-emerald'}`}>
                          {ev.eventType}
                        </span>
                      </td>
                      <td className="font-mono">{ev.playerId || 'SYSTEM'}</td>
                      <td style={{ color: 'var(--text-dim)' }}>{new Date(ev.timestamp).toLocaleTimeString()}</td>
                      <td className="font-mono" style={{ fontSize: '11px', color: 'var(--text-muted)', maxWidth: '350px', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                        {ev.payloadJson || '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
