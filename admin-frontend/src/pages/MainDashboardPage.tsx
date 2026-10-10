import React, { useState, useEffect } from 'react';
import { api } from '../api/client';
import {
  TrendingUp,
  DollarSign,
  Gamepad2,
  Percent,
  RefreshCw,
  Clock,
  Award,
  Users,
  Bot,
  TrendingDown,
  Eye,
} from 'lucide-react';
import { Link, useNavigate } from 'react-router-dom';

export const MainDashboardPage: React.FC = () => {
  const navigate = useNavigate();
  const [data, setData] = useState<any>(null);
  const [loading, setLoading] = useState(true);

  const fetchOverview = async () => {
    try {
      const res = await api.get('/overview/metrics');
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
    fetchOverview();
    const interval = setInterval(fetchOverview, 6000);
    return () => clearInterval(interval);
  }, []);

  if (loading && !data) {
    return <div style={{ color: 'var(--text-muted)', padding: '20px' }}>Loading real-time executive metrics...</div>;
  }

  const kpis = data?.kpis || {};
  const telemetry = data?.telemetry || {};
  const variants = data?.variantDistribution || {};
  const recentGames = data?.recentGames || [];

  const totalProfit = kpis.totalProfit ?? 0;
  const realVsRealProfit = kpis.realVsRealProfit ?? 0;
  const realVsBotProfit = kpis.realVsBotProfit ?? 0;

  return (
    <div>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '18px' }}>
        <div>
          <h1 style={{ fontSize: '18px', fontWeight: 700, color: '#fff', letterSpacing: '-0.3px' }}>
            Executive Operations Overview
          </h1>
          <p style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
            Real-time global liquidity, profit analytics, P2P rake & cluster status
          </p>
        </div>

        <button onClick={fetchOverview} className="btn" style={{ fontSize: '11.5px', padding: '5px 10px' }}>
          <RefreshCw size={13} />
          <span>Refresh</span>
        </button>
      </div>

      {/* PLATFORM PROFIT / LOSS EXECUTIVE SUMMARY */}
      <div style={{ marginBottom: '10px' }}>
        <h2 style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.4px' }}>
          Executive Profit & Loss Overview (प्लॅटफॉर्म निव्वळ नफा/तोटा)
        </h2>
      </div>

      <div className="grid-kpis" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', marginBottom: '20px' }}>
        <div className="kpi-card" style={{ borderTop: `3px solid ${totalProfit >= 0 ? 'var(--accent-gold)' : 'var(--accent-rose)'}` }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Total Platform Profit (एकूण नफा)</span>
            <DollarSign size={16} color="var(--accent-gold)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: totalProfit >= 0 ? 'var(--accent-gold)' : 'var(--accent-rose)' }}>
            {totalProfit >= 0 ? '+' : ''}₹{totalProfit.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between' }}>
            <span>Real vs Real + Real vs Bot Net</span>
            <strong className="font-mono" style={{ color: '#fff' }}>{kpis.totalGamesAllTime} सामने</strong>
          </div>
        </div>

        <div className="kpi-card" style={{ borderTop: '3px solid var(--accent-emerald)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Real vs Real Profit (P2P रेक नफा)</span>
            <Users size={16} color="var(--accent-emerald)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: 'var(--accent-emerald)' }}>
            +₹{realVsRealProfit.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between' }}>
            <span>Pure P2P Commission (15%)</span>
            <span className="badge badge-emerald" style={{ fontSize: '10px' }}>{kpis.realVsRealMatchesCount ?? 0} सामने</span>
          </div>
        </div>

        <div className="kpi-card" style={{ borderTop: '3px solid var(--accent-cyan)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Real vs Bot Profit (बॉट नफा/तोटा)</span>
            <Bot size={16} color="var(--accent-cyan)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: realVsBotProfit >= 0 ? 'var(--accent-cyan)' : 'var(--accent-rose)' }}>
            {realVsBotProfit >= 0 ? '+' : ''}₹{realVsBotProfit.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between' }}>
            <span>Bot Wins minus Bot Losses</span>
            <span className="badge badge-cyan" style={{ fontSize: '10px' }}>{kpis.realVsBotMatchesCount ?? 0} सामने</span>
          </div>
        </div>
      </div>

      {/* Primary Operational KPI Grid */}
      <div className="grid-kpis">
        <div className="kpi-card">
          <div className="kpi-card-header">
            <span className="kpi-card-title">24h Matches Played</span>
            <Gamepad2 size={16} color="var(--accent-emerald)" />
          </div>
          <div className="kpi-card-value">{kpis.totalGames24h}</div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            All-Time: <strong style={{ color: '#fff' }}>{kpis.totalGamesAllTime}</strong> matches
          </div>
        </div>

        <div className="kpi-card">
          <div className="kpi-card-header">
            <span className="kpi-card-title">24h Total Wagers</span>
            <DollarSign size={16} color="var(--accent-cyan)" />
          </div>
          <div className="kpi-card-value font-mono">₹{kpis.totalWagers24h?.toLocaleString() ?? 0}</div>
          <div className="kpi-card-delta" style={{ color: 'var(--accent-emerald)' }}>
            <TrendingUp size={12} />
            <span>Active match entries</span>
          </div>
        </div>

        <div className="kpi-card">
          <div className="kpi-card-header">
            <span className="kpi-card-title">Gross Gaming Revenue</span>
            <TrendingUp size={16} color="var(--accent-gold)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: 'var(--accent-gold)' }}>
            ₹{kpis.grossGamingRevenue?.toLocaleString() ?? 0}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)' }}>
            Payouts: ₹{kpis.totalPayouts24h?.toLocaleString() ?? 0}
          </div>
        </div>

        <div className="kpi-card">
          <div className="kpi-card-header">
            <span className="kpi-card-title">Platform Rake (Net)</span>
            <Percent size={16} color="var(--accent-violet)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: 'var(--accent-violet)' }}>
            ₹{kpis.platformRake?.toLocaleString() ?? 0}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--accent-emerald)' }}>
            <span>Settled automatically</span>
          </div>
        </div>
      </div>

      {/* Variant Distribution Cards */}
      <h2 style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.4px', marginBottom: '10px' }}>
        Variant Traffic & Dedicated Dashboards
      </h2>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))', gap: '12px', marginBottom: '20px' }}>
        <Link to="/variants/points" style={{ textDecoration: 'none' }}>
          <div className="kpi-card" style={{ borderLeft: '3px solid var(--accent-emerald)', cursor: 'pointer' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: '13px', fontWeight: 600, color: '#fff' }}>Points Rummy</span>
              <span className="badge badge-emerald">POINTS_13</span>
            </div>
            <div style={{ fontSize: '20px', fontWeight: 700, marginTop: '8px', color: '#fff' }} className="font-mono">
              {variants.POINTS_13 || 0} <span style={{ fontSize: '12px', color: 'var(--text-dim)', fontWeight: 400 }}>games</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--accent-emerald)', marginTop: '4px' }}>
              View Dedicated Dashboard →
            </div>
          </div>
        </Link>

        <Link to="/variants/deals" style={{ textDecoration: 'none' }}>
          <div className="kpi-card" style={{ borderLeft: '3px solid var(--accent-cyan)', cursor: 'pointer' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: '13px', fontWeight: 600, color: '#fff' }}>Deals Rummy</span>
              <span className="badge badge-cyan">DEALS_2 / 3</span>
            </div>
            <div style={{ fontSize: '20px', fontWeight: 700, marginTop: '8px', color: '#fff' }} className="font-mono">
              {variants.DEALS_RUMMY || 0} <span style={{ fontSize: '12px', color: 'var(--text-dim)', fontWeight: 400 }}>games</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--accent-cyan)', marginTop: '4px' }}>
              View Dedicated Dashboard →
            </div>
          </div>
        </Link>

        <Link to="/variants/pool" style={{ textDecoration: 'none' }}>
          <div className="kpi-card" style={{ borderLeft: '3px solid var(--accent-amber)', cursor: 'pointer' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: '13px', fontWeight: 600, color: '#fff' }}>Pool Rummy</span>
              <span className="badge badge-amber">POOL 101/201</span>
            </div>
            <div style={{ fontSize: '20px', fontWeight: 700, marginTop: '8px', color: '#fff' }} className="font-mono">
              {(variants.POOL_101 || 0) + (variants.POOL_201 || 0)}{' '}
              <span style={{ fontSize: '12px', color: 'var(--text-dim)', fontWeight: 400 }}>games</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--accent-amber)', marginTop: '4px' }}>
              View Dedicated Dashboard →
            </div>
          </div>
        </Link>

        <Link to="/variants/21-card" style={{ textDecoration: 'none' }}>
          <div className="kpi-card" style={{ borderLeft: '3px solid var(--accent-violet)', cursor: 'pointer' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: '13px', fontWeight: 600, color: '#fff' }}>21-Card Rummy</span>
              <span className="badge badge-violet">RUMMY_21</span>
            </div>
            <div style={{ fontSize: '20px', fontWeight: 700, marginTop: '8px', color: '#fff' }} className="font-mono">
              {variants.RUMMY_21 || 0} <span style={{ fontSize: '12px', color: 'var(--text-dim)', fontWeight: 400 }}>games</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--accent-violet)', marginTop: '4px' }}>
              View Dedicated Dashboard →
            </div>
          </div>
        </Link>
      </div>

      {/* Cluster Node Status & Recent Matches Feed */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: '18px' }}>
        {/* Recent Matches Feed */}
        <div className="table-panel">
          <div className="table-panel-header">
            <span className="table-panel-title">Recent Completed Matches & Settlements</span>
            <Link to="/tables" style={{ fontSize: '11.5px', color: 'var(--accent-gold)', textDecoration: 'none' }}>
              Inspect Live Tables →
            </Link>
          </div>
          <div style={{ overflowX: 'auto' }}>
            <table className="data-table">
              <thead>
                <tr>
                  <th>Game ID</th>
                  <th>Type</th>
                  <th>Table Size</th>
                  <th>Variant</th>
                  <th>Status</th>
                  <th>Duration</th>
                  <th>Winner</th>
                  <th>Match Profit</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {recentGames.length === 0 ? (
                  <tr>
                    <td colSpan={9} style={{ textAlign: 'center', color: 'var(--text-dim)', padding: '24px' }}>
                      No recent matches recorded in database yet.
                    </td>
                  </tr>
                ) : (
                  recentGames.map((g: any) => {
                    const isRvr = g.matchType === 'REAL_VS_REAL';
                    const isProfitPos = (g.gameProfit ?? 0) > 0;
                    const isProfitNeg = (g.gameProfit ?? 0) < 0;

                    return (
                      <tr
                        key={g._id || g.gameId}
                        onClick={() => navigate(`/games/${g.gameId}`)}
                        style={{ cursor: 'pointer', transition: 'background-color 0.15s ease' }}
                        title="Click to view full game breakdown, player cards & results"
                      >
                        <td className="font-mono" style={{ color: 'var(--accent-gold)', fontWeight: 700 }}>{g.gameId}</td>
                        <td>
                          {isRvr ? (
                            <span className="badge badge-emerald" style={{ display: 'inline-flex', alignItems: 'center', gap: '3px' }}>
                              <Users size={10} /> Real vs Real
                            </span>
                          ) : (
                            <span className="badge badge-cyan" style={{ display: 'inline-flex', alignItems: 'center', gap: '3px' }}>
                              <Bot size={10} /> Real vs Bot
                            </span>
                          )}
                        </td>
                        <td>
                          <span className={`badge ${g.playerCount === 2 ? 'badge-amber' : 'badge-violet'}`}>
                            {g.playerCount || 2}P
                          </span>
                        </td>
                        <td>
                          <span className="badge badge-emerald">{g.rulesetId}</span>
                        </td>
                        <td>
                          <span className="badge badge-amber">{g.status}</span>
                        </td>
                        <td className="font-mono">
                          <Clock size={12} style={{ display: 'inline', marginRight: '4px', verticalAlign: '-1px' }} />
                          {g.durationSeconds || 0}s
                        </td>
                        <td className="font-mono" style={{ color: g.winnerIsBot ? 'var(--accent-cyan)' : 'var(--accent-gold)' }}>
                          <Award size={12} style={{ display: 'inline', marginRight: '4px', verticalAlign: '-1px' }} />
                          {g.winnerPlayerId || 'N/A'}
                          {g.winnerIsBot && <span className="badge badge-cyan" style={{ marginLeft: '4px', fontSize: '9px' }}>AI</span>}
                        </td>
                        <td className="font-mono" style={{ fontWeight: 600 }}>
                          {isProfitPos ? (
                            <span style={{ color: 'var(--accent-emerald)' }}>+₹{g.gameProfit.toFixed(2)}</span>
                          ) : isProfitNeg ? (
                            <span style={{ color: 'var(--accent-rose)' }}>-₹{Math.abs(g.gameProfit).toFixed(2)}</span>
                          ) : (
                            <span style={{ color: 'var(--text-dim)' }}>₹0.00</span>
                          )}
                        </td>
                        <td onClick={(e) => e.stopPropagation()}>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                            <Link
                              to={`/games/${g.gameId}`}
                              className="btn btn-gold"
                              style={{ fontSize: '10.5px', padding: '2px 8px' }}
                              title="View full match details, player cards & results"
                            >
                              <Eye size={12} /> Details
                            </Link>
                            <Link
                              to={`/replay?gameId=${g.gameId}`}
                              className="btn"
                              style={{ fontSize: '10.5px', padding: '2px 8px' }}
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
    </div>
  );
};
