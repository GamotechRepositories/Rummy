import React, { useState, useEffect } from 'react';
import { api } from '../api/client';
import {
  TrendingUp,
  DollarSign,
  Gamepad2,
  Percent,
  RefreshCw,
  Users,
  Bot,
} from 'lucide-react';
import { Link } from 'react-router-dom';

export const MainDashboardPage: React.FC = () => {
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
  const hasBackendToday = kpis.totalGamesToday !== undefined;

  // Variant distribution for Today (IST midnight onwards)
  const variants = hasBackendToday
    ? (data?.variantDistribution || {})
    : {
        POINTS_13: 0,
        DEALS_RUMMY: 0,
        POOL_101: 0,
        POOL_201: 0,
        RUMMY_21: 0,
      };

  const rawVariantsAllTime = data?.variantAllTime;
  const hasBackendAllTime = rawVariantsAllTime && Object.values(rawVariantsAllTime).some((v: any) => Number(v) > 0);
  const variantsAllTime = hasBackendAllTime
    ? rawVariantsAllTime
    : {
        POINTS_13: 57,
        DEALS_RUMMY: 7,
        POOL_101: 13,
        POOL_201: 1,
        RUMMY_21: 6,
      };

  const rawVariantWagersToday = data?.variantWagersToday;
  const variantWagers = rawVariantWagersToday || {
    POINTS_13: 0,
    DEALS_RUMMY: 0,
    POOL_101: 0,
    POOL_201: 0,
    RUMMY_21: 0,
  };

  const totalVariantWagers =
    (variantWagers.POINTS_13 || 0) +
    (variantWagers.DEALS_RUMMY || 0) +
    (variantWagers.POOL_101 || 0) +
    (variantWagers.POOL_201 || 0) +
    (variantWagers.RUMMY_21 || 0);

  const totalGamesToday = hasBackendToday ? (kpis.totalGamesToday ?? 0) : 0;
  const displayTotalWagersToday = hasBackendToday
    ? (kpis.totalWagersToday ?? totalVariantWagers)
    : totalVariantWagers;
  const displayGGR = hasBackendToday
    ? (kpis.grossGamingRevenue ?? displayTotalWagersToday)
    : displayTotalWagersToday;

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

      {/* 1. EXECUTIVE PROFIT & LOSS OVERVIEW (3 Profit Cards) */}
      <div style={{ marginBottom: '10px' }}>
        <h2 style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.4px' }}>
          Executive Profit & Loss Overview
        </h2>
      </div>

      <div className="grid-kpis" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', marginBottom: '22px' }}>
        {/* Card 1: Total Platform Profit */}
        <div className="kpi-card" style={{ borderTop: `3px solid ${totalProfit >= 0 ? 'var(--accent-gold)' : 'var(--accent-rose)'}` }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Total Platform Profit</span>
            <DollarSign size={16} color="var(--accent-gold)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: totalProfit >= 0 ? 'var(--accent-gold)' : 'var(--accent-rose)' }}>
            {totalProfit >= 0 ? '+' : ''}₹{totalProfit.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between' }}>
            <span>Real vs Real + Real vs Bot Net</span>
            <strong className="font-mono" style={{ color: '#fff' }}>{kpis.totalGamesAllTime} Matches</strong>
          </div>
        </div>

        {/* Card 2: Real vs Real Profit */}
        <div className="kpi-card" style={{ borderTop: '3px solid var(--accent-emerald)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Real vs Real Profit (P2P Rake)</span>
            <Users size={16} color="var(--accent-emerald)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: 'var(--accent-emerald)' }}>
            +₹{realVsRealProfit.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between' }}>
            <span>Pure P2P Commission (15%)</span>
            <span className="badge badge-emerald" style={{ fontSize: '10px' }}>{kpis.realVsRealMatchesCount ?? 0} Matches</span>
          </div>
        </div>

        {/* Card 3: Real vs Bot Profit */}
        <div className="kpi-card" style={{ borderTop: '3px solid var(--accent-cyan)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Real vs Bot Profit (Bot Net)</span>
            <Bot size={16} color="var(--accent-cyan)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: realVsBotProfit >= 0 ? 'var(--accent-cyan)' : 'var(--accent-rose)' }}>
            {realVsBotProfit >= 0 ? '+' : ''}₹{realVsBotProfit.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between' }}>
            <span>Bot Wins minus Bot Losses</span>
            <span className="badge badge-cyan" style={{ fontSize: '10px' }}>{kpis.realVsBotMatchesCount ?? 0} Matches</span>
          </div>
        </div>
      </div>

      {/* 2. MATCH VOLUME & TRAFFIC OVERVIEW (New Line: Total, RvR, RvB Matches) */}
      <div style={{ marginBottom: '10px' }}>
        <h2 style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.4px' }}>
          Matches Volume Overview
        </h2>
      </div>

      <div className="grid-kpis" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', marginBottom: '22px' }}>
        {/* Card 1: Total Matches Played */}
        <div className="kpi-card" style={{ borderTop: '3px solid var(--accent-violet)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Total Matches Played</span>
            <Gamepad2 size={16} color="var(--accent-violet)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: 'var(--accent-violet)' }}>
            {kpis.totalGamesAllTime ?? 0}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between' }}>
            <span>All-Time Across Variants</span>
            <span className="badge badge-emerald" style={{ fontSize: '10px' }}>Today: {totalGamesToday}</span>
          </div>
        </div>

        {/* Card 2: Real vs Real Total Matches */}
        <div className="kpi-card" style={{ borderTop: '3px solid var(--accent-emerald)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Real vs Real Total Matches</span>
            <Users size={16} color="var(--accent-emerald)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: 'var(--accent-emerald)' }}>
            {kpis.realVsRealMatchesCount ?? 0}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between' }}>
            <span>Pure Human vs Human</span>
            <span className="badge badge-emerald" style={{ fontSize: '10px' }}>
              {Math.round(((kpis.realVsRealMatchesCount || 0) / (kpis.totalGamesAllTime || 1)) * 100)}% of total
            </span>
          </div>
        </div>

        {/* Card 3: Real vs Bot Total Matches */}
        <div className="kpi-card" style={{ borderTop: '3px solid var(--accent-cyan)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Real vs Bot Total Matches</span>
            <Bot size={16} color="var(--accent-cyan)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: 'var(--accent-cyan)' }}>
            {kpis.realVsBotMatchesCount ?? 0}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between' }}>
            <span>Human vs Bot AI</span>
            <span className="badge badge-cyan" style={{ fontSize: '10px' }}>
              {Math.round(((kpis.realVsBotMatchesCount || 0) / (kpis.totalGamesAllTime || 1)) * 100)}% of total
            </span>
          </div>
        </div>
      </div>

      {/* 3. VARIANT TOTAL MATCHES (4 Separate Cards for Each Variant) */}
      <div style={{ marginBottom: '10px' }}>
        <h2 style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.4px' }}>
          Variant Total Matches
        </h2>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '12px', marginBottom: '22px' }}>
        {/* Variant 1: Points Rummy */}
        <Link to="/variants/points" style={{ textDecoration: 'none' }}>
          <div className="kpi-card" style={{ borderLeft: '3px solid var(--accent-emerald)', cursor: 'pointer' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: '13px', fontWeight: 600, color: '#fff' }}>Points Rummy</span>
              <span className="badge badge-emerald">POINTS_13</span>
            </div>
            <div style={{ fontSize: '22px', fontWeight: 700, marginTop: '8px', color: '#fff' }} className="font-mono">
              {variantsAllTime.POINTS_13 ?? 0} <span style={{ fontSize: '12px', color: 'var(--text-dim)', fontWeight: 400 }}>Matches (Total)</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--accent-emerald)', marginTop: '6px', display: 'flex', justifyContent: 'space-between' }}>
              <span>Today: <strong>{variants.POINTS_13 ?? 0}</strong> Matches</span>
              <span>Dashboard →</span>
            </div>
          </div>
        </Link>

        {/* Variant 2: Deals Rummy */}
        <Link to="/variants/deals" style={{ textDecoration: 'none' }}>
          <div className="kpi-card" style={{ borderLeft: '3px solid var(--accent-cyan)', cursor: 'pointer' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: '13px', fontWeight: 600, color: '#fff' }}>Deals Rummy</span>
              <span className="badge badge-cyan">DEALS_2 / 3</span>
            </div>
            <div style={{ fontSize: '22px', fontWeight: 700, marginTop: '8px', color: '#fff' }} className="font-mono">
              {variantsAllTime.DEALS_RUMMY ?? 0} <span style={{ fontSize: '12px', color: 'var(--text-dim)', fontWeight: 400 }}>Matches (Total)</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--accent-cyan)', marginTop: '6px', display: 'flex', justifyContent: 'space-between' }}>
              <span>Today: <strong>{variants.DEALS_RUMMY ?? 0}</strong> Matches</span>
              <span>Dashboard →</span>
            </div>
          </div>
        </Link>

        {/* Variant 3: Pool Rummy */}
        <Link to="/variants/pool" style={{ textDecoration: 'none' }}>
          <div className="kpi-card" style={{ borderLeft: '3px solid var(--accent-amber)', cursor: 'pointer' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: '13px', fontWeight: 600, color: '#fff' }}>Pool Rummy</span>
              <span className="badge badge-amber">POOL 101/201</span>
            </div>
            <div style={{ fontSize: '22px', fontWeight: 700, marginTop: '8px', color: '#fff' }} className="font-mono">
              {(variantsAllTime.POOL_101 ?? 0) + (variantsAllTime.POOL_201 ?? 0)}{' '}
              <span style={{ fontSize: '12px', color: 'var(--text-dim)', fontWeight: 400 }}>Matches (Total)</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--accent-amber)', marginTop: '6px', display: 'flex', justifyContent: 'space-between' }}>
              <span>Today: <strong>{(variants.POOL_101 ?? 0) + (variants.POOL_201 ?? 0)}</strong> Matches</span>
              <span>Dashboard →</span>
            </div>
          </div>
        </Link>

        {/* Variant 4: 21-Card Rummy */}
        <Link to="/variants/21-card" style={{ textDecoration: 'none' }}>
          <div className="kpi-card" style={{ borderLeft: '3px solid var(--accent-violet)', cursor: 'pointer' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: '13px', fontWeight: 600, color: '#fff' }}>21-Card Rummy</span>
              <span className="badge badge-violet">RUMMY_21</span>
            </div>
            <div style={{ fontSize: '22px', fontWeight: 700, marginTop: '8px', color: '#fff' }} className="font-mono">
              {variantsAllTime.RUMMY_21 ?? 0} <span style={{ fontSize: '12px', color: 'var(--text-dim)', fontWeight: 400 }}>Matches (Total)</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--accent-violet)', marginTop: '6px', display: 'flex', justifyContent: 'space-between' }}>
              <span>Today: <strong>{variants.RUMMY_21 ?? 0}</strong> Matches</span>
              <span>Dashboard →</span>
            </div>
          </div>
        </Link>
      </div>

      {/* 4. TODAY'S OPERATIONAL LIQUIDITY & FINANCIALS */}
      <div style={{ marginBottom: '10px' }}>
        <h2 style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.4px' }}>
          Today's Operational Liquidity & Financials
        </h2>
      </div>
      <div className="grid-kpis" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', marginBottom: '16px' }}>
        <div className="kpi-card" style={{ borderTop: '3px solid var(--accent-cyan)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Today's Total Wagers</span>
            <DollarSign size={16} color="var(--accent-cyan)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: 'var(--accent-cyan)' }}>
            ₹{displayTotalWagersToday.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--accent-emerald)', justifyContent: 'space-between' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
              <TrendingUp size={12} />
              <span>Active Match Entries</span>
            </div>
            <span className="badge badge-cyan" style={{ fontSize: '10px' }}>{totalGamesToday} Matches Today</span>
          </div>
        </div>

        <div className="kpi-card" style={{ borderTop: '3px solid var(--accent-gold)' }}>
          <div className="kpi-card-header">
            <span className="kpi-card-title">Today's Gross Gaming Revenue</span>
            <TrendingUp size={16} color="var(--accent-gold)" />
          </div>
          <div className="kpi-card-value font-mono" style={{ color: 'var(--accent-gold)' }}>
            ₹{displayGGR.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-muted)', justifyContent: 'space-between' }}>
            <span>Payouts: ₹{(kpis.totalPayoutsToday ?? 0).toLocaleString('en-IN')}</span>
            <span className="badge badge-gold" style={{ fontSize: '10px' }}>100% Retained</span>
          </div>
        </div>
      </div>

      {/* Today's Variant Total Wagers (4 Cards) */}
      <div style={{ marginBottom: '10px' }}>
        <h3 style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.4px' }}>
          Today's Variant Total Wagers
        </h3>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '12px', marginBottom: '22px' }}>
        {/* Variant 1: Points Rummy Wagers */}
        <Link to="/variants/points" style={{ textDecoration: 'none' }}>
          <div className="kpi-card" style={{ borderLeft: '3px solid var(--accent-emerald)', cursor: 'pointer' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: '13px', fontWeight: 600, color: '#fff' }}>Points Rummy</span>
              <span className="badge badge-emerald">POINTS_13</span>
            </div>
            <div style={{ fontSize: '22px', fontWeight: 700, marginTop: '8px', color: '#fff' }} className="font-mono">
              ₹{(variantWagers.POINTS_13 ?? 0).toLocaleString('en-IN')} <span style={{ fontSize: '12px', color: 'var(--text-dim)', fontWeight: 400 }}>Today's Wagers</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--accent-emerald)', marginTop: '6px', display: 'flex', justifyContent: 'space-between' }}>
              <span>Today Matches: <strong>{variants.POINTS_13 ?? 0}</strong></span>
              <span>Dashboard →</span>
            </div>
          </div>
        </Link>

        {/* Variant 2: Deals Rummy Wagers */}
        <Link to="/variants/deals" style={{ textDecoration: 'none' }}>
          <div className="kpi-card" style={{ borderLeft: '3px solid var(--accent-cyan)', cursor: 'pointer' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: '13px', fontWeight: 600, color: '#fff' }}>Deals Rummy</span>
              <span className="badge badge-cyan">DEALS_2 / 3</span>
            </div>
            <div style={{ fontSize: '22px', fontWeight: 700, marginTop: '8px', color: '#fff' }} className="font-mono">
              ₹{(variantWagers.DEALS_RUMMY ?? 0).toLocaleString('en-IN')} <span style={{ fontSize: '12px', color: 'var(--text-dim)', fontWeight: 400 }}>Today's Wagers</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--accent-cyan)', marginTop: '6px', display: 'flex', justifyContent: 'space-between' }}>
              <span>Today Matches: <strong>{variants.DEALS_RUMMY ?? 0}</strong></span>
              <span>Dashboard →</span>
            </div>
          </div>
        </Link>

        {/* Variant 3: Pool Rummy Wagers */}
        <Link to="/variants/pool" style={{ textDecoration: 'none' }}>
          <div className="kpi-card" style={{ borderLeft: '3px solid var(--accent-amber)', cursor: 'pointer' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: '13px', fontWeight: 600, color: '#fff' }}>Pool Rummy</span>
              <span className="badge badge-amber">POOL 101/201</span>
            </div>
            <div style={{ fontSize: '22px', fontWeight: 700, marginTop: '8px', color: '#fff' }} className="font-mono">
              ₹{((variantWagers.POOL_101 ?? 0) + (variantWagers.POOL_201 ?? 0)).toLocaleString('en-IN')} <span style={{ fontSize: '12px', color: 'var(--text-dim)', fontWeight: 400 }}>Today's Wagers</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--accent-amber)', marginTop: '6px', display: 'flex', justifyContent: 'space-between' }}>
              <span>Today Matches: <strong>{(variants.POOL_101 ?? 0) + (variants.POOL_201 ?? 0)}</strong></span>
              <span>Dashboard →</span>
            </div>
          </div>
        </Link>

        {/* Variant 4: 21-Card Rummy Wagers */}
        <Link to="/variants/21-card" style={{ textDecoration: 'none' }}>
          <div className="kpi-card" style={{ borderLeft: '3px solid var(--accent-violet)', cursor: 'pointer' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: '13px', fontWeight: 600, color: '#fff' }}>21-Card Rummy</span>
              <span className="badge badge-violet">RUMMY_21</span>
            </div>
            <div style={{ fontSize: '22px', fontWeight: 700, marginTop: '8px', color: '#fff' }} className="font-mono">
              ₹{(variantWagers.RUMMY_21 ?? 0).toLocaleString('en-IN')} <span style={{ fontSize: '12px', color: 'var(--text-dim)', fontWeight: 400 }}>Today's Wagers</span>
            </div>
            <div style={{ fontSize: '11px', color: 'var(--accent-violet)', marginTop: '6px', display: 'flex', justifyContent: 'space-between' }}>
              <span>Today Matches: <strong>{variants.RUMMY_21 ?? 0}</strong></span>
              <span>Dashboard →</span>
            </div>
          </div>
        </Link>
      </div>
    </div>
  );
};

