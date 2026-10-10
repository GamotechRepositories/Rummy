import React, { useState, useEffect } from 'react';
import { api } from '../../api/client';
import { Flame, Clock, UserMinus, RefreshCw } from 'lucide-react';
import { Link } from 'react-router-dom';
import { VariantProfitSection } from '../../components/variant/VariantProfitSection';

export const PoolDashboardPage: React.FC = () => {
  const [data, setData] = useState<any>(null);
  const [selectedPool, setSelectedPool] = useState<'POOL_101' | 'POOL_201'>('POOL_101');
  const [loading, setLoading] = useState(true);
  const [matchType, setMatchType] = useState<'ALL' | 'REAL_VS_REAL' | 'REAL_VS_BOT'>('ALL');
  const [playerCount, setPlayerCount] = useState<'ALL' | '2' | '6'>('ALL');

  const fetchMetrics = async () => {
    try {
      const params = new URLSearchParams();
      if (matchType !== 'ALL') params.append('matchType', matchType);
      if (playerCount !== 'ALL') params.append('playerCount', playerCount);
      const queryStr = params.toString() ? `?${params.toString()}` : '';

      const res = await api.get(`/variants/${selectedPool}/metrics${queryStr}`);
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
  }, [selectedPool, matchType, playerCount]);

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
            <span className="badge badge-amber" style={{ fontSize: '12px' }}>{selectedPool}</span>
            <h1 style={{ fontSize: '18px', fontWeight: 700, color: '#fff' }}>
              Pool Rummy Dedicated Dashboard
            </h1>
          </div>
          <p style={{ fontSize: '12px', color: 'var(--text-muted)', marginTop: '2px' }}>
            Elimination Rummy (101 Pool & 201 Pool) profit analytics, P2P rake & cumulative score curves
          </p>
        </div>

        <div style={{ display: 'flex', gap: '8px' }}>
          <button
            onClick={() => setSelectedPool('POOL_101')}
            className={`btn ${selectedPool === 'POOL_101' ? 'btn-gold' : ''}`}
            style={{ fontSize: '11px', padding: '5px 12px' }}
          >
            101 Pool
          </button>
          <button
            onClick={() => setSelectedPool('POOL_201')}
            className={`btn ${selectedPool === 'POOL_201' ? 'btn-gold' : ''}`}
            style={{ fontSize: '11px', padding: '5px 12px' }}
          >
            201 Pool
          </button>
          <button onClick={fetchMetrics} className="btn" style={{ fontSize: '11.5px', padding: '5px 10px' }}>
            <RefreshCw size={13} />
          </button>
        </div>
      </div>

      {/* PROFIT & LOSS ANALYTICS + MATCHES DRILL-DOWN SECTION */}
      <VariantProfitSection
        financials={financials}
        matches={recent}
        matchType={matchType}
        onMatchTypeChange={setMatchType}
        playerCount={playerCount}
        onPlayerCountChange={setPlayerCount}
        variantThemeColor="var(--accent-amber)"
      />

      {/* Operational KPI Cards */}
      <div style={{ marginBottom: '10px' }}>
        <h2 style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
          Elimination Telemetry & Rejoin Stats
        </h2>
      </div>

      <div className="grid-kpis">
        <div className="kpi-card" style={{ borderTop: '2px solid var(--accent-amber)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">24h Pool Matches</span>
            <Flame size={16} color="var(--accent-amber)" />
          </div>
          <div className="kpi-card-value font-mono">{metrics.total24h ?? 0}</div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            All-Time: {metrics.totalAllTime ?? 0}
          </div>
        </div>

        <div className="kpi-card" style={{ borderTop: '2px solid var(--accent-amber)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Average Deals to End</span>
            <Clock size={16} color="var(--accent-cyan)" />
          </div>
          <div className="kpi-card-value font-mono">4.2 Deals</div>
          <div className="kpi-card-delta" style={{ color: 'var(--accent-emerald)' }}>
            Per completed match
          </div>
        </div>

        <div className="kpi-card" style={{ borderTop: '2px solid var(--accent-amber)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Rejoin Rate</span>
            <RefreshCw size={16} color="var(--accent-gold)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: 'var(--accent-gold)' }}>
            18.4%
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            Re-entered before threshold
          </div>
        </div>

        <div className="kpi-card" style={{ borderTop: '2px solid var(--accent-amber)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Eliminations</span>
            <UserMinus size={16} color="var(--accent-rose)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: 'var(--accent-rose)' }}>
            {outcomes.LOST ?? 0}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            Exceeded score limit
          </div>
        </div>
      </div>

      {/* Rules Tuning Info */}
      <div className="table-panel">
        <div className="table-panel-header">
          <span className="table-panel-title">Active {selectedPool} Rules & Parameters</span>
          <Link to="/config" className="btn" style={{ fontSize: '11px', padding: '3px 8px' }}>
            Modify Rules Config →
          </Link>
        </div>
        <div style={{ padding: '14px', display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '14px', fontSize: '12px' }}>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>Elimination Threshold</div>
            <strong className="font-mono" style={{ color: '#fff', fontSize: '14px' }}>
              {selectedPool === 'POOL_101' ? '101 Points' : '201 Points'}
            </strong>
          </div>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>First Drop Penalty</div>
            <strong className="font-mono" style={{ color: 'var(--accent-amber)', fontSize: '14px' }}>
              {selectedPool === 'POOL_101' ? '20 Points' : '25 Points'}
            </strong>
          </div>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>Middle Drop Penalty</div>
            <strong className="font-mono" style={{ color: 'var(--accent-amber)', fontSize: '14px' }}>
              {selectedPool === 'POOL_101' ? '40 Points' : '50 Points'}
            </strong>
          </div>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>Full Count Penalty</div>
            <strong className="font-mono" style={{ color: 'var(--accent-rose)', fontSize: '14px' }}>80 Points</strong>
          </div>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>Rejoin Max Score Limit</div>
            <strong className="font-mono" style={{ color: 'var(--accent-cyan)', fontSize: '14px' }}>
              {selectedPool === 'POOL_101' ? '79 Points' : '174 Points'}
            </strong>
          </div>
        </div>
      </div>
    </div>
  );
};
