import React, { useCallback, useEffect, useState } from 'react';
import { useGameStore } from '../store/useGameStore';
import { IndianRupee, PlusCircle, ShieldCheck, History, X, RefreshCw } from 'lucide-react';
import { getApiBaseUrl } from '../utils/apiConfig';
import { authFetch } from '../utils/authClient';
import { useModalScroll } from '../hooks/useModalScroll';

interface WalletTransaction {
  id: string;
  idempotencyKey: string;
  transactionType: string;
  amount: number;
  balanceBefore: number;
  balanceAfter: number;
  currency: string;
  description: string;
  createdAt: string;
  metadata?: { operatorStatus?: string };
}

interface WalletModalProps {
  isOpen: boolean;
  onClose: () => void;
}

const TYPE_LABELS: Record<string, string> = {
  GAME_ENTRY_STAKE: 'Table entry',
  REJOIN_FEE: 'Rejoin fee',
  GAME_WIN: 'Winnings',
  GAME_REFUND: 'Unused stake returned',
  GAME_ENTRY_REFUND: 'Entry returned',
  GAME_ABORT_REFUND: 'Game cancelled, entry returned',
  GAME_CRASH_REFUND: 'Game cancelled, entry returned',
  REJOIN_REFUND: 'Rejoin fee returned',
};

function isCredit(type: string): boolean {
  return type.includes('WIN') || type.includes('REFUND');
}

/**
 * Shows the player's balance with their operator. Money is added and withdrawn in the operator's
 * own cashier, which "Add Cash" opens.
 */
