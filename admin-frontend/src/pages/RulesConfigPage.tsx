import React, { useState, useEffect } from 'react';
import { api } from '../api/client';
import { Sliders, CheckCircle, RefreshCw, Save } from 'lucide-react';

export const RulesConfigPage: React.FC = () => {
  const [configs, setConfigs] = useState<Record<string, any>>({});
  const [loading, setLoading] = useState(true);
  const [saveStatus, setSaveStatus] = useState<string | null>(null);

  const fetchConfigs = async () => {
    try {
      const res = await api.get('/config/rulesets');
      if (res.data?.success) {
        setConfigs(res.data.configs || {});
      }
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchConfigs();
  }, []);

  const handleUpdate = async (variantKey: string, field: string, value: any) => {
    try {
      setSaveStatus(`Updating ${variantKey}...`);
      await api.put(`/config/rulesets/${variantKey}`, { [field]: Number(value) });
      setConfigs((prev) => ({
        ...prev,
        [variantKey]: { ...prev[variantKey], [field]: Number(value) },
      }));
      setSaveStatus(`Saved ${variantKey} successfully!`);
      setTimeout(() => setSaveStatus(null), 3000);
    } catch (err: any) {
      alert(`Save failed: ${err.message}`);
      setSaveStatus(null);
    }
  };

  return (
    <div>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '18px' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Sliders size={18} color="var(--accent-gold)" />
            <h1 style={{ fontSize: '18px', fontWeight: 700, color: '#fff' }}>
              Dynamic Ruleset & Rake Tuning Engine
            </h1>
          </div>
          <p style={{ fontSize: '12px', color: 'var(--text-muted)', marginTop: '2px' }}>
            Live parameters & commission adjustments applied instantly without restarting Java game engine
          </p>
        </div>

        <button onClick={fetchConfigs} className="btn" style={{ fontSize: '11.5px', padding: '5px 10px' }}>
          <RefreshCw size={13} />
          <span>Reload Configs</span>
        </button>
      </div>

      {saveStatus && (
        <div style={{ background: 'rgba(16, 185, 129, 0.15)', border: '1px solid rgba(16, 185, 129, 0.35)', color: '#34d399', padding: '10px 14px', borderRadius: '6px', marginBottom: '16px', display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px' }}>
          <CheckCircle size={15} />
          <span>{saveStatus}</span>
        </div>
      )}

      {loading ? (
        <div style={{ color: 'var(--text-muted)', padding: '24px' }}>Loading active ruleset parameters...</div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(340px, 1fr))', gap: '16px' }}>
          {Object.entries(configs).map(([variantKey, cfg]) => (
            <div key={variantKey} className="table-panel" style={{ padding: '16px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px', borderBottom: '1px solid var(--border-subtle)', paddingBottom: '8px' }}>
                <span style={{ fontSize: '14px', fontWeight: 600, color: '#fff' }}>{cfg.variantName || variantKey}</span>
                <span className="badge badge-emerald">{variantKey}</span>
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: '12px', fontSize: '12px' }}>
                <div>
                  <label style={{ display: 'block', color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase', marginBottom: '4px' }}>
                    Platform Rake Commission (%)
                  </label>
                  <div style={{ display: 'flex', gap: '8px' }}>
                    <input
                      type="number"
                      step="0.5"
                      defaultValue={cfg.rakePercentage ?? 10.0}
                      onBlur={(e) => handleUpdate(variantKey, 'rakePercentage', e.target.value)}
                      className="font-mono"
                      style={{ background: 'var(--bg-input)', border: '1px solid var(--border-subtle)', borderRadius: '6px', padding: '6px 10px', color: '#fff', fontSize: '13px', width: '120px' }}
                    />
                    <span style={{ alignSelf: 'center', color: 'var(--text-dim)' }}>%</span>
                  </div>
                </div>

                <div>
                  <label style={{ display: 'block', color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase', marginBottom: '4px' }}>
                    Turn Timeout (Seconds)
                  </label>
                  <input
                    type="number"
                    defaultValue={cfg.turnTimeoutSeconds ?? 15}
                    onBlur={(e) => handleUpdate(variantKey, 'turnTimeoutSeconds', e.target.value)}
                    className="font-mono"
                    style={{ background: 'var(--bg-input)', border: '1px solid var(--border-subtle)', borderRadius: '6px', padding: '6px 10px', color: '#fff', fontSize: '13px', width: '120px' }}
                  />
                </div>

                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px' }}>
                  <div>
                    <label style={{ display: 'block', color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase', marginBottom: '4px' }}>
                      First Drop Penalty
                    </label>
                    <input
                      type="number"
                      defaultValue={cfg.firstDropPenalty ?? 20}
                      onBlur={(e) => handleUpdate(variantKey, 'firstDropPenalty', e.target.value)}
                      className="font-mono"
                      style={{ background: 'var(--bg-input)', border: '1px solid var(--border-subtle)', borderRadius: '6px', padding: '6px 10px', color: '#fff', fontSize: '13px', width: '100%' }}
                    />
                  </div>
                  <div>
                    <label style={{ display: 'block', color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase', marginBottom: '4px' }}>
                      Middle Drop Penalty
                    </label>
                    <input
                      type="number"
                      defaultValue={cfg.middleDropPenalty ?? 40}
                      onBlur={(e) => handleUpdate(variantKey, 'middleDropPenalty', e.target.value)}
                      className="font-mono"
                      style={{ background: 'var(--bg-input)', border: '1px solid var(--border-subtle)', borderRadius: '6px', padding: '6px 10px', color: '#fff', fontSize: '13px', width: '100%' }}
                    />
                  </div>
                </div>

                <div>
                  <label style={{ display: 'block', color: 'var(--text-dim)', fontSize: '11px', textTransform: 'uppercase', marginBottom: '4px' }}>
                    Wrong Declare Penalty
                  </label>
                  <input
                    type="number"
                    defaultValue={cfg.wrongDeclarePenalty ?? 80}
                    onBlur={(e) => handleUpdate(variantKey, 'wrongDeclarePenalty', e.target.value)}
                    className="font-mono"
                    style={{ background: 'var(--bg-input)', border: '1px solid var(--border-subtle)', borderRadius: '6px', padding: '6px 10px', color: '#fff', fontSize: '13px', width: '120px' }}
                  />
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
