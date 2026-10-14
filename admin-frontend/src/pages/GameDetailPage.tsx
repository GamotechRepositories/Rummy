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
  const [selectedDeal, setSelectedDeal] = useState<number | 'summary'>('summary');

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
  const deals: any[] = data.deals || [];

  const isRvr = summary.matchType === 'REAL_VS_REAL';
  const isProfitPos = (summary.platformProfit ?? 0) > 0;
  const isProfitNeg = (summary.platformProfit ?? 0) < 0;

  // Active deal details if a specific deal is selected
  const activeDeal = typeof selectedDeal === 'number' && deals[selectedDeal] ? deals[selectedDeal] : null;
  const activeCutJoker = activeDeal ? activeDeal.cutJoker : summary.cutJoker;
  const activeWinnerId = activeDeal ? activeDeal.winnerPlayerId : summary.winnerPlayerId;
  const activePlayers = activeDeal ? activeDeal.players : players;

  return (
    <div style={{ maxWidth: '1400px', margin: '0 auto', paddingBottom: '24px' }}>
      {/* 1. TOP BREADCRUMB & HEADER */}
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: '10px', marginBottom: '12px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <button
            onClick={() => navigate(-1)}
            className="btn"
            style={{ fontSize: '11.5px', padding: '5px 10px', background: 'var(--bg-card)' }}
          >
            <ArrowLeft size={13} />
            <span>Back</span>
          </button>

          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px', flexWrap: 'wrap' }}>
              <h1 style={{ fontSize: '17px', fontWeight: 800, color: '#fff', letterSpacing: '-0.3px', margin: 0 }} className="font-mono">
                {summary.gameId}
              </h1>
              <button
                onClick={copyGameId}
                className="btn"
                style={{ padding: '2px 6px', fontSize: '10.5px', background: 'transparent', border: '1px solid var(--border-subtle)' }}
                title="Copy Game ID"
              >
                {copied ? <Check size={11} color="var(--accent-emerald)" /> : <Copy size={11} />}
              </button>

              <span className="badge badge-emerald" style={{ fontSize: '10px', padding: '2px 6px' }}>{summary.rulesetId}</span>
              {isRvr ? (
                <span className="badge badge-emerald" style={{ fontSize: '10px', padding: '2px 6px', display: 'inline-flex', alignItems: 'center', gap: '3px' }}>
                  <Users size={10} /> Real vs Real
                </span>
              ) : (
                <span className="badge badge-cyan" style={{ fontSize: '10px', padding: '2px 6px', display: 'inline-flex', alignItems: 'center', gap: '3px' }}>
                  <Bot size={10} /> Real vs Bot
                </span>
              )}
              {deals.length > 1 && (
                <span className="badge badge-violet" style={{ fontSize: '10px', padding: '2px 6px' }}>
                  {deals.length} DEALS
                </span>
              )}
              <span className="badge badge-gold" style={{ fontSize: '10px', padding: '2px 6px', background: 'rgba(251, 191, 36, 0.15)', color: '#fbbf24' }}>
                {summary.status}
              </span>
            </div>
            <p style={{ fontSize: '11px', color: 'var(--text-muted)', margin: '2px 0 0 0' }}>
              Match Summary, Full Player Hand Cards & Profit Settlement Audit
            </p>
          </div>
        </div>

        <Link
          to={`/replay?gameId=${summary.gameId}`}
          className="btn btn-gold"
          style={{ fontSize: '11.5px', padding: '5px 12px' }}
        >
          <History size={13} />
          <span>Dispute Hand Replay Timeline →</span>
        </Link>
      </div>

      {/* 2. TOP METRICS OVERVIEW (COMPACT TILES) */}
      <div className="grid-kpis" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))', gap: '10px', marginBottom: '14px' }}>
        {/* CUT WILD JOKER CARD */}
        <div className="kpi-card" style={{ borderTop: '2.5px solid var(--accent-gold)', padding: '9px 12px' }}>
          <div className="kpi-card-header" style={{ marginBottom: '4px' }}>
            <span className="kpi-card-title" style={{ display: 'flex', alignItems: 'center', gap: '5px', fontSize: '10.5px' }}>
              <Sparkles size={13} color="var(--accent-gold)" />
              {activeDeal ? `Deal ${activeDeal.dealNumber} Cut Joker` : 'Cut Wild Joker'}
            </span>
            <span className="badge badge-amber" style={{ fontSize: '8px', padding: '1px 5px' }}>WILD JOKER</span>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            {activeCutJoker ? (
              <PlayingCard card={activeCutJoker} isCutJoker={true} size="sm" />
            ) : (
              <span style={{ color: 'var(--text-dim)', fontSize: '11px' }}>None</span>
            )}
            <div>
              <div style={{ fontSize: '12px', color: '#fff', fontWeight: 600 }}>
                {activeCutJoker?.rankName} of {activeCutJoker?.suit}
              </div>
              <div style={{ fontSize: '10.5px', color: 'var(--text-muted)', marginTop: '1px' }}>
                All {activeCutJoker?.rank}s are Wild Jokers
              </div>
            </div>
          </div>
        </div>

        {/* PLATFORM NET P&L CARD */}
        <div className="kpi-card" style={{ borderTop: `2.5px solid ${isProfitPos ? 'var(--accent-emerald)' : isProfitNeg ? 'var(--accent-rose)' : 'var(--text-dim)'}`, padding: '9px 12px' }}>
          <div className="kpi-card-header" style={{ marginBottom: '4px' }}>
            <span className="kpi-card-title" style={{ display: 'flex', alignItems: 'center', gap: '5px', fontSize: '10.5px' }}>
              <DollarSign size={13} color={isProfitPos ? 'var(--accent-emerald)' : 'var(--accent-rose)'} />
              Platform Profit / P&L
            </span>
            <span className="badge badge-cyan" style={{ fontSize: '8px', padding: '1px 5px' }}>HOUSE IMPACT</span>
          </div>

          <div
            className="kpi-card-value font-mono"
            style={{
              fontSize: '18px',
              color: isProfitPos ? 'var(--accent-emerald)' : isProfitNeg ? 'var(--accent-rose)' : 'var(--text-dim)',
              display: 'flex',
              alignItems: 'center',
              gap: '4px',
            }}
          >
            {isProfitPos ? (
              <>
                <TrendingUp size={16} />
                +₹{summary.platformProfit.toFixed(2)}
              </>
            ) : isProfitNeg ? (
              <>
                <TrendingDown size={16} />
                -₹{Math.abs(summary.platformProfit).toFixed(2)}
              </>
            ) : (
              '₹0.00'
            )}
          </div>

          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between', marginTop: '3px', fontSize: '10.5px' }}>
            <span>Platform Rake</span>
            <strong className="font-mono" style={{ color: '#fff' }}>+₹{(summary.platformRake || 0).toFixed(2)}</strong>
          </div>
        </div>

        {/* TABLE CAPACITY & WINNER */}
        <div className="kpi-card" style={{ borderTop: '2.5px solid var(--accent-cyan)', padding: '9px 12px' }}>
          <div className="kpi-card-header" style={{ marginBottom: '4px' }}>
            <span className="kpi-card-title" style={{ display: 'flex', alignItems: 'center', gap: '5px', fontSize: '10.5px' }}>
              <Users size={13} color="var(--accent-cyan)" />
              Table Capacity
            </span>
            <span className="badge badge-amber" style={{ fontSize: '8px', padding: '1px 5px' }}>{summary.playerCount} Players</span>
          </div>

          <div>
            <div style={{ fontSize: '9.5px', color: 'var(--text-dim)', textTransform: 'uppercase' }}>
              {activeDeal ? `Deal ${activeDeal.dealNumber} Winner` : 'Tournament Winner'}
            </div>
            <div className="font-mono" style={{ fontSize: '13.5px', fontWeight: 700, color: 'var(--accent-gold)' }}>
              {activeWinnerId || 'N/A'}
            </div>
          </div>

          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between', marginTop: '3px', fontSize: '10.5px' }}>
            <span>Table ID</span>
            <strong className="font-mono" style={{ color: '#fff' }}>{summary.tableId}</strong>
          </div>
        </div>

        {/* TIMING & TURNS */}
        <div className="kpi-card" style={{ borderTop: '2.5px solid var(--accent-violet)', padding: '9px 12px' }}>
          <div className="kpi-card-header" style={{ marginBottom: '4px' }}>
            <span className="kpi-card-title" style={{ display: 'flex', alignItems: 'center', gap: '5px', fontSize: '10.5px' }}>
              <Clock size={13} color="var(--accent-violet)" />
              {activeDeal ? `Deal ${activeDeal.dealNumber} Turns` : 'Match Duration & Turns'}
            </span>
            <span className="badge badge-violet" style={{ fontSize: '8px', padding: '1px 5px' }}>
              {activeDeal ? `${activeDeal.turnsCount} Turns` : `${summary.totalTurns} Turns`}
            </span>
          </div>

          <div className="kpi-card-value font-mono" style={{ fontSize: '18px' }}>
            {activeDeal ? `${activeDeal.turnsCount} turns` : `${summary.durationSeconds}s`}
          </div>

          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between', marginTop: '3px', fontSize: '10.5px' }}>
            <span>Started</span>
            <span>{summary.startedAt ? new Date(summary.startedAt).toLocaleTimeString() : 'N/A'}</span>
          </div>
        </div>
      </div>

      {/* 2.5 MULTI-DEAL ROUND SELECTOR BAR */}
      {deals.length > 1 && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            flexWrap: 'wrap',
            gap: '8px',
            background: 'rgba(0, 0, 0, 0.4)',
            border: '1px solid rgba(255, 255, 255, 0.08)',
            borderRadius: '8px',
            padding: '7px 12px',
            marginBottom: '12px',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Layers size={14} color="var(--accent-gold)" />
            <span style={{ fontSize: '11px', fontWeight: 700, color: '#fff', textTransform: 'uppercase', letterSpacing: '0.4px' }}>
              Match Deals ({deals.length} Rounds Played):
            </span>
            <span style={{ fontSize: '10.5px', color: 'var(--text-muted)' }}>
              Click round to inspect individual deal cards & joker
            </span>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <button
              onClick={() => setSelectedDeal('summary')}
              className={`btn ${selectedDeal === 'summary' ? 'btn-gold' : ''}`}
              style={{
                fontSize: '11px',
                padding: '3px 10px',
                background: selectedDeal === 'summary' ? undefined : 'rgba(255, 255, 255, 0.05)',
                border: selectedDeal === 'summary' ? undefined : '1px solid var(--border-subtle)',
              }}
            >
              🏆 Match Overview & Final P&L
            </button>
            {deals.map((d: any, idx: number) => (
              <button
                key={idx}
                onClick={() => setSelectedDeal(idx)}
                className={`btn ${selectedDeal === idx ? 'btn-cyan' : ''}`}
                style={{
                  fontSize: '11px',
                  padding: '3px 10px',
                  background: selectedDeal === idx ? undefined : 'rgba(255, 255, 255, 0.05)',
                  border: selectedDeal === idx ? undefined : '1px solid var(--border-subtle)',
                }}
              >
                Deal {d.dealNumber}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* 3. PLAYERS DETAILED BREAKDOWN & CARDS UI */}
      <div style={{ marginBottom: '8px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <h2 style={{ fontSize: '14px', fontWeight: 700, color: '#fff', margin: 0 }}>
            {activeDeal ? `Deal ${activeDeal.dealNumber} Hand Breakdown & Cards UI` : 'Players Hand Breakdown & Cards UI'}
          </h2>
          <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
            ({activePlayers.length} players · {activeDeal ? `Deal ${activeDeal.dealNumber} Scores & Cards` : 'Final match penalties & net outcomes'})
          </span>
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', marginBottom: '20px' }}>
        {activePlayers.map((p, idx) => {
          const isWinner = Boolean(p.won);
          const isPos = (p.netDelta ?? 0) > 0;
          const isNeg = (p.netDelta ?? 0) < 0;

          return (
            <div
              key={p.playerId || idx}
              className="table-panel"
              style={{
                border: isWinner ? '1.5px solid var(--accent-gold)' : '1px solid var(--border-subtle)',
                background: isWinner ? 'rgba(251, 191, 36, 0.03)' : 'var(--bg-card)',
                boxShadow: isWinner ? '0 0 16px rgba(251, 191, 36, 0.1)' : 'none',
                padding: '8px 12px',
              }}
            >
              {/* Compact Single-Line Player Header Bar */}
              <div
                style={{
                  display: 'flex',
                  flexWrap: 'wrap',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: '8px',
                  paddingBottom: '6px',
                  marginBottom: '6px',
                  borderBottom: '1px solid rgba(255, 255, 255, 0.06)',
                }}
              >
                {/* Left Info: Seat, Name, Bot/Real, Status, ID */}
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                  <span
                    style={{
                      background: isWinner ? 'var(--accent-gold)' : 'rgba(255, 255, 255, 0.1)',
                      color: isWinner ? '#000' : '#fff',
                      padding: '2px 6px',
                      borderRadius: '4px',
                      fontWeight: 800,
                      fontFamily: 'var(--font-mono)',
                      fontSize: '10px',
                    }}
                  >
                    Seat {p.seatIndex}
                  </span>

                  <strong style={{ fontSize: '13.5px', color: '#fff' }}>
                    {p.displayName}
                  </strong>

                  {p.isBot ? (
                    <span className="badge badge-cyan" style={{ fontSize: '9px', padding: '1px 5px', display: 'inline-flex', alignItems: 'center', gap: '3px' }}>
                      <Bot size={10} /> Bot
                    </span>
                  ) : (
                    <span className="badge badge-emerald" style={{ fontSize: '9px', padding: '1px 5px', display: 'inline-flex', alignItems: 'center', gap: '3px' }}>
                      <Users size={10} /> Real
                    </span>
                  )}

                  {isWinner ? (
                    <span
                      className="badge"
                      style={{
                        background: 'linear-gradient(90deg, #d97706, #fbbf24)',
                        color: '#000',
                        fontWeight: 800,
                        fontSize: '9px',
                        padding: '1px 6px',
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: '3px',
                      }}
                    >
                      👑 WINNER
                    </span>
                  ) : (
                    <span
                      className={`badge ${p.status === 'DROPPED' ? 'badge-amber' : 'badge-rose'}`}
                      style={{ fontSize: '9px', padding: '1px 5px' }}
                    >
                      {p.status}
                    </span>
                  )}

                  <span className="font-mono" style={{ fontSize: '10.5px', color: 'var(--text-dim)' }}>
                    ID: {p.playerId}
                  </span>
                </div>

                {/* Right Info: Penalty Score & Outcome */}
                <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                    <span style={{ fontSize: '10px', color: 'var(--text-dim)', textTransform: 'uppercase' }}>Penalty:</span>
                    <strong
                      className="font-mono"
                      style={{
                        fontSize: '13px',
                        color: isWinner ? 'var(--accent-emerald)' : 'var(--accent-amber)',
                      }}
                    >
                      {p.finalScore} pts
                    </strong>
                  </div>

                  <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                    <span style={{ fontSize: '10px', color: 'var(--text-dim)', textTransform: 'uppercase' }}>
                      {activeDeal ? 'Deal Result:' : 'Net:'}
                    </span>
                    {activeDeal ? (
                      isWinner ? (
                        <span className="badge badge-emerald font-mono" style={{ fontSize: '11px', padding: '2px 8px', fontWeight: 800 }}>
                          DEAL WINNER (0 pts)
                        </span>
                      ) : (
                        <span className="badge badge-rose font-mono" style={{ fontSize: '11px', padding: '2px 8px', fontWeight: 800 }}>
                          -{p.finalScore} pts (LOST)
                        </span>
                      )
                    ) : isPos ? (
                      <span
                        className="badge badge-emerald font-mono"
                        style={{ fontSize: '11.5px', padding: '2px 8px', fontWeight: 800 }}
                      >
                        +₹{p.netDelta.toFixed(2)} (WON)
                      </span>
                    ) : isNeg ? (
                      <span
                        className="badge badge-rose font-mono"
                        style={{ fontSize: '11.5px', padding: '2px 8px', fontWeight: 800 }}
                      >
                        -₹{Math.abs(p.netDelta).toFixed(2)} (LOST)
                      </span>
                    ) : (
                      <span className="badge font-mono" style={{ fontSize: '11.5px', padding: '2px 8px' }}>
                        ₹0.00
                      </span>
                    )}
                  </div>
                </div>
              </div>

              {/* Melds & Hand Cards Row (Compact groups & cards) */}
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', alignItems: 'center' }}>
                {p.meldGroups && p.meldGroups.length > 0 ? (
                  p.meldGroups.map((group: any, gIdx: number) => (
                    <div
                      key={gIdx}
                      style={{
                        background: 'rgba(0, 0, 0, 0.4)',
                        border: '1px solid rgba(255, 255, 255, 0.08)',
                        borderRadius: '6px',
                        padding: '4px 6px',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: '3px',
                      }}
                    >
                      {/* Tiny Group Header */}
                      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '6px' }}>
                        <span style={{ fontSize: '9.5px', fontWeight: 700, color: '#fff' }}>
                          {group.name}
                        </span>
                        <span
                          className="badge"
                          style={{
                            fontSize: '8px',
                            padding: '1px 4px',
                            lineHeight: 1.1,
                            background: group.type.includes('Pure') ? 'rgba(16, 185, 129, 0.2)' : 'rgba(255, 255, 255, 0.08)',
                            color: group.type.includes('Pure') ? '#34d399' : 'var(--text-muted)',
                            border: group.type.includes('Pure') ? '1px solid rgba(16, 185, 129, 0.35)' : 'none',
                          }}
                        >
                          {group.type}
                        </span>
                      </div>

                      {/* Cards in group: using compact size="sm" */}
                      <div style={{ display: 'flex', alignItems: 'center', gap: '3px' }}>
                        {group.cards && group.cards.map((card: CardData, cIdx: number) => (
                          <PlayingCard key={cIdx} card={card} size="sm" />
                        ))}
                      </div>
                    </div>
                  ))
                ) : (
                  <div style={{ color: 'var(--text-dim)', fontSize: '11px', padding: '4px 0' }}>
                    No meld cards recorded for this player
                  </div>
                )}

                {/* Finish Card Slot (If Winner) */}
                {p.finishCard && (
                  <div
                    style={{
                      background: 'rgba(244, 63, 94, 0.06)',
                      border: '1.5px dashed rgba(244, 63, 94, 0.5)',
                      borderRadius: '6px',
                      padding: '4px 6px',
                      display: 'flex',
                      flexDirection: 'column',
                      alignItems: 'center',
                      gap: '3px',
                    }}
                  >
                    <span
                      className="badge badge-rose"
                      style={{ fontSize: '8px', padding: '1px 4px', display: 'flex', alignItems: 'center', gap: '2px' }}
                    >
                      <Flag size={9} /> FINISH
                    </span>
                    <PlayingCard card={p.finishCard} size="sm" />
                  </div>
                )}
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
