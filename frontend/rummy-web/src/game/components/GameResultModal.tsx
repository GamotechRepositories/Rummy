import React, { useEffect } from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import confetti from 'canvas-confetti';
import { Trophy, Award, RotateCcw, LogOut, CheckCircle2, AlertCircle } from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';

interface GameResultModalProps {
  isOpen: boolean;
}

export const GameResultModal: React.FC<GameResultModalProps> = ({ isOpen }) => {
  const { gameState, playerId, displayName, leaveTable, setAutoMatchmakePending, clearSelection } =
    useGameStore();

  const isCompleted = gameState?.gameStatus === 'COMPLETED';
  const winnerId = gameState?.winnerId;
  const isWinner = winnerId === playerId;

  useEffect(() => {
    if (isOpen && isCompleted && isWinner) {
      confetti({
        particleCount: 100,
        spread: 70,
        origin: { y: 0.5 },
      });
      soundEngine.play('win');
    } else if (isOpen && isCompleted && !isWinner) {
      soundEngine.play('lose');
    }
  }, [isOpen, isCompleted, isWinner]);

  if (!isOpen || !isCompleted || !gameState) return null;

  const opponents = gameState.opponents ?? [];
  const winnerName = isWinner
    ? displayName
    : opponents.find((p) => p.playerId === winnerId)?.displayName ?? 'Opponent';

  // Build players list for the scoreboard
  const myScore = isWinner ? 0 : (gameState.viewerScore ?? (opponents.length > 0 ? opponents[0].score : 80));
  const myStatus = isWinner ? 'WON' : (gameState.viewerStatus ?? 'LOST');

  const allPlayers = [
    {
      playerId,
      name: displayName || 'You',
      isMe: true,
      isWinner,
      status: myStatus,
      score: myScore,
    },
    ...opponents.map((opp) => ({
      playerId: opp.playerId,
      name: opp.displayName,
      isMe: false,
      isWinner: opp.playerId === winnerId,
      status: opp.playerId === winnerId ? 'WON' : opp.status,
      score: opp.score,
    })),
  ];

  const handleRematch = () => {
    soundEngine.play('match');
    clearSelection();
    setAutoMatchmakePending(true);
    socketClient.disconnect();
    leaveTable();
  };

  const handleLeave = () => {
    soundEngine.play('click');
    socketClient.disconnect();
    leaveTable();
  };

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        backgroundColor: 'rgba(0, 0, 0, 0.78)',
        backdropFilter: 'blur(8px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1000,
        padding: '16px',
        animation: 'fadeIn 0.25s ease-out',
      }}
    >
      <div
        style={{
          width: '100%',
          maxWidth: '520px',
          background: 'linear-gradient(180deg, #1e293b 0%, #0f172a 100%)',
          borderRadius: '24px',
          border: isWinner ? '2px solid var(--border-gold)' : '1px solid rgba(255, 255, 255, 0.15)',
          boxShadow: isWinner
            ? '0 0 40px rgba(212, 175, 55, 0.35), 0 20px 50px rgba(0, 0, 0, 0.8)'
            : '0 20px 50px rgba(0, 0, 0, 0.8)',
          overflow: 'hidden',
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        {/* Header Banner */}
        <div
          style={{
            padding: '24px 20px 16px',
            textAlign: 'center',
            background: isWinner
              ? 'linear-gradient(180deg, rgba(212, 175, 55, 0.25) 0%, transparent 100%)'
              : 'linear-gradient(180deg, rgba(51, 65, 85, 0.4) 0%, transparent 100%)',
            borderBottom: '1px solid rgba(255, 255, 255, 0.08)',
          }}
        >
          <div
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: '64px',
              height: '64px',
              borderRadius: '50%',
              background: isWinner
                ? 'linear-gradient(135deg, #fbbf24, #d97706)'
                : 'linear-gradient(135deg, #475569, #1e293b)',
              boxShadow: isWinner ? '0 0 20px rgba(251, 191, 36, 0.5)' : 'none',
              marginBottom: '12px',
            }}
          >
            {isWinner ? <Trophy size={32} color="#1e1b4b" /> : <Award size={32} color="#94a3b8" />}
          </div>

          <h2
            style={{
              margin: '0 0 6px',
              fontSize: '24px',
              fontWeight: 900,
              color: isWinner ? 'var(--gold-light)' : '#f8fafc',
              fontFamily: 'var(--font-display)',
              letterSpacing: '-0.01em',
            }}
          >
            {isWinner ? '🎉 You Won the Hand!' : `${winnerName} Won the Hand`}
          </h2>
          <p style={{ margin: 0, fontSize: '13px', color: '#94a3b8' }}>
            {isWinner
              ? 'Excellent declaration! 0 penalty points.'
              : 'Better luck next hand! Review your cards and scores below.'}
          </p>
        </div>

        {/* Scoreboard Table */}
        <div style={{ padding: '20px' }}>
          <div
            style={{
              fontSize: '11px',
              fontWeight: 800,
              textTransform: 'uppercase',
              letterSpacing: '0.08em',
              color: '#94a3b8',
              marginBottom: '10px',
              display: 'flex',
              justifyContent: 'space-between',
              padding: '0 8px',
            }}
          >
            <span>Player</span>
            <span>Status / Points</span>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
            {allPlayers.map((p) => {
              const won = p.isWinner;
              return (
                <div
                  key={p.playerId}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    padding: '12px 14px',
                    borderRadius: '14px',
                    background: won
                      ? 'rgba(212, 175, 55, 0.12)'
                      : 'rgba(255, 255, 255, 0.04)',
                    border: won
                      ? '1px solid rgba(212, 175, 55, 0.4)'
                      : '1px solid rgba(255, 255, 255, 0.08)',
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                    <div
                      style={{
                        width: '32px',
                        height: '32px',
                        borderRadius: '50%',
                        background: won ? '#fbbf24' : '#475569',
                        color: won ? '#1a1a1a' : '#ffffff',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        fontWeight: 800,
                        fontSize: '13px',
                      }}
                    >
                      {p.name.charAt(0).toUpperCase()}
                    </div>
                    <div>
                      <div style={{ fontWeight: 700, fontSize: '14px', color: '#ffffff' }}>
                        {p.name} {p.isMe ? <span style={{ color: 'var(--gold-accent)', fontSize: '11px' }}>(You)</span> : ''}
                      </div>
                      <div style={{ fontSize: '11px', color: '#94a3b8', display: 'flex', alignItems: 'center', gap: '4px' }}>
                        {won ? (
                          <span style={{ color: '#86efac', display: 'flex', alignItems: 'center', gap: '3px' }}>
                            <CheckCircle2 size={12} /> Winner
                          </span>
                        ) : (
                          <span style={{ color: '#fca5a5', display: 'flex', alignItems: 'center', gap: '3px' }}>
                            <AlertCircle size={12} /> {p.status}
                          </span>
                        )}
                      </div>
                    </div>
                  </div>

                  <div style={{ textAlign: 'right' }}>
                    <div
                      style={{
                        fontWeight: 800,
                        fontSize: '16px',
                        color: won ? '#86efac' : '#f87171',
                      }}
                    >
                      {p.score} pts
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* Action Buttons */}
        <div
          style={{
            padding: '16px 20px 20px',
            display: 'flex',
            gap: '12px',
            background: 'rgba(0, 0, 0, 0.25)',
            borderTop: '1px solid rgba(255, 255, 255, 0.06)',
          }}
        >
          <button
            type="button"
            className="btn-secondary"
            onClick={handleLeave}
            style={{
              flex: 1,
              padding: '12px',
              fontSize: '13px',
              fontWeight: 700,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '8px',
            }}
          >
            <LogOut size={16} />
            Leave Table
          </button>

          <button
            type="button"
            className="btn-primary"
            onClick={handleRematch}
            style={{
              flex: 1.6,
              padding: '12px',
              fontSize: '14px',
              fontWeight: 800,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '8px',
            }}
          >
            <RotateCcw size={16} />
            Rematch Same Stake
          </button>
        </div>
      </div>
    </div>
  );
};
