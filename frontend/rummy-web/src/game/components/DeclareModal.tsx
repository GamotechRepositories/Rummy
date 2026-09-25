import React from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { CardView } from './CardView';
import { checkOverallDeclaration } from '../rules/clientValidator';
import confetti from 'canvas-confetti';
import { Award, AlertTriangle, X } from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';

export const DeclareModal: React.FC = () => {
  const {
    isDeclareModalOpen,
    setDeclareModalOpen,
    gameState,
    groups,
    selectedCardIds,
  } = useGameStore();

  if (!isDeclareModalOpen || !gameState) return null;

  const finishCardId = selectedCardIds[0];
  const finishCard = gameState.hand.find((c) => c.instanceId === finishCardId);

  const remainingGroups = groups
    .map((g) => ({
      ...g,
      cards: g.cards.filter((c) => c.instanceId !== finishCardId),
    }))
    .filter((g) => g.cards.length > 0);

  const evaluation = checkOverallDeclaration(remainingGroups, gameState.cutJoker, gameState.rulesetId);

  const handleConfirmDeclare = () => {
    if (!finishCardId) return;

    if (evaluation.isValid) {
      soundEngine.play('win');
      confetti({
        particleCount: 120,
        spread: 70,
        origin: { y: 0.6 },
      });
    } else {
      soundEngine.play('error');
    }

    socketClient.declare(finishCardId, remainingGroups);
    setDeclareModalOpen(false);
  };

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        backgroundColor: 'rgba(0, 0, 0, 0.75)',
        backdropFilter: 'blur(8px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 100,
        padding: '20px',
      }}
    >
      <div
        className="glass-panel"
        style={{
          width: '100%',
          maxWidth: '680px',
          border: '1px solid var(--border-gold)',
          padding: '24px',
          display: 'flex',
          flexDirection: 'column',
          gap: '20px',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <Award size={26} color="var(--gold-accent)" />
            <h2 style={{ fontSize: '20px', fontWeight: 800 }}>Ready to declare a win?</h2>
          </div>
          <button
            type="button"
            onClick={() => setDeclareModalOpen(false)}
            style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}
          >
            <X size={20} />
          </button>
        </div>

        {finishCard && (
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '16px',
              padding: '12px 16px',
              background: 'rgba(255, 255, 255, 0.04)',
              borderRadius: '12px',
              border: '1px solid rgba(255, 255, 255, 0.1)',
            }}
          >
            <CardView card={finishCard} wildJoker={gameState.cutJoker} size="small" />
            <div>
              <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>Card you are throwing to finish:</div>
              <div style={{ fontWeight: 700, fontSize: '15px', color: 'var(--gold-light)' }}>
                {finishCard.rank} of {finishCard.suit}
              </div>
              <div style={{ fontSize: '11px', color: 'var(--text-dim)', marginTop: '2px' }}>
                Your other 13 cards must all be in valid groups.
              </div>
            </div>
          </div>
        )}

        {evaluation.isValid ? (
          <div
            style={{
              padding: '12px 16px',
              background: 'rgba(16, 185, 129, 0.15)',
              border: '1px solid var(--color-pure)',
              borderRadius: '10px',
              color: 'var(--color-pure)',
              fontSize: '13px',
              fontWeight: 600,
              display: 'flex',
              alignItems: 'center',
              gap: '10px',
            }}
          >
            <span>✓</span>
            <span>Looks like a valid win! You get 0 penalty points.</span>
          </div>
        ) : (
          <div
            style={{
              padding: '12px 16px',
              background: 'rgba(239, 68, 68, 0.15)',
              border: '1px solid var(--color-invalid)',
              borderRadius: '10px',
              color: '#fca5a5',
              fontSize: '13px',
              fontWeight: 600,
              display: 'flex',
              alignItems: 'center',
              gap: '10px',
            }}
          >
            <AlertTriangle size={18} color="var(--color-invalid)" />
            <div>
              <div>Not a valid win yet — {evaluation.reason}</div>
              <div style={{ fontSize: '11px', opacity: 0.8, marginTop: '2px' }}>
                If you declare anyway and it is wrong, you get 80 penalty points.
              </div>
            </div>
          </div>
        )}

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px', marginTop: '10px' }}>
          <button type="button" className="btn-secondary" onClick={() => setDeclareModalOpen(false)}>
            Go back
          </button>

          <button
            type="button"
            id="btn-confirm-declare"
            className="btn-primary"
            onClick={handleConfirmDeclare}
            style={{
              background: evaluation.isValid
                ? 'linear-gradient(135deg, #10b981 0%, #059669 100%)'
                : 'linear-gradient(135deg, #ef4444 0%, #b91c1c 100%)',
              color: '#ffffff',
            }}
          >
            {evaluation.isValid ? 'Yes — Declare win' : 'Declare anyway (risky)'}
          </button>
        </div>
      </div>
    </div>
  );
};