export const WalletModal: React.FC<WalletModalProps> = ({ isOpen, onClose }) => {
  const { playerId } = useGameStore();
  const modalScroll = useModalScroll();
  const [balance, setBalance] = useState<number | null>(null);
  const [cashierUrl, setCashierUrl] = useState<string | null>(null);
  const [transactions, setTransactions] = useState<WalletTransaction[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchWallet = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      const res = await authFetch(`${getApiBaseUrl()}/api/wallet/balance?playerId=${playerId}`);
      if (res.ok) {
        const data = await res.json();
        setBalance(Number(data.balance ?? 0));
        setCashierUrl(typeof data.cashierUrl === 'string' && data.cashierUrl ? data.cashierUrl : null);
      } else {
        setError('Could not load your balance.');
      }
      const txRes = await authFetch(`${getApiBaseUrl()}/api/wallet/transactions?playerId=${playerId}`);
      if (txRes.ok) {
        setTransactions(await txRes.json());
      }
    } catch {
      setError('Could not reach the server.');
    } finally {
      setLoading(false);
    }
  }, [playerId]);

  useEffect(() => {
    if (isOpen) {
      void fetchWallet();
    }
  }, [isOpen, fetchWallet]);

  if (!isOpen) return null;

  const openCashier = () => {
    if (cashierUrl) {
      window.open(cashierUrl, '_blank', 'noopener,noreferrer');
    }
  };

  return (
    <div
      className="wallet-modal-backdrop"
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(3, 7, 18, 0.88)',
        backdropFilter: 'blur(16px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 9999,
        padding: '16px',
      }}
    >
      <div
        className="wallet-modal-card"
        onClick={(e) => e.stopPropagation()}
        onWheel={modalScroll.onWheel}
        onTouchStart={modalScroll.onTouchStart}
        onTouchMove={modalScroll.onTouchMove}
        style={{
          background: 'linear-gradient(170deg, #1e293b 0%, #0f172a 60%, #020617 100%)',
          border: '1.5px solid rgba(212, 175, 55, 0.45)',
          borderRadius: '24px',
          width: '100%',
          maxWidth: '520px',
          maxHeight: 'min(92dvh, calc(100% - 16px), 640px)',
          display: 'flex',
          flexDirection: 'column',
          boxShadow: '0 25px 60px rgba(0,0,0,0.85), 0 0 45px rgba(212, 175, 55, 0.15)',
          color: '#fff',
          overflow: 'hidden',
        }}
      >
        {/* Header */}
        <div
          style={{
            padding: '16px 22px',
            borderBottom: '1px solid rgba(255,255,255,0.08)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            background: 'rgba(212, 175, 55, 0.06)',
            flexShrink: 0,
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <div
              style={{
                width: '40px',
                height: '40px',
                borderRadius: '12px',
                background: 'linear-gradient(135deg, #10b981 0%, #047857 100%)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: '#fff',
                boxShadow: '0 4px 12px rgba(16, 185, 129, 0.35)',
              }}
            >
              <IndianRupee size={22} strokeWidth={2.5} />
            </div>
            <div>
              <h3 style={{ margin: 0, fontSize: '18px', fontWeight: 900, color: '#f8fafc' }}>Wallet</h3>
              <p style={{ margin: 0, fontSize: '11px', color: '#94a3b8' }}>INR (₹) · held by your gaming account</p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            style={{
              background: 'rgba(255,255,255,0.06)',
              border: 'none',
              borderRadius: '50%',
              width: '32px',
              height: '32px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: '#94a3b8',
              cursor: 'pointer',
            }}
          >
            <X size={16} />
          </button>
        </div>

        {/* Scrollable Body */}
        <div
          className="wallet-modal-body"
          onWheel={modalScroll.onWheel}
          onTouchStart={modalScroll.onTouchStart}
          onTouchMove={modalScroll.onTouchMove}
          style={{ padding: '20px 22px', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '16px', minHeight: 0, flex: '1 1 auto' }}
        >
          {/* Balance Card */}
          <div
            style={{
              background: 'radial-gradient(ellipse at 50% 20%, #7f1d1d 0%, #450a0a 70%, #1c0303 100%)',
              border: '1px solid rgba(212, 175, 55, 0.4)',
              borderRadius: '18px',
              padding: '18px 20px',
              textAlign: 'center',
              boxShadow: '0 10px 25px rgba(0,0,0,0.4)',
            }}
          >
            <div style={{ fontSize: '12px', fontWeight: 700, color: '#fca5a5', textTransform: 'uppercase', letterSpacing: '0.8px' }}>
              Available Balance
            </div>
            <div
              style={{
                fontSize: '32px',
                fontWeight: 900,
                color: '#ffffff',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '6px',
                margin: '6px 0 4px 0',
              }}
            >
              <span>₹</span>
              <span>
                {balance === null
                  ? '—'
                  : balance.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
              </span>
            </div>
            <button
              type="button"
              onClick={() => void fetchWallet()}
              disabled={loading}
              style={{
                background: 'transparent',
                border: 'none',
                color: '#fca5a5',
                fontSize: '11px',
                cursor: 'pointer',
                display: 'inline-flex',
                alignItems: 'center',
                gap: '4px',
              }}
            >
              <RefreshCw size={12} /> {loading ? 'Refreshing…' : 'Refresh'}
            </button>
          </div>

          {error && (
            <div
              style={{
                background: 'rgba(239, 68, 68, 0.15)',
                border: '1px solid #ef4444',
                borderRadius: '10px',
                padding: '10px 14px',
                fontSize: '13px',
                fontWeight: 700,
                color: '#fca5a5',
                textAlign: 'center',
              }}
            >
              {error}
            </div>
          )}

          <button
            type="button"
            disabled={!cashierUrl}
            onClick={openCashier}
            style={{
              width: '100%',
              padding: '14px',
              borderRadius: '14px',
              border: 'none',
              background: 'linear-gradient(135deg, #10b981 0%, #059669 100%)',
              color: '#ffffff',
              fontSize: '16px',
              fontWeight: 900,
              cursor: cashierUrl ? 'pointer' : 'not-allowed',
              opacity: cashierUrl ? 1 : 0.6,
              boxShadow: '0 6px 20px rgba(16, 185, 129, 0.35)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '8px',
            }}
          >
            <PlusCircle size={18} /> Add Cash / Withdraw
          </button>
          <div style={{ fontSize: '11px', color: '#94a3b8', textAlign: 'center', marginTop: '-8px' }}>
            {cashierUrl
              ? 'Opens your gaming account cashier. Your balance here updates after you return.'
              : 'Add cash and withdraw from your gaming account app.'}
          </div>

          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '8px',
              background: 'rgba(16, 185, 129, 0.08)',
              border: '1px solid rgba(16, 185, 129, 0.2)',
              borderRadius: '10px',
              padding: '10px 14px',
              fontSize: '12px',
              color: '#34d399',
            }}
          >
            <ShieldCheck size={18} color="#10b981" style={{ flexShrink: 0 }} />
            <span>Every entry and payout is recorded once, with a transaction id shared with your account.</span>
          </div>

          {/* Recent Transactions */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
            <div style={{ fontSize: '13px', fontWeight: 800, color: '#94a3b8', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <History size={15} /> Recent Game Transactions
            </div>
            <div style={{ maxHeight: '200px', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '6px' }}>
              {transactions.length === 0 ? (
                <div style={{ fontSize: '12px', color: '#64748b', textAlign: 'center', padding: '12px' }}>
                  No transactions yet.
                </div>
              ) : (
                transactions.slice(0, 20).map((t) => {
                  const positive = isCredit(t.transactionType);
                  const pending = t.metadata?.operatorStatus === 'QUEUED';
                  return (
                    <div
                      key={t.id || t.idempotencyKey}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        padding: '8px 12px',
                        background: 'rgba(0,0,0,0.3)',
                        borderRadius: '8px',
                        border: '1px solid rgba(255,255,255,0.04)',
                        fontSize: '12px',
                      }}
                    >
                      <div>
                        <div style={{ fontWeight: 700, color: '#f8fafc' }}>
                          {TYPE_LABELS[t.transactionType] ?? t.transactionType}
                          {pending && <span style={{ color: '#fbbf24', fontWeight: 600 }}> · processing</span>}
                        </div>
                        <div style={{ fontSize: '10px', color: '#64748b' }}>
                          {t.createdAt ? new Date(t.createdAt).toLocaleString('en-IN') : t.description}
                        </div>
                      </div>
                      <div style={{ fontWeight: 900, color: positive ? '#34d399' : '#f87171' }}>
                        {positive ? '+' : '-'} ₹ {Math.abs(t.amount).toFixed(2)}
                      </div>
                    </div>
                  );
                })
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
