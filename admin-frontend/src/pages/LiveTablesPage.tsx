import React, { useState, useEffect } from 'react';
import { api } from '../api/client';
import { Eye, Radio, RefreshCw, PowerOff, Filter } from 'lucide-react';
import { Link } from 'react-router-dom';

export const LiveTablesPage: React.FC = () => {
  const [tables, setTables] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [variantFilter, setVariantFilter] = useState('ALL');

  const fetchTables = async () => {
    try {
      const res = await api.get(`/tables/live?variant=${variantFilter}`);
      if (res.data?.success) {
        setTables(res.data.tables || []);
      }
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTables();
    const interval = setInterval(fetchTables, 5000);
    return () => clearInterval(interval);
  }, [variantFilter]);

  const handleTerminate = async (tableId: string) => {
    const reason = window.prompt(`Terminate Table ${tableId}? Enter reason:`, 'Administrative dispute intervention');
    if (!reason) return;

    try {
      await api.post(`/tables/${tableId}/terminate`, { reason });
      alert(`Table ${tableId} terminated successfully.`);
      fetchTables();
    } catch (err: any) {
      alert(`Failed to terminate table: ${err.message}`);
    }
  };

  return (
    <div>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '18px' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <span className="live-dot" />
            <h1 style={{ fontSize: '18px', fontWeight: 700, color: '#fff' }}>
              Live Table Spectator Grid
            </h1>
          </div>
          <p style={{ fontSize: '12px', color: 'var(--text-muted)', marginTop: '2px' }}>
            Real-time in-memory TableActors, seated players & game phases
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '4px', background: 'var(--bg-card)', padding: '4px 8px', borderRadius: '6px', border: '1px solid var(--border-subtle)' }}>
            <Filter size={13} color="var(--text-dim)" />
            <select
              value={variantFilter}
              onChange={(e) => setVariantFilter(e.target.value)}
              style={{ background: 'transparent', border: 'none', color: '#fff', fontSize: '12px', outline: 'none' }}
            >
              <option value="ALL" style={{ background: '#121824' }}>All Variants</option>
              <option value="POINTS" style={{ background: '#121824' }}>Points Rummy</option>
              <option value="DEALS" style={{ background: '#121824' }}>Deals Rummy</option>
              <option value="POOL" style={{ background: '#121824' }}>Pool Rummy</option>
              <option value="21" style={{ background: '#121824' }}>21-Card Rummy</option>
            </select>
          </div>

          <button onClick={fetchTables} className="btn" style={{ fontSize: '11.5px', padding: '5px 10px' }}>
            <RefreshCw size={13} />
            <span>Refresh</span>
          </button>
        </div>
      </div>

      {/* Tables Table */}
      <div className="table-panel">
        <div className="table-panel-header">
          <span className="table-panel-title">Active Tables in Memory ({tables.length})</span>
          <span className="badge badge-emerald">RAM ACTORS</span>
        </div>
        <div style={{ overflowX: 'auto' }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>Table ID</th>
                <th>Game ID</th>
                <th>Variant</th>
                <th>Status</th>
                <th>Started</th>
                <th>Seated Players</th>
                <th>Cut Wild Card</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {tables.length === 0 ? (
                <tr>
                  <td colSpan={8} style={{ textAlign: 'center', color: 'var(--text-dim)', padding: '36px' }}>
                    <Radio size={24} style={{ margin: '0 auto 8px', display: 'block', color: 'var(--text-dim)' }} />
                    No active tables currently running in RAM for this filter.
                  </td>
                </tr>
              ) : (
                tables.map((t) => (
                  <tr key={t._id || t.tableId}>
                    <td className="font-mono" style={{ color: '#fff', fontWeight: 600 }}>{t.tableId}</td>
                    <td className="font-mono" style={{ color: 'var(--text-muted)' }}>{t.gameId}</td>
                    <td>
                      <span className="badge badge-emerald">{t.rulesetId}</span>
                    </td>
                    <td>
                      <span className={`badge ${t.status === 'SHOWDOWN' ? 'badge-amber' : 'badge-emerald'}`}>
                        {t.status}
                      </span>
                    </td>
                    <td style={{ color: 'var(--text-dim)' }}>
                      {t.startedAt ? new Date(t.startedAt).toLocaleTimeString() : 'Just now'}
                    </td>
                    <td>
                      <span style={{ color: '#fff', fontWeight: 500 }}>
                        {t.players?.length || 0} players seated
                      </span>
                    </td>
                    <td className="font-mono" style={{ color: 'var(--accent-gold)' }}>
                      {t.cutJoker || 'N/A'}
                    </td>
                    <td style={{ display: 'flex', gap: '6px' }}>
                      <Link
                        to={`/replay?gameId=${t.gameId}`}
                        className="btn"
                        style={{ fontSize: '10.5px', padding: '2px 8px' }}
                      >
                        <Eye size={12} /> Inspect
                      </Link>
                      <button
                        onClick={() => handleTerminate(t.tableId)}
                        className="btn btn-danger"
                        style={{ fontSize: '10.5px', padding: '2px 8px' }}
                        title="Force-close and refund table"
                      >
                        <PowerOff size={12} /> Terminate
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};
