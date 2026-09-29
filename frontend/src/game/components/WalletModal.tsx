import React, { useEffect, useState } from 'react';
import { useGameStore } from '../store/useGameStore';
import {
  IndianRupee,
  PlusCircle,
  ArrowUpRight,
  ShieldCheck,
  History,
  X,
  CreditCard,
  Building,
  Smartphone,
  Gift,
} from 'lucide-react';
import { getApiBaseUrl } from '../utils/apiConfig';

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
}

interface WalletModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const WalletModal: React.FC<WalletModalProps> = ({ isOpen, onClose }) => {
  const { playerId } = useGameStore();
  const [totalBalance, setTotalBalance] = useState<number>(1000);
  const [depositBalance, setDepositBalance] = useState<number>(600);
  const [winningsBalance, setWinningsBalance] = useState<number>(400);

  const [activeTab, setActiveTab] = useState<'DEPOSIT' | 'WITHDRAW'>('DEPOSIT');
  const [depositAmount, setDepositAmount] = useState<number>(500);
  const [withdrawAmount, setWithdrawAmount] = useState<number>(200);
  const [upiId, setUpiId] = useState<string>('player@upi');
  const [payMethod, setPayMethod] = useState<'UPI' | 'NETBANKING' | 'CARD'>('UPI');

