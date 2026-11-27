import React from 'react';
import { Wallet, X } from 'lucide-react';
import { useModalScroll } from '../hooks/useModalScroll';
import { formatCurrencySymbol, getCurrencyCode } from '../utils/authClient';
import { useGameStore } from '../store/useGameStore';

export interface InsufficientBalanceInfo {
  requiredAmount?: number;
  currentBalance?: number | null;
  contextLabel?: string;
}

interface InsufficientBalanceModalProps {
  info: InsufficientBalanceInfo | null;
  onClose: () => void;
}

function formatInr(amount: number): string {
  return Number.isInteger(amount)
    ? amount.toLocaleString('en-IN')
    : amount.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** Informs the player when their provider balance is below the stake or entry fee required to start. */
export const InsufficientBalanceModal: React.FC<InsufficientBalanceModalProps> = ({ info, onClose }) => {
  const modalScroll = useModalScroll();
  const currency = useGameStore((s) => s.currency);
  const currencySym = formatCurrencySymbol(currency || getCurrencyCode());

  if (!info) return null;

  const hasRequired = typeof info.requiredAmount === 'number' && info.requiredAmount > 0;
  const hasBalance = typeof info.currentBalance === 'number' && Number.isFinite(info.currentBalance);
  const contextText = info.contextLabel || 'join this game';

  return (
    <div
      className="royal-dialog-backdrop"
      role="presentation"
      onClick={onClose}
    >
      <div
        className="royal-dialog-card"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="insufficient-balance-title"
        onClick={(e) => e.stopPropagation()}
        onWheel={modalScroll.onWheel}
        onTouchStart={modalScroll.onTouchStart}
        onTouchMove={modalScroll.onTouchMove}
      >
        <button
          type="button"
          className="royal-dialog-close"
          onClick={onClose}
          aria-label="Close"
        >
          <X size={18} />
        </button>

        <div className="royal-dialog-header">
          <div className="royal-dialog-crest-wrap">
            <div className="royal-dialog-crest-glow royal-dialog-crest-glow--amber" />
            <div className="royal-dialog-crest-badge">
              <Wallet size={26} color="#fbbf24" />
            </div>
          </div>

          <h3 id="insufficient-balance-title" className="royal-dialog-title">
            Insufficient Balance
          </h3>
          <p className="royal-dialog-subtitle">
            {hasRequired
              ? `You need ${currencySym}${formatInr(info.requiredAmount!)} to ${contextText}. Please add funds in your gaming account to continue.`
              : `Your gaming account balance is too low to ${contextText}. Please add funds in your gaming account to continue.`}
          </p>
        </div>

        {(hasRequired || hasBalance) && (
          <div
            className="royal-dialog-body"
            onWheel={modalScroll.onWheel}
            onTouchStart={modalScroll.onTouchStart}
            onTouchMove={modalScroll.onTouchMove}
          >
            <div className="table-menu-rows" style={{ textAlign: 'left' }}>
              {hasRequired && (
                <div className="table-menu-row">
                  <span>Required</span>
                  <span className="table-menu-value--gold">{currencySym} {formatInr(info.requiredAmount!)}</span>
                </div>
              )}
              {hasBalance && (
                <div className="table-menu-row">
                  <span>Available Balance</span>
                  <span className="table-menu-value--penalty">{currencySym} {formatInr(info.currentBalance!)}</span>
                </div>
              )}
            </div>
          </div>
        )}

        <div className="royal-dialog-actions">
          <button
            type="button"
            className="royal-btn-gold"
            onClick={onClose}
          >
            OK
          </button>
        </div>
      </div>
    </div>
  );
};
