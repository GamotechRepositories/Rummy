import React from 'react';
import { DollarSign, Users, Bot, Filter, RotateCcw, Eye, TrendingUp, TrendingDown, Layers, ChevronRight } from 'lucide-react';
import { Link, useNavigate } from 'react-router-dom';

export interface VariantFinancials {
  totalProfit: number;
  realVsRealProfit: number;
  realVsBotProfit: number;
  realVsRealMatchesCount: number;
  realVsBotMatchesCount: number;
  twoPlayerMatchesCount: number;
  sixPlayerMatchesCount: number;
  totalMatchesCount: number;
}

export interface EnrichedMatch {
  _id?: string;
  gameId: string;
  tableId: string;
  rulesetId: string;
  status: string;
  startedAt: string | Date;
  finishedAt?: string | Date;
  durationSeconds: number;
  winnerPlayerId: string;
  winnerIsBot: boolean;
  matchType: 'REAL_VS_REAL' | 'REAL_VS_BOT';
  playerCount: number;
  gameProfit: number;
  players?: any[];
}

interface VariantProfitSectionProps {
  financials: VariantFinancials;
  matches: EnrichedMatch[];
  matchType: 'ALL' | 'REAL_VS_REAL' | 'REAL_VS_BOT';
  onMatchTypeChange: (type: 'ALL' | 'REAL_VS_REAL' | 'REAL_VS_BOT') => void;
  playerCount: 'ALL' | '2' | '6';
  onPlayerCountChange: (count: 'ALL' | '2' | '6') => void;
  variantThemeColor?: string;
}