  const [transactions, setTransactions] = useState<WalletTransaction[]>([]);
  const [loading, setLoading] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);

  const fetchWallet = async () => {
    try {
      setLoading(true);
      const res = await fetch(`${getApiBaseUrl()}/api/wallet/balance?playerId=${playerId}`);
      if (res.ok) {
        const data = await res.json();
        const total = data.totalBalance ?? data.freePlayBalance ?? 1000;
        setTotalBalance(total);
        setDepositBalance(data.depositBalance ?? total * 0.6);
        setWinningsBalance(data.winningsBalance ?? total * 0.4);
      }

      const txRes = await fetch(`${getApiBaseUrl()}/api/wallet/transactions?playerId=${playerId}`);
      if (txRes.ok) {
        const txData = await txRes.json();
        setTransactions(txData);
      }
    } catch {
      // Fallback
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (isOpen) {
      fetchWallet();
    }
  }, [isOpen, playerId]);

  const handleDeposit = async () => {
    if (depositAmount <= 0) return;
    try {
      setLoading(true);
      const res = await fetch(
        `${getApiBaseUrl()}/api/wallet/deposit?playerId=${playerId}&amount=${depositAmount}&method=${payMethod}`,
        { method: 'POST' }
      );
      const data = await res.json();
      if (res.ok && data.success) {
        setFeedback(`✅ ₹ ${depositAmount} successfully deposited via ${payMethod}!`);
        await fetchWallet();
      } else {
        setFeedback(data.message || 'Deposit failed');
      }
      setTimeout(() => setFeedback(null), 4000);
    } catch {
      setFeedback('Deposit network error');
    } finally {
      setLoading(false);
    }
  };

  const handleWithdraw = async () => {
    if (withdrawAmount <= 0) return;
    if (withdrawAmount > winningsBalance) {
      setFeedback('⚠️ Withdrawal amount cannot exceed your Winnings Balance.');
      setTimeout(() => setFeedback(null), 4000);
      return;
    }

    try {
      setLoading(true);
      const res = await fetch(
        `${getApiBaseUrl()}/api/wallet/withdraw?playerId=${playerId}&amount=${withdrawAmount}&method=UPI&destination=${encodeURIComponent(
          upiId
        )}`,
        { method: 'POST' }
      );
      const data = await res.json();
      if (res.ok && data.success) {
        setFeedback(`✅ ₹ ${withdrawAmount} successfully withdrawn to ${upiId}!`);
        await fetchWallet();
      } else {
        setFeedback(data.message || 'Withdrawal failed');
      }
      setTimeout(() => setFeedback(null), 4000);
    } catch {
      setFeedback('Withdrawal network error');
    } finally {
      setLoading(false);
    }
  };

  const handleClaimDaily = async () => {
    try {
      setLoading(true);
      const res = await fetch(`${getApiBaseUrl()}/api/wallet/claim-daily?playerId=${playerId}`, {
        method: 'POST',
      });
      const data = await res.json();
      if (res.ok && data.success) {
        setFeedback('🎁 Claimed ₹ 500 Daily Login Cash!');
        await fetchWallet();
      } else {
        setFeedback(data.message || 'Daily bonus already claimed for today!');
      }
      setTimeout(() => setFeedback(null), 4000);
    } catch {
      setFeedback('Bonus claim error');
    } finally {
      setLoading(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div
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
        style={{
          background: 'linear-gradient(170deg, #1e293b 0%, #0f172a 60%, #020617 100%)',
          border: '1.5px solid rgba(212, 175, 55, 0.45)',
          borderRadius: '24px',
          width: '100%',
          maxWidth: '520px',
          maxHeight: '90vh',
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
              <h3 style={{ margin: 0, fontSize: '18px', fontWeight: 900, color: '#f8fafc' }}>
                Real Cash Wallet
              </h3>
              <p style={{ margin: 0, fontSize: '11px', color: '#94a3b8' }}>
                Account ID: {playerId} · INR (₹)
              </p>
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
        <div style={{ padding: '20px 22px', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: '16px' }}>
          {/* Total Balance Card */}
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
              Total Cash Balance
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
                margin: '6px 0 12px 0',
              }}
            >
              <span>₹</span>
              <span>{totalBalance.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</span>
            </div>

            {/* Split: Deposit vs Winnings */}
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: '1fr 1fr',
                gap: '10px',
                paddingTop: '12px',
                borderTop: '1px solid rgba(255,255,255,0.1)',
                fontSize: '12px',
              }}
            >
              <div style={{ background: 'rgba(0,0,0,0.3)', padding: '8px 12px', borderRadius: '10px' }}>
                <div style={{ color: '#cbd5e1', fontSize: '11px' }}>Deposit Cash</div>
                <div style={{ fontWeight: 800, color: '#f8fafc', fontSize: '15px', marginTop: '2px' }}>
                  ₹ {depositBalance.toFixed(2)}
                </div>
              </div>
              <div style={{ background: 'rgba(0,0,0,0.3)', padding: '8px 12px', borderRadius: '10px' }}>
                <div style={{ color: '#cbd5e1', fontSize: '11px' }}>Winnings (Withdrawable)</div>
                <div style={{ fontWeight: 800, color: '#34d399', fontSize: '15px', marginTop: '2px' }}>
                  ₹ {winningsBalance.toFixed(2)}
                </div>
              </div>
            </div>
          </div>

          {/* Action Tabs: Add Cash vs Withdraw */}
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: '1fr 1fr',
              background: 'rgba(0,0,0,0.4)',
              border: '1px solid rgba(255,255,255,0.08)',
              borderRadius: '14px',
              padding: '4px',
              gap: '4px',
            }}
          >
            <button
              type="button"
              onClick={() => setActiveTab('DEPOSIT')}
              style={{
                padding: '10px',
                borderRadius: '10px',
                border: 'none',
                background:
                  activeTab === 'DEPOSIT'
                    ? 'linear-gradient(135deg, #10b981 0%, #059669 100%)'
                    : 'transparent',
                color: activeTab === 'DEPOSIT' ? '#ffffff' : '#94a3b8',
                fontWeight: 800,
                fontSize: '14px',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '6px',
                transition: 'all 0.2s',
              }}
            >
              <PlusCircle size={16} /> + Add Cash
            </button>
            <button
              type="button"
              onClick={() => setActiveTab('WITHDRAW')}
              style={{
                padding: '10px',
                borderRadius: '10px',
                border: 'none',
                background:
                  activeTab === 'WITHDRAW'
                    ? 'linear-gradient(135deg, #3b82f6 0%, #1d4ed8 100%)'
                    : 'transparent',
                color: activeTab === 'WITHDRAW' ? '#ffffff' : '#94a3b8',
                fontWeight: 800,
                fontSize: '14px',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '6px',
                transition: 'all 0.2s',
              }}
            >
              <ArrowUpRight size={16} /> ↗ Withdraw
            </button>
          </div>

          {/* Feedback alert */}
          {feedback && (
            <div
              style={{
                background: feedback.includes('✅') || feedback.includes('🎁') ? 'rgba(16, 185, 129, 0.15)' : 'rgba(239, 68, 68, 0.15)',
                border: `1px solid ${feedback.includes('✅') || feedback.includes('🎁') ? '#10b981' : '#ef4444'}`,
                borderRadius: '10px',
                padding: '10px 14px',
                fontSize: '13px',
                fontWeight: 700,
                color: feedback.includes('✅') || feedback.includes('🎁') ? '#34d399' : '#fca5a5',
                textAlign: 'center',
              }}
            >
              {feedback}
            </div>
          )}

          {/* DEPOSIT FORM */}
          {activeTab === 'DEPOSIT' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <div style={{ fontSize: '13px', fontWeight: 700, color: '#e2e8f0' }}>Select Deposit Amount:</div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '8px' }}>
                {[100, 200, 500, 1000].map((amt) => {
                  const isSelected = depositAmount === amt;
                  return (
                    <button
                      key={amt}
                      type="button"
                      onClick={() => setDepositAmount(amt)}
                      style={{
                        padding: '10px 0',
                        borderRadius: '10px',
                        border: isSelected ? '2px solid #10b981' : '1px solid rgba(255,255,255,0.12)',
                        background: isSelected ? 'rgba(16, 185, 129, 0.15)' : 'rgba(15, 23, 42, 0.6)',
                        color: isSelected ? '#34d399' : '#f8fafc',
                        fontSize: '14px',
                        fontWeight: 800,
                        cursor: 'pointer',
                      }}
                    >
                      ₹ {amt}
                    </button>
                  );
                })}
              </div>

              {/* Payment Method Selector */}
              <div style={{ fontSize: '13px', fontWeight: 700, color: '#e2e8f0' }}>Payment Mode:</div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '8px' }}>
                {[
                  { id: 'UPI' as const, label: 'UPI / GPay', icon: <Smartphone size={15} /> },
                  { id: 'NETBANKING' as const, label: 'NetBanking', icon: <Building size={15} /> },
                  { id: 'CARD' as const, label: 'Cards', icon: <CreditCard size={15} /> },
                ].map((m) => {
                  const isSel = payMethod === m.id;
                  return (
                    <button
                      key={m.id}
                      type="button"
                      onClick={() => setPayMethod(m.id)}
                      style={{
                        padding: '8px 6px',
                        borderRadius: '10px',
                        border: isSel ? '1px solid #10b981' : '1px solid rgba(255,255,255,0.1)',
                        background: isSel ? 'rgba(16, 185, 129, 0.15)' : 'rgba(0,0,0,0.3)',
                        color: isSel ? '#34d399' : '#cbd5e1',
                        fontSize: '12px',
                        fontWeight: 700,
                        cursor: 'pointer',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        gap: '6px',
                      }}
                    >
                      {m.icon}
                      {m.label}
                    </button>
                  );
                })}
              </div>

              <button
                type="button"
                disabled={loading}
                onClick={handleDeposit}
                style={{
                  width: '100%',
                  padding: '14px',
                  borderRadius: '14px',
                  border: 'none',
                  background: 'linear-gradient(135deg, #10b981 0%, #059669 100%)',
                  color: '#ffffff',
                  fontSize: '16px',
                  fontWeight: 900,
                  cursor: 'pointer',
                  boxShadow: '0 6px 20px rgba(16, 185, 129, 0.35)',
                  marginTop: '4px',
                }}
              >
                {loading ? 'Processing…' : `Deposit ₹ ${depositAmount} Instantly`}
              </button>
            </div>
          )}

          {/* WITHDRAW FORM */}
          {activeTab === 'WITHDRAW' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <div>
                <label style={{ display: 'block', fontSize: '13px', fontWeight: 700, color: '#e2e8f0', marginBottom: '6px' }}>
                  Withdrawal Amount (₹):
                </label>
                <input
                  type="number"
                  value={withdrawAmount}
                  max={winningsBalance}
                  min={50}
                  onChange={(e) => setWithdrawAmount(Math.max(0, parseInt(e.target.value, 10) || 0))}
                  style={{
                    width: '100%',
                    background: 'rgba(0,0,0,0.4)',
                    border: '1px solid rgba(255,255,255,0.15)',
                    borderRadius: '10px',
                    color: '#fff',
                    fontSize: '16px',
                    fontWeight: 700,
                    padding: '10px 14px',
                    outline: 'none',
                  }}
                />
                <div style={{ fontSize: '11px', color: '#94a3b8', marginTop: '4px' }}>
                  Max withdrawable winnings: <strong style={{ color: '#34d399' }}>₹ {winningsBalance.toFixed(2)}</strong>
                </div>
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '13px', fontWeight: 700, color: '#e2e8f0', marginBottom: '6px' }}>
                  UPI ID / Virtual Payment Address:
                </label>
                <input
                  type="text"
                  value={upiId}
                  onChange={(e) => setUpiId(e.target.value)}
                  placeholder="yourname@okhdfcbank"
                  style={{
                    width: '100%',
                    background: 'rgba(0,0,0,0.4)',
                    border: '1px solid rgba(255,255,255,0.15)',
                    borderRadius: '10px',
                    color: '#fff',
                    fontSize: '14px',
                    padding: '10px 14px',
                    outline: 'none',
                  }}
                />
              </div>

              <button
                type="button"
                disabled={loading || winningsBalance < 50}
                onClick={handleWithdraw}
                style={{
                  width: '100%',
                  padding: '14px',
                  borderRadius: '14px',
                  border: 'none',
                  background: 'linear-gradient(135deg, #3b82f6 0%, #1d4ed8 100%)',
                  color: '#ffffff',
                  fontSize: '16px',
                  fontWeight: 900,
                  cursor: winningsBalance < 50 ? 'not-allowed' : 'pointer',
                  opacity: winningsBalance < 50 ? 0.6 : 1,
                  boxShadow: '0 6px 20px rgba(59, 130, 246, 0.35)',
                  marginTop: '4px',
                }}
              >
                {loading ? 'Processing…' : `Withdraw ₹ ${withdrawAmount} to UPI`}
              </button>
            </div>
          )}

          {/* Daily Bonus Button */}
          <div style={{ paddingTop: '8px' }}>
            <button
              type="button"
              onClick={handleClaimDaily}
              style={{
                width: '100%',
                padding: '10px',
                borderRadius: '12px',
                border: '1px solid rgba(212,175,55,0.3)',
                background: 'rgba(212,175,55,0.08)',
                color: '#fef08a',
                fontSize: '13px',
                fontWeight: 700,
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '8px',
              }}
            >
              <Gift size={16} color="#fbbf24" /> Claim Daily ₹500 Free Cash Bonus
            </button>
          </div>

          {/* Trust badge */}
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
            <span>100% Secure SSL Ledger · RNG Certified · Instant UPI Settlements</span>
          </div>

          {/* Recent Real Cash Transactions */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
            <div style={{ fontSize: '13px', fontWeight: 800, color: '#94a3b8', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <History size={15} /> Recent Cash Transactions
            </div>
            <div
              style={{
                maxHeight: '160px',
                overflowY: 'auto',
                display: 'flex',
                flexDirection: 'column',
                gap: '6px',
              }}
            >
              {transactions.length === 0 ? (
                <div style={{ fontSize: '12px', color: '#64748b', textAlign: 'center', padding: '12px' }}>
                  No cash transactions recorded yet.
                </div>
              ) : (
                transactions.slice(0, 10).map((t) => {
                  const isPositive =
                    t.transactionType.includes('CREDIT') ||
                    t.transactionType.includes('WIN') ||
                    t.transactionType.includes('DEPOSIT');
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
                        <div style={{ fontWeight: 700, color: '#f8fafc' }}>{t.transactionType}</div>
                        <div style={{ fontSize: '10px', color: '#64748b' }}>{t.description}</div>
                      </div>
                      <div style={{ fontWeight: 900, color: isPositive ? '#34d399' : '#f87171' }}>
                        {isPositive ? '+' : '-'} ₹ {Math.abs(t.amount)}
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
