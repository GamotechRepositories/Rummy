import React, { useEffect } from 'react';
import { createPortal } from 'react-dom';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { CardView } from './CardView';
import { checkOverallDeclaration } from '../rules/clientValidator';
import { Award, AlertTriangle, X, CheckCircle2, ShieldCheck, AlertCircle, ArrowLeft } from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';

export const DeclareModal: React.FC = () => {
  const {
    isDeclareModalOpen,
    setDeclareModalOpen,
    gameState,
    groups,
    selectedCardIds,
  } = useGameStore();

  useEffect(() => {
    if (!isDeclareModalOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        soundEngine.play('click');
        setDeclareModalOpen(false);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isDeclareModalOpen, setDeclareModalOpen]);

  if (!isDeclareModalOpen || !gameState) return null;

  const finishCardId = selectedCardIds[0];
  const finishCard = gameState.hand.find((c) => c.instanceId === finishCardId);

  const isRummy21 = gameState.rulesetId?.includes('21') || gameState.rulesetId === 'RUMMY_21';
  const remainingHandTarget = isRummy21 ? 21 : 13;
  const wrongDeclarationPenalty = isRummy21 ? 120 : 80;

  const remainingGroups = groups
    .map((g) => ({
      ...g,
      cards: g.cards.filter((c) => c.instanceId !== finishCardId),
    }))
    .filter((g) => g.cards.length > 0);

  const evaluation = checkOverallDeclaration(remainingGroups, gameState.cutJoker, gameState.rulesetId);
  const finishIsPickedOpenCard =
    !!gameState.isDrawnFromDiscard && !!finishCardId && finishCardId === gameState.drawnCardInstanceId;

  const handleConfirmDeclare = () => {
    if (!finishCardId || finishIsPickedOpenCard) return;

    soundEngine.play(evaluation.isValid ? 'click' : 'error');
    socketClient.declare(finishCardId, remainingGroups);
    setDeclareModalOpen(false);
  };

  const isValid = evaluation.isValid;

  return createPortal(
    <div
      className="royal-dialog-backdrop"
      role="presentation"
      onClick={() => {
        soundEngine.play('click');
        setDeclareModalOpen(false);
      }}
      style={{
        position: 'fixed',
        inset: 0,
        backgroundColor: 'rgba(0, 0, 0, 0.82)',
        backdropFilter: 'blur(12px)',
        WebkitBackdropFilter: 'blur(12px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1200,
        padding: '16px',
        animation: 'fadeIn 0.2s ease-out',
      }}
    >
      <div
        className="royal-dialog-card"
        role="dialog"
        aria-label="Declare Win"
        onClick={(e) => e.stopPropagation()}
        style={{
          maxWidth: '480px',
          width: '100%',
          background: 'radial-gradient(130% 90% at 50% -12%, rgba(168, 28, 40, 0.45) 0%, rgba(24, 10, 14, 0.98) 55%, #0d0508 100%)',
          border: '1.5px solid rgba(212, 175, 55, 0.65)',
          borderRadius: '24px',
          boxShadow: '0 32px 72px rgba(0, 0, 0, 0.9), 0 0 36px rgba(212, 175, 55, 0.2), inset 0 1px 1px rgba(255, 235, 185, 0.4)',
          padding: '24px 22px 20px',
          position: 'relative',
          display: 'flex',
          flexDirection: 'column',
          textAlign: 'center',
          boxSizing: 'border-box',
        }}
      >
        {/* Close Button */}
        <button
          type="button"
          className="royal-dialog-close"
          onClick={() => {
            soundEngine.play('click');
            setDeclareModalOpen(false);
          }}
          aria-label="Close"
          style={{
            position: 'absolute',
            top: '14px',
            right: '14px',
            width: '32px',
            height: '32px',
            borderRadius: '50%',
            background: 'rgba(255, 255, 255, 0.08)',
            border: '1px solid rgba(255, 255, 255, 0.16)',
            color: '#94a3b8',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            cursor: 'pointer',
            transition: 'all 0.16s ease',
          }}
        >
          <X size={18} />
        </button>

        {/* Crest Header */}
        <div className="royal-dialog-header" style={{ marginBottom: '14px' }}>
          <div className="royal-dialog-crest-wrap">
            <div
              className={`royal-dialog-crest-glow ${
                isValid ? 'royal-dialog-crest-glow--emerald' : 'royal-dialog-crest-glow--amber'
              }`}
              style={
                isValid
                  ? {
                      background: 'radial-gradient(circle, rgba(16, 185, 129, 0.5) 0%, rgba(212, 175, 55, 0.2) 50%, transparent 72%)',
                    }
                  : undefined
              }
            />
            <div
              className="royal-dialog-crest-badge"
              style={
                isValid
                  ? {
                      borderColor: 'rgba(52, 211, 153, 0.85)',
                      boxShadow: '0 6px 18px rgba(0, 0, 0, 0.65), inset 0 2px 4px rgba(110, 231, 183, 0.4)',
                    }
                  : undefined
              }
            >
              <Award size={30} color={isValid ? '#34d399' : '#fbbf24'} />
            </div>
          </div>

          <h3 className="royal-dialog-title" style={{ fontSize: '23px', margin: '0 0 6px' }}>
            Ready to Declare?
          </h3>
          <p className="royal-dialog-subtitle" style={{ fontSize: '13px', margin: '0 0 6px', color: '#94a3b8' }}>
            Review your finish card and hand validation before finalizing.
          </p>
        </div>

        {/* Finish Card Showcase Box */}
        {finishCard && (
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '16px',
              padding: '12px 16px',
              background: 'linear-gradient(135deg, rgba(255, 255, 255, 0.05) 0%, rgba(255, 255, 255, 0.02) 100%)',
              borderRadius: '16px',
              border: '1px solid rgba(212, 175, 55, 0.25)',
              marginBottom: '14px',
              textAlign: 'left',
              boxShadow: 'inset 0 1px 0 rgba(255, 255, 255, 0.06), 0 4px 12px rgba(0, 0, 0, 0.25)',
            }}
          >
            <div style={{ flexShrink: 0, transform: 'scale(0.95)', transformOrigin: 'left center' }}>
              <CardView card={finishCard} wildJoker={gameState.cutJoker} />
            </div>
            <div style={{ flex: 1 }}>
              <div
                style={{
                  fontSize: '11px',
                  fontWeight: 800,
                  textTransform: 'uppercase',
                  letterSpacing: '0.8px',
                  color: '#fbbf24',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '5px',
                }}
              >
                <span>FINISH CARD</span>
              </div>
              <div
                style={{
                  fontWeight: 800,
                  fontSize: '16px',
                  color: '#ffffff',
                  marginTop: '2px',
                  letterSpacing: '0.3px',
                }}
              >
                {finishCard.rank} OF {finishCard.suit}
              </div>
              <div style={{ fontSize: '12px', color: '#94a3b8', marginTop: '3px', lineHeight: 1.3 }}>
                Your other {remainingHandTarget} cards must be in valid groups.
              </div>
            </div>
          </div>
        )}

        {/* Validation Status Banner */}
        {finishIsPickedOpenCard ? (
          <div
            style={{
              padding: '12px 14px',
              background: 'linear-gradient(180deg, rgba(38, 12, 16, 0.9) 0%, rgba(20, 6, 9, 0.95) 100%)',
              border: '1.5px solid rgba(239, 68, 68, 0.5)',
              borderRadius: '14px',
              color: '#fca5a5',
              fontSize: '13px',
              display: 'flex',
              alignItems: 'center',
              gap: '12px',
              textAlign: 'left',
              marginBottom: '18px',
              boxShadow: '0 4px 16px rgba(220, 38, 38, 0.2)',
            }}
          >
            <AlertCircle size={22} color="#ef4444" style={{ flexShrink: 0 }} />
            <div style={{ lineHeight: 1.4 }}>
              <strong style={{ color: '#ffffff', display: 'block', fontSize: '13.5px' }}>
                Cannot finish with picked discard
              </strong>
              You cannot finish with the card you just picked from the open discard pile. Choose another card.
            </div>
          </div>
        ) : isValid ? (
          <div
            style={{
              padding: '12px 14px',
              background: 'linear-gradient(180deg, rgba(6, 44, 28, 0.9) 0%, rgba(3, 24, 15, 0.95) 100%)',
              border: '1.5px solid rgba(16, 185, 129, 0.55)',
              borderRadius: '14px',
              color: '#a7f3d0',
              fontSize: '13px',
              display: 'flex',
              alignItems: 'center',
              gap: '12px',
              textAlign: 'left',
              marginBottom: '18px',
              boxShadow: '0 4px 16px rgba(16, 185, 129, 0.22)',
            }}
          >
            <div
              style={{
                width: '34px',
                height: '34px',
                borderRadius: '50%',
                background: 'rgba(16, 185, 129, 0.2)',
                border: '1px solid rgba(16, 185, 129, 0.5)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                flexShrink: 0,
              }}
            >
              <CheckCircle2 size={20} color="#34d399" />
            </div>
            <div style={{ lineHeight: 1.35 }}>
              <strong style={{ color: '#ffffff', display: 'block', fontSize: '13.5px', marginBottom: '2px' }}>
                ✓ Valid Declaration! (0 Penalty Points)
              </strong>
              All sequence and set rules are satisfied. You will win this hand!
            </div>
          </div>
        ) : (
          <div
            style={{
              padding: '12px 14px',
              background: 'linear-gradient(180deg, rgba(42, 14, 18, 0.9) 0%, rgba(22, 6, 9, 0.95) 100%)',
              border: '1.5px solid rgba(239, 68, 68, 0.5)',
              borderRadius: '14px',
              color: '#fca5a5',
              fontSize: '13px',
              display: 'flex',
              alignItems: 'flex-start',
              gap: '12px',
              textAlign: 'left',
              marginBottom: '18px',
              boxShadow: '0 4px 16px rgba(220, 38, 38, 0.2)',
            }}
          >
            <AlertTriangle size={22} color="#ef4444" style={{ flexShrink: 0, marginTop: '2px' }} />
            <div style={{ lineHeight: 1.4 }}>
              <strong style={{ color: '#ffffff', display: 'block', fontSize: '13.5px', marginBottom: '2px' }}>
                Not a valid win yet — {evaluation.reason}
              </strong>
              If you declare anyway and it is invalid, you will receive{' '}
              <strong style={{ color: '#ef4444' }}>{wrongDeclarationPenalty} penalty points</strong>.
            </div>
          </div>
        )}

        {/* Action Buttons */}
        <div style={{ display: 'flex', gap: '12px', width: '100%' }}>
          <button
            type="button"
            onClick={() => {
              soundEngine.play('click');
              setDeclareModalOpen(false);
            }}
            style={{
              flex: 1,
              background: 'linear-gradient(180deg, rgba(255, 255, 255, 0.08) 0%, rgba(255, 255, 255, 0.03) 100%)',
              border: '1px solid rgba(255, 255, 255, 0.16)',
              borderRadius: '12px',
              color: '#cbd5e1',
              fontSize: '14px',
              fontWeight: 700,
              padding: '12px 16px',
              cursor: 'pointer',
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '8px',
              transition: 'all 0.16s ease',
            }}
          >
            <ArrowLeft size={16} />
            Go back
          </button>

          <button
            type="button"
            id="btn-confirm-declare"
            onClick={handleConfirmDeclare}
            disabled={finishIsPickedOpenCard}
            style={{
              flex: 1.4,
              background: finishIsPickedOpenCard
                ? 'rgba(255, 255, 255, 0.08)'
                : isValid
                ? 'linear-gradient(180deg, #34d399 0%, #059669 60%, #047857 100%)'
                : 'linear-gradient(180deg, #f87171 0%, #dc2626 60%, #991b1b 100%)',
              border: finishIsPickedOpenCard
                ? '1px solid rgba(255, 255, 255, 0.12)'
                : isValid
                ? '1px solid rgba(110, 231, 183, 0.6)'
                : '1px solid rgba(252, 165, 165, 0.5)',
              borderRadius: '12px',
              color: '#ffffff',
              fontSize: '14px',
              fontWeight: 800,
              letterSpacing: '0.4px',
              padding: '12px 18px',
              cursor: finishIsPickedOpenCard ? 'not-allowed' : 'pointer',
              opacity: finishIsPickedOpenCard ? 0.45 : 1,
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '8px',
              boxShadow: finishIsPickedOpenCard
                ? 'none'
                : isValid
                ? '0 6px 20px rgba(5, 150, 105, 0.45), inset 0 1px 0 rgba(255, 255, 255, 0.4)'
                : '0 6px 20px rgba(220, 38, 38, 0.45), inset 0 1px 0 rgba(255, 255, 255, 0.3)',
              transition: 'all 0.16s ease',
            }}
          >
            {isValid ? (
              <>
                <ShieldCheck size={18} />
                Yes — Declare Win
              </>
            ) : (
              <>
                <AlertTriangle size={18} />
                Declare Anyway
              </>
            )}
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
};
