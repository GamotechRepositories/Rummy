import React, { useEffect, useState } from 'react';
import { useGameStore } from '../store/useGameStore';
import { Coins, Gift, ShieldAlert, History, X, Sparkles, PlusCircle } from 'lucide-react';

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
  const [balance, setBalance] = useState<number>(1000);
  const [transactions, setTransactions] = useState<WalletTransaction[]>([]);
  const [loading, setLoading] = useState(false);
  const [feedback, setFeedback] = useState<string | null>(null);

  const fetchWallet = async () => {
    try {
      setLoading(true);
      const res = await fetch(`http://localhost:8081/api/wallet/balance?playerId=${playerId}`);
      if (res.ok) {
        const data = await res.json();
        setBalance(data.freePlayBalance ?? 1000);
      }

      const txRes = await fetch(`http://localhost:8081/api/wallet/transactions?playerId=${playerId}`);
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

  const claimDailyBonus = async () => {
    try {
      setLoading(true);
      const res = await fetch(`http://localhost:8081/api/wallet/claim-daily?playerId=${playerId}`, { method: 'POST' });
      const data = await res.json();
      if (res.ok && data.success) {
        setFeedback('🎉 Claimed 500 Free-Play Tokens!');
        fetchWallet();
      } else {
        setFeedback(data.message || 'Daily bonus already claimed for today!');
      }
      setTimeout(() => setFeedback(null), 4000);
    } catch {
      setFeedback('Failed to claim daily bonus');
    } finally {
      setLoading(false);
    }
  };

  const getTestFaucet = async () => {
    try {
      setLoading(true);
      const res = await fetch(`http://localhost:8081/api/wallet/faucet?playerId=${playerId}&amount=1000`, { method: 'POST' });
      if (res.ok) {
        setFeedback('🪙 Added +1,000 Free-Play Tokens from Test Faucet!');
        fetchWallet();
      }
      setTimeout(() => setFeedback(null), 4000);
    } catch {
      setFeedback('Faucet error');
    } finally {
      setLoading(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div style={{
      position: 'fixed',
      inset: 0,
      background: 'rgba(5, 12, 8, 0.85)',
      backdropFilter: 'blur(10px)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      zIndex: 9999,
      padding: '1rem',
    }}>
      <div style={{
        background: 'linear-gradient(145deg, #13241b, #0d1a13)',
        border: '1px solid rgba(212, 175, 55, 0.4)',
        borderRadius: '16px',
        width: '100%',
        maxWidth: '540px',
        boxShadow: '0 20px 50px rgba(0,0,0,0.8), 0 0 30px rgba(212, 175, 55, 0.15)',
        color: '#fff',
        overflow: 'hidden',
      }}>
        {/* Header */}
        <div style={{
          padding: '1.25rem 1.5rem',
          borderBottom: '1px solid rgba(255,255,255,0.08)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          background: 'rgba(212, 175, 55, 0.05)',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
            <div style={{
              width: '40px',
              height: '40px',
              borderRadius: '10px',
              background: 'linear-gradient(135deg, #d4af37, #856404)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: '#000',
            }}>
              <Coins size={22} />
            </div>
            <div>
              <h3 style={{ margin: 0, fontSize: '1.2rem', fontWeight: 700, color: '#f3e5ab' }}>
                Player Token Vault
              </h3>
              <p style={{ margin: 0, fontSize: '0.8rem', color: '#88ab8e' }}>
                Player Token Balance
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            style={{
              background: 'transparent',
              border: 'none',
              color: '#88ab8e',
              cursor: 'pointer',
              padding: '0.5rem',
              display: 'flex',
              alignItems: 'center',
            }}
          >
            <X size={20} />
          </button>
        </div>

        {/* Content */}
        <div style={{ padding: '1.5rem', display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
          {/* Balance Display */}
          <div style={{
            background: 'linear-gradient(135deg, rgba(212, 175, 55, 0.15), rgba(212, 175, 55, 0.03))',
            border: '1px solid rgba(212, 175, 55, 0.3)',
            borderRadius: '12px',
            padding: '1.25rem',
            textAlign: 'center',
          }}>
            <span style={{ fontSize: '0.85rem', color: '#d4af37', textTransform: 'uppercase', letterSpacing: '1px' }}>
              Free-Play Token Balance
            </span>
            <div style={{
              fontSize: '2.5rem',
              fontWeight: 800,
              color: '#fff',
              margin: '0.25rem 0',
              textShadow: '0 2px 10px rgba(212,175,55,0.4)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '0.5rem',
            }}>
              <Sparkles size={28} color="#d4af37" />
              {balance.toLocaleString()}
              <span style={{ fontSize: '1rem', color: '#d4af37', fontWeight: 600 }}>TOKENS</span>
            </div>
            <div style={{ fontSize: '0.75rem', color: '#88ab8e' }}>
              Player ID: <code style={{ color: '#fff' }}>{playerId}</code>
            </div>
          </div>

          {feedback && (
            <div style={{
              padding: '0.75rem 1rem',
              borderRadius: '8px',
              background: 'rgba(40, 167, 69, 0.2)',
              border: '1px solid rgba(40, 167, 69, 0.4)',
              color: '#d4edda',
              fontSize: '0.85rem',
              textAlign: 'center',
            }}>
              {feedback}
            </div>
          )}

          {/* Quick Action Buttons */}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.75rem' }}>
            <button
              onClick={claimDailyBonus}
              disabled={loading}
              style={{
                padding: '0.85rem',
                borderRadius: '10px',
                border: 'none',
                background: 'linear-gradient(135deg, #28a745, #1e7e34)',
                color: '#fff',
                fontWeight: 700,
                fontSize: '0.9rem',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '0.5rem',
                boxShadow: '0 4px 15px rgba(40,167,69,0.3)',
              }}
            >
              <Gift size={18} />
              Claim Daily 500
            </button>

            <button
              onClick={getTestFaucet}
              disabled={loading}
              style={{
                padding: '0.85rem',
                borderRadius: '10px',
                border: '1px solid rgba(212, 175, 55, 0.4)',
                background: 'linear-gradient(135deg, rgba(212, 175, 55, 0.2), rgba(212, 175, 55, 0.05))',
                color: '#f3e5ab',
                fontWeight: 700,
                fontSize: '0.9rem',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '0.5rem',
              }}
            >
              <PlusCircle size={18} />
              +1,000 Faucet
            </button>
          </div>

          {/* Compliance Banner */}
          <div style={{
            display: 'flex',
            alignItems: 'flex-start',
            gap: '0.75rem',
            padding: '0.85rem',
            borderRadius: '10px',
            background: 'rgba(255, 193, 7, 0.08)',
            border: '1px solid rgba(255, 193, 7, 0.25)',
          }}>
            <ShieldAlert size={20} color="#ffc107" style={{ flexShrink: 0, marginTop: '2px' }} />
            <div style={{ fontSize: '0.75rem', color: '#e6c875', lineHeight: 1.4 }}>
              <strong style={{ color: '#ffc107' }}>Platform Notice:</strong> This platform operates strictly in Free-Play Mode using non-redeemable virtual game tokens.
            </div>
          </div>

          {/* Recent Ledger History */}
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.75rem' }}>
              <History size={16} color="#88ab8e" />
              <span style={{ fontSize: '0.85rem', fontWeight: 600, color: '#88ab8e' }}>
                Recent Ledger Transactions
              </span>
            </div>

            <div style={{
              maxHeight: '140px',
              overflowY: 'auto',
              borderRadius: '8px',
              background: 'rgba(0,0,0,0.3)',
              border: '1px solid rgba(255,255,255,0.05)',
            }}>
              {transactions.length === 0 ? (
                <div style={{ padding: '1rem', textAlign: 'center', fontSize: '0.8rem', color: '#666' }}>
                  No previous transactions recorded.
                </div>
              ) : (
                transactions.slice(0, 5).map((tx) => (
                  <div
                    key={tx.id || tx.idempotencyKey}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      padding: '0.6rem 0.85rem',
                      borderBottom: '1px solid rgba(255,255,255,0.04)',
                      fontSize: '0.8rem',
                    }}
                  >
                    <div>
                      <div style={{ fontWeight: 600, color: '#fff' }}>{tx.transactionType}</div>
                      <div style={{ fontSize: '0.7rem', color: '#888' }}>
                        {new Date(tx.createdAt).toLocaleTimeString()} · {tx.description || 'Ledger entry'}
                      </div>
                    </div>
                    <div style={{
                      fontWeight: 700,
                      color: tx.transactionType.includes('WIN') || tx.transactionType.includes('CREDIT') ? '#28a745' : '#dc3545',
                    }}>
                      {tx.transactionType.includes('WIN') || tx.transactionType.includes('CREDIT') ? '+' : '-'}
                      {tx.amount}
                    </div>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
