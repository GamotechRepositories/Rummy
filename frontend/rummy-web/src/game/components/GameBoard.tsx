import React from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { OpponentSeat, type SeatPosition } from './OpponentSeat';
import { TableCenter } from './TableCenter';
import { PlayerHand } from './PlayerHand';
import { ActionControls } from './ActionControls';
import { DeclareModal } from './DeclareModal';
import { LogOut, Wifi, AlertCircle, Sparkles, Menu, X, ShieldAlert, BookOpen } from 'lucide-react';
import { SoundToggle } from './SoundToggle';
import { soundEngine } from '../audio/soundEngine';
import { normalizeTurnPhase } from '../utils/turnPhase';
import { GameResultModal } from './GameResultModal';

function getPerimeterPosition(index: number, total: number): SeatPosition {
  if (total === 1) return 'top-center';
  if (total === 2) return index === 0 ? 'top-left' : 'top-right';
  if (total === 3) {
    if (index === 0) return 'left';
    if (index === 1) return 'top-center';
    return 'right';
  }
  if (total === 4) {
    if (index === 0) return 'left';
    if (index === 1) return 'top-left';
    if (index === 2) return 'top-right';
    return 'right';
  }
  // 5 or more opponents (6-max table)
  const positions: SeatPosition[] = ['left', 'top-left', 'top-center', 'top-right', 'right'];
  return positions[index % positions.length];
}

interface GameBoardProps {
  onOpenTutorial?: () => void;
}

