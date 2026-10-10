import React, { useState, useEffect } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { api } from '../api/client';
import {
  ArrowLeft,
  Users,
  Bot,
  Clock,
  Award,
  DollarSign,
  TrendingUp,
  TrendingDown,
  Layers,
  History,
  ShieldAlert,
  Copy,
  Check,
  Flag,
  Sparkles,
} from 'lucide-react';
import { PlayingCard, CardData } from '../components/cards/PlayingCard';

export const GameDetailPage: React.FC = () => {
  const { gameId } = useParams<{ gameId: string }>();
  const navigate = useNavigate();

  const [data, setData] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!gameId) return;
    const fetchGame = async () => {
      setLoading(true);
      setError(null);
      try {
        const res = await api.get(`/games/${gameId}`);
        if (res.data?.success) {
          setData(res.data);
        } else {
          setError('Match details not found');
        }
      } catch (err: any) {
        setError(err.response?.data?.message || err.message || 'Failed to load match details');
      } finally {
        setLoading(false);
      }
    };
    fetchGame();
  }, [gameId]);

  const copyGameId = () => {
    if (gameId) {
      navigator.clipboard.writeText(gameId);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  if (loading) {
    return (
      <div style={{ color: 'var(--text-muted)', padding: '36px', textAlign: 'center' }}>
        <div style={{ fontSize: '15px', color: '#fff', marginBottom: '8px' }}>Loading complete match details...</div>
        <div style={{ fontSize: '12px', color: 'var(--text-dim)' }}>Reconstructing player hands, settlement ledger & wild jokers</div>
      </div>
    );
  }

  if (error || !data) {
    return (
      <div style={{ padding: '24px' }}>
        <button onClick={() => navigate(-1)} className="btn" style={{ marginBottom: '16px' }}>
          <ArrowLeft size={14} /> Back
        </button>
        <div className="table-panel" style={{ padding: '32px', textAlign: 'center' }}>
          <ShieldAlert size={36} color="var(--accent-rose)" style={{ marginBottom: '12px' }} />
          <h2 style={{ fontSize: '16px', color: '#fff', marginBottom: '8px' }}>Error Loading Match</h2>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>{error || 'Match record not found'}</p>
        </div>
      </div>
    );
  }

  const summary = data.summary || {};
  const players: any[] = data.players || [];
  const transactions: any[] = data.transactions || [];

  const isRvr = summary.matchType === 'REAL_VS_REAL';
  const isProfitPos = (summary.platformProfit ?? 0) > 0;
  const isProfitNeg = (summary.platformProfit ?? 0) < 0;

  return (
    <div style={{ maxWidth: '1400px', margin: '0 auto', paddingBottom: '40px' }}>
      {/* 1. TOP BREADCRUMB & HEADER */}
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: '14px', marginBottom: '20px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <button
            onClick={() => navigate(-1)}
            className="btn"
            style={{ fontSize: '12px', padding: '6px 12px', background: 'var(--bg-card)' }}
          >
            <ArrowLeft size={14} />
            <span>मागे जा (Back)</span>
          </button>

          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <h1 style={{ fontSize: '20px', fontWeight: 800, color: '#fff', letterSpacing: '-0.3px' }} className="font-mono">
                {summary.gameId}
              </h1>
              <button
                onClick={copyGameId}
                className="btn"
                style={{ padding: '3px 8px', fontSize: '11px', background: 'transparent', border: '1px solid var(--border-subtle)' }}
                title="Copy Game ID"
              >
                {copied ? <Check size={12} color="var(--accent-emerald)" /> : <Copy size={12} />}
              </button>

              <span className="badge badge-emerald">{summary.rulesetId}</span>
              {isRvr ? (
                <span className="badge badge-emerald" style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
                  <Users size={11} /> Real vs Real
                </span>
              ) : (
                <span className="badge badge-cyan" style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
                  <Bot size={11} /> Real vs Bot
                </span>
              )}
              <span className="badge badge-gold" style={{ background: 'rgba(251, 191, 36, 0.15)', color: '#fbbf24' }}>
                {summary.status}
              </span>
            </div>
            <p style={{ fontSize: '12px', color: 'var(--text-muted)', marginTop: '2px' }}>
              Match Summary, Full Player Hand Cards (पत्ते) & Profit Settlement Audit
            </p>
          </div>
        </div>

        <Link
          to={`/replay?gameId=${summary.gameId}`}
          className="btn btn-gold"
          style={{ fontSize: '12px', padding: '7px 14px' }}
        >
          <History size={14} />
          <span>Dispute Hand Replay Timeline →</span>
        </Link>
      </div>

      {/* 2. TOP METRICS OVERVIEW (४ कार्ड्स) */}
      <div className="grid-kpis" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', marginBottom: '24px' }}>
        {/* CUT WILD JOKER CARD */}
        <div className="kpi-card" style={{ borderTop: '3px solid var(--accent-gold)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <Sparkles size={14} color="var(--accent-gold)" />
              Cut Wild Joker (कट जोकर)
            </span>
            <span className="badge badge-amber" style={{ fontSize: '9px' }}>WILD JOKER</span>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '14px', marginTop: '4px' }}>
            {summary.cutJoker ? (
              <PlayingCard card={summary.cutJoker} isCutJoker={true} size="md" />
            ) : (
              <span style={{ color: 'var(--text-dim)' }}>None</span>
            )}
            <div>
              <div style={{ fontSize: '12px', color: '#fff', fontWeight: 600 }}>
                {summary.cutJoker?.rankName} of {summary.cutJoker?.suit}
              </div>
              <div style={{ fontSize: '11px', color: 'var(--text-muted)', marginTop: '2px' }}>
                All {summary.cutJoker?.rank}s are Wild Jokers
              </div>
            </div>
          </div>
        </div>

        {/* PLATFORM NET P&L CARD */}
        <div className="kpi-card" style={{ borderTop: `3px solid ${isProfitPos ? 'var(--accent-emerald)' : isProfitNeg ? 'var(--accent-rose)' : 'var(--text-dim)'}` }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <DollarSign size={14} color={isProfitPos ? 'var(--accent-emerald)' : 'var(--accent-rose)'} />
              Platform Profit / P&L (नफा/तोटा)
            </span>
            <span className="badge badge-cyan" style={{ fontSize: '9px' }}>HOUSE IMPACT</span>
          </div>

          <div
            className="kpi-card-value font-mono"
            style={{
              color: isProfitPos ? 'var(--accent-emerald)' : isProfitNeg ? 'var(--accent-rose)' : 'var(--text-dim)',
              display: 'flex',
              alignItems: 'center',
              gap: '4px',
            }}
          >
            {isProfitPos ? (
              <>
                <TrendingUp size={20} />
                +₹{summary.platformProfit.toFixed(2)}
              </>
            ) : isProfitNeg ? (
              <>
                <TrendingDown size={20} />
                -₹{Math.abs(summary.platformProfit).toFixed(2)}
              </>
            ) : (
              '₹0.00'
            )}
          </div>

          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between', marginTop: '6px' }}>
            <span>Platform Commission Rake</span>
            <strong className="font-mono" style={{ color: '#fff' }}>+₹{(summary.platformRake || 0).toFixed(2)}</strong>
          </div>
        </div>

        {/* TABLE CAPACITY & WINNER */}
        <div className="kpi-card" style={{ borderTop: '3px solid var(--accent-cyan)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <Users size={14} color="var(--accent-cyan)" />
              Table Capacity (टेबल क्षमता)
            </span>
            <span className="badge badge-amber">{summary.playerCount} Players</span>
          </div>

          <div style={{ marginTop: '4px' }}>
            <div style={{ fontSize: '11px', color: 'var(--text-dim)', textTransform: 'uppercase' }}>Winner (विजेता)</div>
            <div className="font-mono" style={{ fontSize: '16px', fontWeight: 700, color: 'var(--accent-gold)' }}>
              {summary.winnerPlayerId || 'N/A'}
            </div>
          </div>

          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between', marginTop: '6px' }}>
            <span>Table ID</span>
            <strong className="font-mono" style={{ color: '#fff' }}>{summary.tableId}</strong>
          </div>
        </div>

        {/* TIMING & TURNS */}
        <div className="kpi-card" style={{ borderTop: '3px solid var(--accent-violet)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <Clock size={14} color="var(--accent-violet)" />
              Match Duration & Turns
            </span>
            <span className="badge badge-violet">{summary.totalTurns} Turns</span>
          </div>

          <div className="kpi-card-value font-mono">
            {summary.durationSeconds}s
          </div>

          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between', marginTop: '6px' }}>
            <span>Started</span>
            <span>{summary.startedAt ? new Date(summary.startedAt).toLocaleTimeString() : 'N/A'}</span>
          </div>
        </div>
      </div>

      {/* 3. PLAYERS DETAILED BREAKDOWN & PATTE UI */}
      <div style={{ marginBottom: '14px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div>
          <h2 style={{ fontSize: '16px', fontWeight: 700, color: '#fff' }}>
            Players Hand Breakdown & Cards UI (खेळाडू, पत्ते आणि निकाल)
          </h2>
          <p style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
            Total {players.length} players · Final penalties, won/lost amounts and declared meld groups
          </p>
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '16px', marginBottom: '32px' }}>
        {players.map((p, idx) => {
          const isWinner = Boolean(p.won);
          const isPos = p.netDelta > 0;
          const isNeg = p.netDelta < 0;

          return (
            <div
              key={p.playerId || idx}
              className="table-panel"
              style={{
                border: isWinner ? '2px solid var(--accent-gold)' : '1px solid var(--border-subtle)',
                background: isWinner ? 'rgba(251, 191, 36, 0.03)' : 'var(--bg-card)',
                boxShadow: isWinner ? '0 0 20px rgba(251, 191, 36, 0.12)' : 'none',
                padding: '16px 20px',
              }}
            >
              {/* Player Header Row */}
              <div
                style={{
                  display: 'flex',
                  flexWrap: 'wrap',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: '12px',
                  borderBottom: '1px solid var(--border-subtle)',
                  paddingBottom: '14px',
                  marginBottom: '16px',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                  {/* Seat Badge */}
                  <span
                    style={{
                      background: 'rgba(255, 255, 255, 0.08)',
                      padding: '4px 8px',
                      borderRadius: '6px',
                      fontWeight: 700,
                      fontFamily: 'var(--font-mono)',
                      fontSize: '11px',
                    }}
                  >
                    Seat {p.seatIndex}
                  </span>

                  <div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                      <strong style={{ fontSize: '16px', color: '#fff' }}>
                        {p.displayName}
                      </strong>

                      {p.isBot ? (
                        <span className="badge badge-cyan" style={{ display: 'inline-flex', alignItems: 'center', gap: '3px' }}>
                          <Bot size={11} /> AI Bot
                        </span>
                      ) : (
                        <span className="badge badge-emerald" style={{ display: 'inline-flex', alignItems: 'center', gap: '3px' }}>
                          <Users size={11} /> Real Player
                        </span>
                      )}

                      {isWinner && (
                        <span
                          className="badge"
                          style={{
                            background: 'linear-gradient(90deg, #d97706, #fbbf24)',
                            color: '#000',
                            fontWeight: 800,
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: '4px',
                            boxShadow: '0 0 10px rgba(251, 191, 36, 0.35)',
                          }}
                        >
                          👑 WINNER / DECLARED
                        </span>
                      )}

                      {!isWinner && (
                        <span className={`badge ${p.status === 'DROPPED' ? 'badge-amber' : 'badge-rose'}`}>
                          {p.status}
                        </span>
                      )}
                    </div>

                    <div className="font-mono" style={{ fontSize: '11px', color: 'var(--text-dim)', marginTop: '2px' }}>
                      ID: {p.playerId}
                    </div>
                  </div>
                </div>

                {/* Player Score & P&L Badges */}
                <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
                  {/* Penalty Score */}
                  <div style={{ textAlign: 'right' }}>
                    <div style={{ fontSize: '10.5px', color: 'var(--text-dim)', textTransform: 'uppercase' }}>Penalty Score</div>
                    <div
                      className="font-mono"
                      style={{
                        fontSize: '16px',
                        fontWeight: 700,
                        color: isWinner ? 'var(--accent-emerald)' : 'var(--accent-amber)',
                      }}
                    >
                      {p.finalScore} pts
                    </div>
                  </div>

                  {/* Financial Won / Lost Amount (konta player kiti harala jinkala) */}
                  <div style={{ textAlign: 'right' }}>
                    <div style={{ fontSize: '10.5px', color: 'var(--text-dim)', textTransform: 'uppercase' }}>
                      Net Outcome (नफा / तोटा)
                    </div>
                    <div>
                      {isPos ? (
                        <span
                          className="badge badge-emerald font-mono"
                          style={{ fontSize: '13px', padding: '4px 10px', fontWeight: 800 }}
                        >
                          +₹{p.netDelta.toFixed(2)} JINKALA (WON)
                        </span>
                      ) : isNeg ? (
                        <span
                          className="badge badge-rose font-mono"
                          style={{ fontSize: '13px', padding: '4px 10px', fontWeight: 800 }}
                        >
                          -₹{Math.abs(p.netDelta).toFixed(2)} HARALA (LOST)
                        </span>
                      ) : (
                        <span className="badge font-mono" style={{ fontSize: '13px', padding: '4px 10px' }}>
                          ₹0.00
                        </span>
                      )}
                    </div>
                  </div>
                </div>
              </div>

              {/* Player Cards & Melds UI (पत्ते) */}
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
                  <Layers size={14} color="var(--accent-gold)" />
                  <span style={{ fontSize: '12.5px', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.4px' }}>
                    Declared Melds & Hand Cards (खेळाडूचे पत्ते)
                  </span>
                </div>

                <div style={{ display: 'flex', flexWrap: 'wrap', gap: '20px', alignItems: 'flex-start' }}>
                  {p.meldGroups && p.meldGroups.length > 0 ? (
                    p.meldGroups.map((group: any, gIdx: number) => (
                      <div
                        key={gIdx}
                        style={{
                          background: 'rgba(0, 0, 0, 0.35)',
                          border: '1px solid var(--border-subtle)',
                          borderRadius: '8px',
                          padding: '10px 12px',
                          display: 'flex',
                          flexDirection: 'column',
                          gap: '8px',
                        }}
                      >
                        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '10px' }}>
                          <span style={{ fontSize: '11px', fontWeight: 700, color: '#fff' }}>
                            {group.name}
                          </span>
                          <span
                            className="badge"
                            style={{
                              fontSize: '9px',
                              background: group.type.includes('Pure') ? 'rgba(16, 185, 129, 0.2)' : 'rgba(255, 255, 255, 0.1)',
                              color: group.type.includes('Pure') ? '#34d399' : 'var(--text-muted)',
                              border: group.type.includes('Pure') ? '1px solid rgba(16, 185, 129, 0.4)' : 'none',
                            }}
                          >
                            {group.type}
                          </span>
                        </div>

                        {/* Cards in this meld group */}
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
                          {group.cards && group.cards.map((card: CardData, cIdx: number) => (
                            <PlayingCard key={cIdx} card={card} size="md" />
                          ))}
                        </div>
                      </div>
                    ))
                  ) : (
                    <div style={{ color: 'var(--text-dim)', fontSize: '12px' }}>
                      No meld cards recorded for this player
                    </div>
                  )}

                  {/* Finish Card Slot (If Winner) */}
                  {p.finishCard && (
                    <div
                      style={{
                        background: 'rgba(244, 63, 94, 0.08)',
                        border: '1.5px dashed rgba(244, 63, 94, 0.5)',
                        borderRadius: '8px',
                        padding: '10px 14px',
                        display: 'flex',
                        flexDirection: 'column',
                        alignItems: 'center',
                        gap: '8px',
                      }}
                    >
                      <span
                        className="badge badge-rose"
                        style={{ fontSize: '9.5px', display: 'flex', alignItems: 'center', gap: '3px' }}
                      >
                        <Flag size={10} /> FINISH CARD (फिनिश)
                      </span>
                      <PlayingCard card={p.finishCard} size="md" />
                    </div>
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>

      {/* 4. FINANCIAL LEDGER & SETTLEMENT TRANSACTIONS TABLE */}
      <div className="table-panel">
        <div className="table-panel-header">
          <span className="table-panel-title">
            Financial Ledger Movements for this Match ({transactions.length} Transactions)
          </span>
          <span className="badge badge-emerald">AUDITED LEDGER</span>
        </div>

        <div style={{ overflowX: 'auto' }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>Timestamp</th>
                <th>Idempotency Key</th>
                <th>Player / Account</th>
                <th>Transaction Type</th>
                <th>Amount</th>
                <th>Status</th>
                <th>Description</th>
              </tr>
            </thead>
            <tbody>
              {transactions.length === 0 ? (
                <tr>
                  <td colSpan={7} style={{ textAlign: 'center', color: 'var(--text-dim)', padding: '24px' }}>
                    No wallet transaction ledger records found for this game.
                  </td>
                </tr>
              ) : (
                transactions.map((tx: any, idx: number) => {
                  const isCredit = tx.transactionType.includes('WIN') || tx.transactionType.includes('RAKE') || tx.transactionType.includes('REFUND');
                  return (
                    <tr key={tx.id || idx}>
                      <td style={{ color: 'var(--text-dim)' }}>
                        {tx.createdAt ? new Date(tx.createdAt).toLocaleTimeString() : '—'}
                      </td>
                      <td className="font-mono" style={{ color: 'var(--text-dim)', fontSize: '11px' }}>
                        {tx.idempotencyKey || '—'}
                      </td>
                      <td className="font-mono" style={{ color: tx.playerId === 'PLATFORM_TREASURY' ? 'var(--accent-gold)' : '#fff' }}>
                        {tx.playerId}
                      </td>
                      <td>
                        <span
                          className={`badge ${
                            tx.transactionType === 'PLATFORM_RAKE'
                              ? 'badge-emerald'
                              : tx.transactionType.includes('WIN')
                              ? 'badge-gold'
                              : tx.transactionType.includes('REFUND')
                              ? 'badge-cyan'
                              : 'badge-rose'
                          }`}
                        >
                          {tx.transactionType}
                        </span>
                      </td>
                      <td className="font-mono" style={{ fontWeight: 700, color: isCredit ? 'var(--accent-emerald)' : 'var(--accent-rose)' }}>
                        {isCredit ? '+' : '-'}₹{tx.amount.toFixed(2)}
                      </td>
                      <td>
                        <span className="badge badge-emerald">{tx.status}</span>
                      </td>
                      <td style={{ color: 'var(--text-muted)', fontSize: '11.5px', maxWidth: '300px', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                        {tx.description || '—'}
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};
