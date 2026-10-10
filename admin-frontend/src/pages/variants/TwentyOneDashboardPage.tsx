import React, { useState, useEffect } from 'react';
import { api } from '../../api/client';
import { Sparkles, Clock, Crown, RefreshCw } from 'lucide-react';
import { Link } from 'react-router-dom';
import { VariantProfitSection } from '../../components/variant/VariantProfitSection';

export const TwentyOneDashboardPage: React.FC = () => {
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

      const res = await api.get(`/variants/RUMMY_21/metrics${queryStr}`);
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
  const recent = data?.recentMatches || [];

  return (
    <div>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '18px' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <span className="badge badge-violet" style={{ fontSize: '12px' }}>RUMMY_21</span>
            <h1 style={{ fontSize: '18px', fontWeight: 700, color: '#fff' }}>
              21-Card Rummy Dedicated Dashboard
            </h1>
          </div>
          <p style={{ fontSize: '12px', color: 'var(--text-muted)', marginTop: '2px' }}>
            3-deck, 7-joker high-stakes Rummy profit analytics, P2P rake & Marriage/Dublee tracking
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
        variantThemeColor="var(--accent-violet)"
      />

      {/* Operational KPI Cards */}
      <div style={{ marginBottom: '10px' }}>
        <h2 style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
          21-Card Melds & Hand Statistics
        </h2>
      </div>

      <div className="grid-kpis">
        <div className="kpi-card" style={{ borderTop: '2px solid var(--accent-violet)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">24h 21-Card Matches</span>
            <Sparkles size={16} color="var(--accent-violet)" />
          </div>
          <div className="kpi-card-value font-mono">{metrics.total24h ?? 0}</div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            All-Time: {metrics.totalAllTime ?? 0}
          </div>
        </div>

        <div className="kpi-card" style={{ borderTop: '2px solid var(--accent-violet)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Average Hand Duration</span>
            <Clock size={16} color="var(--accent-cyan)" />
          </div>
          <div className="kpi-card-value font-mono">{metrics.avgDurationSeconds ?? 0}s</div>
          <div className="kpi-card-delta" style={{ color: 'var(--accent-violet)' }}>
            Deep melding strategy
          </div>
        </div>

        <div className="kpi-card" style={{ borderTop: '2px solid var(--accent-violet)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Marriage Hand Hits</span>
            <Crown size={16} color="var(--accent-gold)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: 'var(--accent-gold)' }}>
            100 pts
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            Value cards bonus payout
          </div>
        </div>

        <div className="kpi-card" style={{ borderTop: '2px solid var(--accent-violet)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Wrong Declare Penalty</span>
            <Sparkles size={16} color="var(--accent-rose)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: 'var(--accent-rose)' }}>
            120 pts
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            Strict verification
          </div>
        </div>
      </div>

      {/* Rules Tuning Info */}
      <div className="table-panel">
        <div className="table-panel-header">
          <span className="table-panel-title">Active 21-Card Rules & Melds</span>
          <Link to="/config" className="btn" style={{ fontSize: '11px', padding: '3px 8px' }}>
            Modify Rules Config →
          </Link>
        </div>
        <div style={{ padding: '14px', display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '14px', fontSize: '12px' }}>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>Deck Structure</div>
            <strong className="font-mono" style={{ color: '#fff', fontSize: '14px' }}>3 Decks (156 Cards)</strong>
          </div>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>Hand Size</div>
            <strong className="font-mono" style={{ color: '#fff', fontSize: '14px' }}>21 Cards Per Seat</strong>
          </div>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>First Drop Penalty</div>
            <strong className="font-mono" style={{ color: 'var(--accent-amber)', fontSize: '14px' }}>30 Points</strong>
          </div>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>Middle Drop Penalty</div>
            <strong className="font-mono" style={{ color: 'var(--accent-amber)', fontSize: '14px' }}>60 Points</strong>
          </div>
          <div>
            <div style={{ color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase' }}>Wrong Declare Penalty</div>
            <strong className="font-mono" style={{ color: 'var(--accent-rose)', fontSize: '14px' }}>120 Points</strong>
          </div>
        </div>
      </div>
    </div>
  );
};