export const GameBoard: React.FC<GameBoardProps> = ({ onOpenTutorial }) => {
  const {
    gameState,
    connectionStatus,
    errorMessage,
    leaveTable,
    playerId,
    lastGameConfig,
  } = useGameStore();

  const [menuOpen, setMenuOpen] = React.useState(false);
  const [confirmLeaveOpen, setConfirmLeaveOpen] = React.useState(false);

  const handleLeaveTable = () => {
    socketClient.disconnect();
    leaveTable();
  };

  const opponents = gameState?.opponents ?? [];
  const isMyTurn = gameState?.isMyTurn ?? false;

  // Dynamic Game Variant & Stake Details
  const rawRulesetId = (lastGameConfig?.rulesetId ?? 'POINTS_13').toUpperCase();
  const entryFee = lastGameConfig?.entryFee ?? 8;
  const maxSeats = lastGameConfig?.maxPlayers ?? Math.max(2, (gameState?.opponents?.length ?? 0) + 1);
  const isPointsRummy = rawRulesetId.includes('POINT');

  const variantName = React.useMemo(() => {
    if (rawRulesetId.includes('POOL')) {
      return rawRulesetId.includes('201') ? 'Pool 201' : 'Pool 101';
    }
    if (rawRulesetId.includes('DEAL')) {
      return 'Deal Rummy';
    }
    if (rawRulesetId.includes('21')) {
      return '21-Card Rummy';
    }
    return 'Point Rummy';
  }, [rawRulesetId]);

  const pointValue = React.useMemo(() => {
    return entryFee / 80;
  }, [entryFee]);

  const stakeLabel = React.useMemo(() => {
    if (isPointsRummy) {
      return `₹${pointValue >= 1 ? pointValue.toFixed(0) : pointValue.toFixed(2)}/pt`;
    }
    return `Entry ₹${entryFee}`;
  }, [isPointsRummy, pointValue, entryFee]);

  const tableHeaderSubtitle = `${variantName} · ${stakeLabel} · ${maxSeats} Players`;
  const totalPot = ((opponents.length + 1) * entryFee).toFixed(2);

  const turnLabel = !gameState
    ? 'Connecting…'
    : gameState.gameStatus === 'WAITING_FOR_PLAYERS'
      ? 'Getting ready…'
      : gameState.gameStatus === 'COMPLETED'
        ? gameState.winnerId === playerId
          ? 'You won'
          : 'Finished'
        : isMyTurn
          ? normalizeTurnPhase(gameState.turnPhase) === 'DRAW'
            ? 'Your turn — draw'
            : normalizeTurnPhase(gameState.turnPhase) === 'DISCARD'
              ? 'Your turn — discard'
              : 'Your turn'
          : 'Opponent’s turn';

  return (
    <div className="game-frame">
      <header className="game-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
          <Sparkles size={16} color="var(--gold-accent)" />
          <span style={{ fontWeight: 800, fontSize: 14, color: 'var(--gold-light)' }}>
            Royal Rummy
          </span>
          <span
            className={isMyTurn ? 'my-turn-pulse' : undefined}
            style={{
              fontSize: 11,
              fontWeight: 700,
              padding: '3px 8px',
              borderRadius: 16,
              background: isMyTurn ? 'rgba(212,175,55,0.2)' : 'rgba(255,255,255,0.06)',
              color: isMyTurn ? '#fef08a' : '#94a3b8',
              border: isMyTurn ? '1px solid rgba(212,175,55,0.5)' : '1px solid transparent',
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              maxWidth: 160,
            }}
          >
            {turnLabel}
          </span>
        </div>

        <div
          className="table-header-center"
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            color: 'var(--gold-light)',
            fontSize: 12,
            fontWeight: 700,
            letterSpacing: '0.02em',
            fontFamily: 'var(--font-display)',
          }}
        >
          <span style={{ width: 6, height: 6, borderRadius: '50%', background: '#fbbf24', opacity: 0.9 }} />
          <span>{tableHeaderSubtitle}</span>
          <span style={{ color: 'rgba(212, 175, 55, 0.45)', margin: '0 2px' }}>·</span>
          <span style={{ color: '#fbbf24', fontWeight: 800 }}>🪙 POT: ₹{totalPot}</span>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 4,
              fontSize: 10,
              fontWeight: 700,
              padding: '3px 8px',
              borderRadius: 16,
              background:
                connectionStatus === 'CONNECTED'
                  ? 'rgba(16, 185, 129, 0.15)'
                  : 'rgba(245, 158, 11, 0.15)',
              color: connectionStatus === 'CONNECTED' ? 'var(--color-pure)' : 'var(--color-impure)',
            }}
          >
            <Wifi size={11} />
            {connectionStatus === 'CONNECTED' ? 'Live' : '…'}
          </div>

          <SoundToggle compact />

          <button
            id="btn-table-menu"
            type="button"
            onClick={() => {
              soundEngine.play('click');
              setMenuOpen(true);
            }}
            className="btn-secondary"
            style={{
              padding: '4px 8px',
              fontSize: 12,
              display: 'flex',
              alignItems: 'center',
              gap: 4,
            }}
            title="Table Menu"
          >
            <Menu size={16} />
          </button>
        </div>
      </header>

      {errorMessage && (
        <div
          style={{
            position: 'absolute',
            top: 56,
            left: '50%',
            transform: 'translateX(-50%)',
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            background: 'rgba(239, 68, 68, 0.95)',
            color: '#fff',
            padding: '8px 14px',
            borderRadius: 8,
            fontSize: 12,
            fontWeight: 600,
            zIndex: 50,
            maxWidth: '90%',
          }}
        >
          <AlertCircle size={16} />
          <span>{errorMessage}</span>
        </div>
      )}

      <main className="casino-table" id="game-felt-table">
        {/* Perimeter Seating (Distributed around the oval table rail) */}
        <div className="table-perimeter-seats" aria-label="Opponents">
          {opponents.length > 0 ? (
            opponents.map((opp, idx) => (
              <OpponentSeat
                key={opp.playerId}
                player={opp}
                activePlayerId={gameState?.activePlayerId}
                turnDeadline={gameState?.turnDeadline}
                seatNumber={opp.seatIndex}
                gameStatus={gameState?.gameStatus}
                position={getPerimeterPosition(idx, opponents.length)}
              />
            ))
          ) : (
            <div className="opponent-waiting-pod">
              <div className="opponent-waiting-dot" />
              Waiting for opponents…
            </div>
          )}
        </div>

        {/* Table Center (Closed Deck, Wild Joker, Discard Pile, Finish Slot) */}
        <section className="table-center-wrap" aria-label="Table Center">
          <TableCenter />
        </section>

        {/* Bottom Station: Player Hand & Action Controls */}
        <section className="table-player-station" aria-label="Player Station">
          <PlayerHand />
          <ActionControls />
        </section>
      </main>

      <DeclareModal />

      <GameResultModal isOpen={gameState?.gameStatus === 'COMPLETED'} />

      {/* Safe Table Menu (☰) Modal */}
      {menuOpen && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            backgroundColor: 'rgba(0, 0, 0, 0.7)',
            backdropFilter: 'blur(6px)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1050,
            padding: '16px',
          }}
          onClick={() => setMenuOpen(false)}
        >
          <div
            style={{
              width: '100%',
              maxWidth: '380px',
              background: 'linear-gradient(180deg, #1e293b 0%, #0f172a 100%)',
              borderRadius: '20px',
              border: '1px solid rgba(255, 255, 255, 0.15)',
              boxShadow: '0 20px 50px rgba(0, 0, 0, 0.8)',
              overflow: 'hidden',
            }}
            onClick={(e) => e.stopPropagation()}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                padding: '16px 20px',
                borderBottom: '1px solid rgba(255, 255, 255, 0.08)',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Menu size={18} color="var(--gold-accent)" />
                <span style={{ fontWeight: 800, fontSize: '16px', color: '#ffffff' }}>Table Menu</span>
              </div>
              <button
                type="button"
                className="btn-secondary"
                onClick={() => setMenuOpen(false)}
                style={{ padding: '6px', borderRadius: '50%' }}
              >
                <X size={16} />
              </button>
            </div>

            <div style={{ padding: '20px' }}>
              {/* Table Info Card */}
              <div
                style={{
                  background: 'rgba(255, 255, 255, 0.04)',
                  border: '1px solid rgba(212, 175, 55, 0.25)',
                  borderRadius: '14px',
                  padding: '14px 16px',
                  marginBottom: '16px',
                }}
              >
                <div style={{ fontWeight: 800, fontSize: '14px', color: 'var(--gold-light)', marginBottom: '8px' }}>
                  {variantName} · {maxSeats} Players
                </div>
                {isPointsRummy ? (
                  <>
                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '12px', color: '#94a3b8', marginBottom: '4px' }}>
                      <span>Point Value:</span>
                      <span style={{ color: '#ffffff', fontWeight: 600 }}>{stakeLabel}</span>
                    </div>
                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '12px', color: '#94a3b8', marginBottom: '4px' }}>
                      <span>Max Penalty:</span>
                      <span style={{ color: '#ffffff', fontWeight: 600 }}>80 points (₹{entryFee.toFixed(2)})</span>
                    </div>
                  </>
                ) : (
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '12px', color: '#94a3b8', marginBottom: '4px' }}>
                    <span>Entry Fee:</span>
                    <span style={{ color: '#ffffff', fontWeight: 600 }}>₹{entryFee.toFixed(2)}</span>
                  </div>
                )}
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '12px', color: '#94a3b8' }}>
                  <span>Table ID:</span>
                  <span style={{ color: '#94a3b8', fontFamily: 'monospace' }}>{gameState?.tableId ?? 'T1'}</span>
                </div>
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                {onOpenTutorial && (
                  <button
                    type="button"
                    className="btn-secondary"
                    onClick={() => {
                      setMenuOpen(false);
                      onOpenTutorial();
                    }}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: '10px',
                      padding: '12px 14px',
                      borderRadius: '12px',
                      fontSize: '13px',
                      fontWeight: 700,
                      justifyContent: 'flex-start',
                    }}
                  >
                    <BookOpen size={16} color="var(--gold-accent)" />
                    How to Play & Rummy Rules
                  </button>
                )}

                <button
                  type="button"
                  className="btn-danger"
                  onClick={() => {
                    setMenuOpen(false);
                    if (gameState?.gameStatus === 'IN_PROGRESS') {
                      setConfirmLeaveOpen(true);
                    } else {
                      handleLeaveTable();
                    }
                  }}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '10px',
                    padding: '12px 14px',
                    borderRadius: '12px',
                    fontSize: '13px',
                    fontWeight: 800,
                    justifyContent: 'flex-start',
                    background: 'rgba(239, 68, 68, 0.15)',
                    border: '1px solid rgba(239, 68, 68, 0.4)',
                    color: '#f87171',
                  }}
                >
                  <LogOut size={16} />
                  Leave Table
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Safe Leave Confirmation Modal */}
      {confirmLeaveOpen && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            backgroundColor: 'rgba(0, 0, 0, 0.8)',
            backdropFilter: 'blur(6px)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1100,
            padding: '16px',
          }}
        >
          <div
            style={{
              width: '100%',
              maxWidth: '380px',
              background: 'linear-gradient(180deg, #1e293b 0%, #0f172a 100%)',
              borderRadius: '20px',
              border: '1px solid rgba(239, 68, 68, 0.5)',
              boxShadow: '0 20px 40px rgba(0, 0, 0, 0.8), 0 0 30px rgba(239, 68, 68, 0.25)',
              padding: '24px',
              textAlign: 'center',
            }}
          >
            <div
              style={{
                width: '48px',
                height: '48px',
                borderRadius: '50%',
                background: 'rgba(239, 68, 68, 0.15)',
                border: '1px solid rgba(239, 68, 68, 0.4)',
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: '#f87171',
                marginBottom: '14px',
              }}
            >
              <ShieldAlert size={26} />
            </div>

            <h3
              style={{
                margin: '0 0 8px',
                fontSize: '18px',
                fontWeight: 800,
                color: '#ffffff',
              }}
            >
              Leave Active Game?
            </h3>

            <p
              style={{
                margin: '0 0 20px',
                fontSize: '13px',
                color: '#94a3b8',
                lineHeight: 1.5,
              }}
            >
              The hand is currently in progress.
              <br />
              Leaving now will result in an immediate forfeit with{' '}
              <strong style={{ color: '#fca5a5' }}>maximum penalty (80 points)</strong>.
            </p>

            <div style={{ display: 'flex', gap: '10px' }}>
              <button
                type="button"
                className="btn-primary"
                onClick={() => setConfirmLeaveOpen(false)}
                style={{ flex: 1.2, padding: '10px', fontSize: '13px' }}
              >
                Resume Game
              </button>
              <button
                type="button"
                className="btn-danger"
                onClick={() => {
                  setConfirmLeaveOpen(false);
                  handleLeaveTable();
                }}
                style={{
                  flex: 1,
                  padding: '10px',
                  fontSize: '13px',
                  fontWeight: 800,
                  background: 'linear-gradient(135deg, #ef4444 0%, #b91c1c 100%)',
                }}
              >
                Leave Anyway
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
