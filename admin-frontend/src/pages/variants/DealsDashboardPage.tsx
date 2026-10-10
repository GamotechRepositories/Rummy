import React, { useState, useEffect } from 'react';
import { api } from '../../api/client';
import { Layers, Clock, Zap, RefreshCw } from 'lucide-react';
import { Link } from 'react-router-dom';
import { VariantProfitSection } from '../../components/variant/VariantProfitSection';

export const DealsDashboardPage: React.FC = () => {
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

      const res = await api.get(`/variants/DEALS_2/metrics${queryStr}`);
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
            <span className="badge badge-cyan" style={{ fontSize: '12px' }}>DEALS_RUMMY</span>
            <h1 style={{ fontSize: '18px', fontWeight: 700, color: '#fff' }}>
              Deals Rummy Dedicated Dashboard
            </h1>
          </div>
          <p style={{ fontSize: '12px', color: 'var(--text-muted)', marginTop: '2px' }}>
            Fixed-deal tournaments (Best of 2 / 3 Deals) profit analytics, P2P rake & chip-swing metrics
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
        variantThemeColor="var(--accent-cyan)"
      />

      {/* Operational KPI Cards */}
      <div style={{ marginBottom: '10px' }}>
        <h2 style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
          Tournament Telemetry & Deal Round Stats
        </h2>
      </div>

      <div className="grid-kpis">
        <div className="kpi-card" style={{ borderTop: '2px solid var(--accent-cyan)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">24h Deals Matches</span>
            <Layers size={16} color="var(--accent-cyan)" />
          </div>
          <div className="kpi-card-value font-mono">{metrics.total24h ?? 0}</div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            All-Time: {metrics.totalAllTime ?? 0}
          </div>
        </div>

        <div className="kpi-card" style={{ borderTop: '2px solid var(--accent-cyan)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Average Deal Duration</span>
            <Clock size={16} color="var(--accent-emerald)" />
          </div>
          <div className="kpi-card-value font-mono">{metrics.avgDurationSeconds ?? 0}s</div>
          <div className="kpi-card-delta" style={{ color: 'var(--accent-cyan)' }}>
            Total round duration
          </div>
        </div>

        <div className="kpi-card" style={{ borderTop: '2px solid var(--accent-cyan)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Tie-Breaker Triggered</span>
            <Zap size={16} color="var(--accent-gold)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: 'var(--accent-gold)' }}>
            3.2%
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            Equal chips tie-breaker deal
          </div>
        </div>

        <div className="kpi-card" style={{ borderTop: '2px solid var(--accent-cyan)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Forfeited Deals</span>
            <Layers size={16} color="var(--accent-rose)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: 'var(--accent-rose)' }}>
            {outcomes.DROPPED ?? 0}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            Mid-deal voluntary exits
          </div>
        </div>
      </div>

      {/* Rules Tuning Info */}
      <div className="table-panel">
        <div className="table-panel-header">
          <span className="table-panel-title">Active Deals Rummy Rules & Parameters</span>
          <Link to="/config" className="btn" style={{ fontSize: '11px', padding: '3px 8px' }}>
            Modify Rules Config →
          </Link>
        </div>
        <div style={{ padding: '14px', display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '14px', fontSize: '12px' }}>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>Available Modes</div>
            <strong className="font-mono" style={{ color: '#fff', fontSize: '14px' }}>Best of 2 & Best of 3</strong>
          </div>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>Inter-Deal Break</div>
            <strong className="font-mono" style={{ color: '#fff', fontSize: '14px' }}>5s Countdown</strong>
          </div>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>First Drop Penalty</div>
            <strong className="font-mono" style={{ color: 'var(--accent-amber)', fontSize: '14px' }}>20 Chips</strong>
          </div>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>Middle Drop Penalty</div>
            <strong className="font-mono" style={{ color: 'var(--accent-amber)', fontSize: '14px' }}>40 Chips</strong>
          </div>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>Max Penalty</div>
            <strong className="font-mono" style={{ color: 'var(--accent-rose)', fontSize: '14px' }}>80 Chips</strong>
          </div>
        </div>
      </div>
    </div>
  );
};