export const VariantProfitSection: React.FC<VariantProfitSectionProps> = ({
  financials,
  matches,
  matchType,
  onMatchTypeChange,
  playerCount,
  onPlayerCountChange,
  variantThemeColor = 'var(--accent-emerald)',
}) => {
  const navigate = useNavigate();

  const fin = financials || {
    totalProfit: 0,
    realVsRealProfit: 0,
    realVsBotProfit: 0,
    realVsRealMatchesCount: 0,
    realVsBotMatchesCount: 0,
    twoPlayerMatchesCount: 0,
    sixPlayerMatchesCount: 0,
    totalMatchesCount: 0,
  };

  const isRvrActive = matchType === 'REAL_VS_REAL';
  const isRvbActive = matchType === 'REAL_VS_BOT';
  const isAllActive = matchType === 'ALL' && playerCount === 'ALL';
  const hasActiveFilter = matchType !== 'ALL' || playerCount !== 'ALL';

  return (
    <div style={{ marginBottom: '24px' }}>
      {/* 1. FINANCIAL KPI CARDS (PROFIT / LOSS) */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '10px' }}>
        <h2 style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
          Profit & Revenue Analytics (नफा आणि महसूल विश्लेषण)
        </h2>
        <span style={{ fontSize: '11px', color: 'var(--text-dim)' }}>
          Click card to drill-down into matches
        </span>
      </div>

      <div className="grid-kpis" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', marginBottom: '18px' }}>
        {/* TOTAL PROFIT CARD */}
        <div
          onClick={() => {
            onMatchTypeChange('ALL');
            onPlayerCountChange('ALL');
          }}
          className="kpi-card"
          style={{
            cursor: 'pointer',
            borderTop: `3px solid ${fin.totalProfit >= 0 ? 'var(--accent-gold)' : 'var(--accent-rose)'}`,
            background: isAllActive ? 'rgba(251, 191, 36, 0.06)' : 'var(--bg-card)',
            boxShadow: isAllActive ? '0 0 15px rgba(251, 191, 36, 0.15)' : 'none',
            transition: 'all 0.2s ease',
          }}
          title="Click to view all matches"
        >
          <div className="kpi-card-header">
            <span className="kpi-card-title" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <DollarSign size={14} color="var(--accent-gold)" />
              Total Net Profit (एकूण नफा)
            </span>
            {isAllActive && <span className="badge badge-amber" style={{ fontSize: '9.5px' }}>All Matches</span>}
          </div>

          <div
            className="kpi-card-value font-mono"
            style={{
              color: fin.totalProfit >= 0 ? 'var(--accent-gold)' : 'var(--accent-rose)',
              display: 'flex',
              alignItems: 'baseline',
              gap: '4px',
            }}
          >
            {fin.totalProfit >= 0 ? '+' : ''}₹{fin.totalProfit.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </div>

          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between', marginTop: '6px' }}>
            <span>Combined: P2P Rake + Bot P&L</span>
            <strong className="font-mono" style={{ color: '#fff' }}>{fin.totalMatchesCount} सामने</strong>
          </div>
        </div>

        {/* REAL VS REAL PROFIT CARD */}
        <div
          onClick={() => onMatchTypeChange(isRvrActive ? 'ALL' : 'REAL_VS_REAL')}
          className="kpi-card"
          style={{
            cursor: 'pointer',
            borderTop: '3px solid var(--accent-emerald)',
            background: isRvrActive ? 'rgba(16, 185, 129, 0.08)' : 'var(--bg-card)',
            border: isRvrActive ? '1px solid var(--accent-emerald)' : '1px solid var(--border-subtle)',
            borderTopWidth: '3px',
            boxShadow: isRvrActive ? '0 0 18px rgba(16, 185, 129, 0.25)' : 'none',
            transform: isRvrActive ? 'scale(1.01)' : 'none',
            transition: 'all 0.2s ease',
          }}
          title="Click to filter Real vs Real matches"
        >
          <div className="kpi-card-header">
            <span className="kpi-card-title" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <Users size={14} color="var(--accent-emerald)" />
              Profit: Real vs Real (P2P नफा)
            </span>
            <span className={`badge ${isRvrActive ? 'badge-emerald' : ''}`} style={{ fontSize: '9.5px' }}>
              {isRvrActive ? '✓ FILTER ACTIVE' : 'CLICK TO VIEW'}
            </span>
          </div>

          <div
            className="kpi-card-value font-mono"
            style={{ color: 'var(--accent-emerald)', display: 'flex', alignItems: 'baseline', gap: '4px' }}
          >
            +{fin.realVsRealProfit >= 0 ? '' : ''}₹{fin.realVsRealProfit.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </div>

          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between', marginTop: '6px' }}>
            <span>15% Platform Commission Rake</span>
            <span className="badge badge-emerald" style={{ fontSize: '10px' }}>
              {fin.realVsRealMatchesCount} सामने
            </span>
          </div>
        </div>

        {/* REAL VS BOT PROFIT CARD */}
        <div
          onClick={() => onMatchTypeChange(isRvbActive ? 'ALL' : 'REAL_VS_BOT')}
          className="kpi-card"
          style={{
            cursor: 'pointer',
            borderTop: '3px solid var(--accent-cyan)',
            background: isRvbActive ? 'rgba(6, 182, 212, 0.08)' : 'var(--bg-card)',
            border: isRvbActive ? '1px solid var(--accent-cyan)' : '1px solid var(--border-subtle)',
            borderTopWidth: '3px',
            boxShadow: isRvbActive ? '0 0 18px rgba(6, 182, 212, 0.25)' : 'none',
            transform: isRvbActive ? 'scale(1.01)' : 'none',
            transition: 'all 0.2s ease',
          }}
          title="Click to filter Real vs Bot matches"
        >
          <div className="kpi-card-header">
            <span className="kpi-card-title" style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <Bot size={14} color="var(--accent-cyan)" />
              Profit: Real vs Bot (बॉट नफा/तोटा)
            </span>
            <span className={`badge ${isRvbActive ? 'badge-cyan' : ''}`} style={{ fontSize: '9.5px' }}>
              {isRvbActive ? '✓ FILTER ACTIVE' : 'CLICK TO VIEW'}
            </span>
          </div>

          <div
            className="kpi-card-value font-mono"
            style={{
              color: fin.realVsBotProfit >= 0 ? 'var(--accent-cyan)' : 'var(--accent-rose)',
              display: 'flex',
              alignItems: 'baseline',
              gap: '4px',
            }}
          >
            {fin.realVsBotProfit >= 0 ? '+' : ''}₹{fin.realVsBotProfit.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </div>

          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between', marginTop: '6px' }}>
            <span>Bot Wins minus Bot Losses</span>
            <span className="badge badge-cyan" style={{ fontSize: '10px' }}>
              {fin.realVsBotMatchesCount} सामने
            </span>
          </div>
        </div>
      </div>

      {/* 2. MATCH FILTER & DRILL-DOWN CONTROL BAR */}
      <div className="table-panel">
        <div
          className="table-panel-header"
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: '12px',
            padding: '12px 16px',
            background: 'rgba(0, 0, 0, 0.28)',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <span className="table-panel-title" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <Layers size={15} color={variantThemeColor} />
              Recent Matches & Settlement Feed ({matches.length})
            </span>
            {hasActiveFilter && (
              <span className="badge badge-gold" style={{ fontSize: '10px', background: 'rgba(251, 191, 36, 0.2)', color: '#fbbf24' }}>
                Filtered View
              </span>
            )}
          </div>

          {/* Interactive Filter Buttons */}
          <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '8px' }}>
            {/* Match Type Group */}
            <div style={{ display: 'flex', alignItems: 'center', background: 'var(--bg-input)', borderRadius: '6px', padding: '2px', border: '1px solid var(--border-subtle)' }}>
              <button
                onClick={() => onMatchTypeChange('ALL')}
                className="btn"
                style={{
                  fontSize: '11px',
                  padding: '4px 10px',
                  background: matchType === 'ALL' ? 'var(--bg-hover)' : 'transparent',
                  color: matchType === 'ALL' ? '#fff' : 'var(--text-dim)',
                  border: 'none',
                }}
              >
                All ({fin.totalMatchesCount})
              </button>
              <button
                onClick={() => onMatchTypeChange('REAL_VS_REAL')}
                className="btn"
                style={{
                  fontSize: '11px',
                  padding: '4px 10px',
                  background: matchType === 'REAL_VS_REAL' ? 'rgba(16, 185, 129, 0.22)' : 'transparent',
                  color: matchType === 'REAL_VS_REAL' ? '#34d399' : 'var(--text-dim)',
                  border: matchType === 'REAL_VS_REAL' ? '1px solid rgba(16, 185, 129, 0.4)' : '1px solid transparent',
                  fontWeight: matchType === 'REAL_VS_REAL' ? 600 : 400,
                }}
              >
                <Users size={12} />
                Real vs Real ({fin.realVsRealMatchesCount})
              </button>
              <button
                onClick={() => onMatchTypeChange('REAL_VS_BOT')}
                className="btn"
                style={{
                  fontSize: '11px',
                  padding: '4px 10px',
                  background: matchType === 'REAL_VS_BOT' ? 'rgba(6, 182, 212, 0.22)' : 'transparent',
                  color: matchType === 'REAL_VS_BOT' ? '#38bdf8' : 'var(--text-dim)',
                  border: matchType === 'REAL_VS_BOT' ? '1px solid rgba(6, 182, 212, 0.4)' : '1px solid transparent',
                  fontWeight: matchType === 'REAL_VS_BOT' ? 600 : 400,
                }}
              >
                <Bot size={12} />
                Real vs Bot ({fin.realVsBotMatchesCount})
              </button>
            </div>

            {/* Player Count Group */}
            <div style={{ display: 'flex', alignItems: 'center', background: 'var(--bg-input)', borderRadius: '6px', padding: '2px', border: '1px solid var(--border-subtle)' }}>
              <button
                onClick={() => onPlayerCountChange('ALL')}
                className="btn"
                style={{
                  fontSize: '11px',
                  padding: '4px 10px',
                  background: playerCount === 'ALL' ? 'var(--bg-hover)' : 'transparent',
                  color: playerCount === 'ALL' ? '#fff' : 'var(--text-dim)',
                  border: 'none',
                }}
              >
                All Tables
              </button>
              <button
                onClick={() => onPlayerCountChange('2')}
                className="btn"
                style={{
                  fontSize: '11px',
                  padding: '4px 10px',
                  background: playerCount === '2' ? 'rgba(245, 158, 11, 0.22)' : 'transparent',
                  color: playerCount === '2' ? '#fbbf24' : 'var(--text-dim)',
                  border: playerCount === '2' ? '1px solid rgba(245, 158, 11, 0.4)' : '1px solid transparent',
                  fontWeight: playerCount === '2' ? 600 : 400,
                }}
              >
                २ खेळाडू (2P) ({fin.twoPlayerMatchesCount})
              </button>
              <button
                onClick={() => onPlayerCountChange('6')}
                className="btn"
                style={{
                  fontSize: '11px',
                  padding: '4px 10px',
                  background: playerCount === '6' ? 'rgba(139, 92, 246, 0.22)' : 'transparent',
                  color: playerCount === '6' ? '#a78bfa' : 'var(--text-dim)',
                  border: playerCount === '6' ? '1px solid rgba(139, 92, 246, 0.4)' : '1px solid transparent',
                  fontWeight: playerCount === '6' ? 600 : 400,
                }}
              >
                ६ खेळाडू (6P) ({fin.sixPlayerMatchesCount})
              </button>
            </div>

            {/* Reset Filter Button */}
            {hasActiveFilter && (
              <button
                onClick={() => {
                  onMatchTypeChange('ALL');
                  onPlayerCountChange('ALL');
                }}
                className="btn"
                style={{ fontSize: '11px', padding: '4px 8px', color: 'var(--accent-rose)' }}
                title="Reset all filters"
              >
                <RotateCcw size={12} />
                <span>Reset</span>
              </button>
            )}
          </div>
        </div>

        {/* 3. ENRICHED MATCHES TABLE */}
        <div style={{ overflowX: 'auto' }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>Game ID</th>
                <th>Type (प्रकार)</th>
                <th>Table Size</th>
                <th>Table ID</th>
                <th>Started</th>
                <th>Duration</th>
                <th>Winner (विजेता)</th>
                <th>Platform Profit / P&L (नफा/तोटा)</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {matches.length === 0 ? (
                <tr>
                  <td colSpan={9} style={{ textAlign: 'center', color: 'var(--text-dim)', padding: '36px' }}>
                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '8px' }}>
                      <Filter size={24} color="var(--text-dim)" />
                      <span>
                        No matches found matching the filter (
                        {matchType !== 'ALL' ? matchType : ''} {playerCount !== 'ALL' ? `${playerCount}-Player` : ''}
                        ).
                      </span>
                      {hasActiveFilter && (
                        <button
                          onClick={() => {
                            onMatchTypeChange('ALL');
                            onPlayerCountChange('ALL');
                          }}
                          className="btn btn-gold"
                          style={{ fontSize: '11px', marginTop: '6px' }}
                        >
                          Clear Filters & View All Matches
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ) : (
                matches.map((g) => {
                  const isRvr = g.matchType === 'REAL_VS_REAL';
                  const isProfitPos = g.gameProfit > 0;
                  const isProfitNeg = g.gameProfit < 0;

                  return (
                    <tr
                      key={g._id || g.gameId}
                      onClick={() => navigate(`/games/${g.gameId}`)}
                      style={{
                        cursor: 'pointer',
                        transition: 'background-color 0.15s ease',
                      }}
                      title="Click row to view full match breakdown, player cards & results"
                    >
                      <td className="font-mono" style={{ color: 'var(--accent-gold)', fontWeight: 700 }}>
                        {g.gameId}
                      </td>

                      {/* Match Type Badge */}
                      <td>
                        {isRvr ? (
                          <span className="badge badge-emerald" style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
                            <Users size={11} /> Real vs Real
                          </span>
                        ) : (
                          <span className="badge badge-cyan" style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
                            <Bot size={11} /> Real vs Bot
                          </span>
                        )}
                      </td>

                      {/* Table Size Badge */}
                      <td>
                        <span className={`badge ${g.playerCount === 2 ? 'badge-amber' : 'badge-violet'}`}>
                          {g.playerCount} Players
                        </span>
                      </td>

                      <td className="font-mono" style={{ color: 'var(--text-muted)' }}>
                        {g.tableId}
                      </td>

                      <td style={{ color: 'var(--text-dim)' }}>
                        {new Date(g.startedAt).toLocaleTimeString()}
                      </td>

                      <td className="font-mono" style={{ color: 'var(--text-muted)' }}>
                        {g.durationSeconds || 0}s
                      </td>

                      {/* Winner Column */}
                      <td>
                        <span className="font-mono" style={{ color: g.winnerIsBot ? 'var(--accent-cyan)' : 'var(--accent-gold)' }}>
                          {g.winnerPlayerId || 'N/A'}
                        </span>
                        {g.winnerIsBot && (
                          <span className="badge badge-cyan" style={{ marginLeft: '6px', fontSize: '9px', padding: '1px 4px' }}>
                            AI Bot
                          </span>
                        )}
                      </td>

                      {/* Game Profit / P&L Column */}
                      <td className="font-mono" style={{ fontWeight: 600 }}>
                        {isProfitPos ? (
                          <span style={{ color: 'var(--accent-emerald)', display: 'inline-flex', alignItems: 'center', gap: '3px' }}>
                            <TrendingUp size={12} />
                            +₹{g.gameProfit.toFixed(2)}
                          </span>
                        ) : isProfitNeg ? (
                          <span style={{ color: 'var(--accent-rose)', display: 'inline-flex', alignItems: 'center', gap: '3px' }}>
                            <TrendingDown size={12} />
                            -₹{Math.abs(g.gameProfit).toFixed(2)}
                          </span>
                        ) : (
                          <span style={{ color: 'var(--text-dim)' }}>₹0.00</span>
                        )}
                      </td>

                      {/* Actions: View Details & Replay */}
                      <td onClick={(e) => e.stopPropagation()}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                          <Link
                            to={`/games/${g.gameId}`}
                            className="btn btn-gold"
                            style={{ fontSize: '10.5px', padding: '3px 8px' }}
                            title="View full match details, player cards & results"
                          >
                            <Eye size={12} /> तपशील (Details)
                          </Link>
                          <Link
                            to={`/replay?gameId=${g.gameId}`}
                            className="btn"
                            style={{ fontSize: '10.5px', padding: '3px 8px' }}
                            title="Open chronological hand replay"
                          >
                            Replay
                          </Link>
                        </div>
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
