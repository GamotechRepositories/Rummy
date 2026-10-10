import React, { useState, useEffect } from 'react';
import { api } from '../../api/client';
import { Coins, Clock, AlertTriangle, ShieldCheck, RefreshCw } from 'lucide-react';
import { Link } from 'react-router-dom';
import { VariantProfitSection } from '../../components/variant/VariantProfitSection';

export const PointsDashboardPage: React.FC = () => {
  const [data, setData] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [matchType, setMatchType] = useState<'ALL' | 'REAL_VS_REAL' | 'REAL_VS_BOT'>('ALL');
  const [playerCount, setPlayerCount] = useState<'ALL' | '2' | '6'>('ALL');

  const fetchMetrics = async () => {
    try {
      const params = new URLSearchParams();
      if (matchType !== 'ALL') params.append('matchType', matchType);
      if (playerCount !== 'ALL') params.append('playerCount', playerCount);
      const queryStr = params.toString() ? `?${params.toString()}` : '';

      const res = await api.get(`/variants/POINTS_13/metrics${queryStr}`);
      if (res.data?.success) {
        setData(res.data);
      }
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchMetrics();
    const interval = setInterval(fetchMetrics, 7000);
    return () => clearInterval(interval);
  }, [matchType, playerCount]);

  const metrics = data?.metrics || {};
  const financials = data?.financials || {
    totalProfit: 0,
    realVsRealProfit: 0,
    realVsBotProfit: 0,
    realVsRealMatchesCount: 0,
    realVsBotMatchesCount: 0,
    twoPlayerMatchesCount: 0,
    sixPlayerMatchesCount: 0,
    totalMatchesCount: 0,
  };
  const outcomes = metrics?.outcomeBreakdown || {};
  const recent = data?.recentMatches || [];

  return (
    <div>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '18px' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <span className="badge badge-emerald" style={{ fontSize: '12px' }}>POINTS_13</span>
            <h1 style={{ fontSize: '18px', fontWeight: 700, color: '#fff' }}>
              Points Rummy Dedicated Dashboard
            </h1>
          </div>
          <p style={{ fontSize: '12px', color: 'var(--text-muted)', marginTop: '2px' }}>
            Fast-paced 13-card Indian Points Rummy profit analytics, P2P rake & bot telemetry
          </p>
        </div>

        <button onClick={fetchMetrics} className="btn" style={{ fontSize: '11.5px', padding: '5px 10px' }}>
          <RefreshCw size={13} />
          <span>Refresh</span>
        </button>
      </div>

      {/* PROFIT & LOSS ANALYTICS + MATCHES DRILL-DOWN SECTION */}
      <VariantProfitSection
        financials={financials}
        matches={recent}
        matchType={matchType}
        onMatchTypeChange={setMatchType}
        playerCount={playerCount}
        onPlayerCountChange={setPlayerCount}
        variantThemeColor="var(--accent-emerald)"
      />

      {/* Operational KPI Cards */}
      <div style={{ marginBottom: '10px' }}>
        <h2 style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
          Game Engine Telemetry & Drop Stats
        </h2>
      </div>

      <div className="grid-kpis">
        <div className="kpi-card" style={{ borderTop: '2px solid var(--accent-emerald)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">24h Points Matches</span>
            <Coins size={16} color="var(--accent-emerald)" />
          </div>
          <div className="kpi-card-value font-mono">{metrics.total24h ?? 0}</div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            All-Time: {metrics.totalAllTime ?? 0}
          </div>
        </div>

        <div className="kpi-card" style={{ borderTop: '2px solid var(--accent-emerald)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Average Hand Duration</span>
            <Clock size={16} color="var(--accent-cyan)" />
          </div>
          <div className="kpi-card-value font-mono">{metrics.avgDurationSeconds ?? 0}s</div>
          <div className="kpi-card-delta" style={{ color: 'var(--accent-emerald)' }}>
            Rapid table turnaround
          </div>
        </div>

        <div className="kpi-card" style={{ borderTop: '2px solid var(--accent-emerald)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">First / Mid Drop Rate</span>
            <AlertTriangle size={16} color="var(--accent-amber)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: 'var(--accent-amber)' }}>
            {outcomes.DROPPED ?? 0}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            Total dropped hands
          </div>
        </div>

        <div className="kpi-card" style={{ borderTop: '2px solid var(--accent-emerald)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Wrong Declarations</span>
            <ShieldCheck size={16} color="var(--accent-rose)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: 'var(--accent-rose)' }}>
            {outcomes.WRONG_DECLARED ?? 0}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            Full 80 pt penalties
          </div>
        </div>
      </div>

      {/* Rules Tuning Info */}
      <div className="table-panel">
        <div className="table-panel-header">
          <span className="table-panel-title">Active Points Rummy Rules & Parameters</span>
          <Link to="/config" className="btn" style={{ fontSize: '11px', padding: '3px 8px' }}>
            Modify Rules Config →
          </Link>
        </div>
        <div style={{ padding: '14px', display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '14px', fontSize: '12px' }}>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>Turn Timer</div>
            <strong className="font-mono" style={{ color: '#fff', fontSize: '14px' }}>15s + 10s Extra</strong>
          </div>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>Declare Timer</div>
            <strong className="font-mono" style={{ color: '#fff', fontSize: '14px' }}>30s Showdown</strong>
          </div>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>First Drop Penalty</div>
            <strong className="font-mono" style={{ color: 'var(--accent-amber)', fontSize: '14px' }}>20 Points</strong>
          </div>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>Middle Drop Penalty</div>
            <strong className="font-mono" style={{ color: 'var(--accent-amber)', fontSize: '14px' }}>40 Points</strong>
          </div>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>Full Count / Wrong Declare</div>
            <strong className="font-mono" style={{ color: 'var(--accent-rose)', fontSize: '14px' }}>80 Points</strong>
          </div>
        </div>
      </div>
    </div>
  );
};
