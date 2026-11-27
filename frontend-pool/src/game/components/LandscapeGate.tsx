import React from 'react';
import { RefreshCw, Smartphone, RotateCw } from 'lucide-react';
import { useRequiresLandscape } from '../hooks/useRequiresLandscape';
import { soundEngine } from '../audio/soundEngine';

export interface LandscapeGateContextType {
  simulatedLandscape: boolean;
  isFlipped: boolean;
  toggleFlip: () => void;
}

export const LandscapeGateContext = React.createContext<LandscapeGateContextType>({
  simulatedLandscape: false,
  isFlipped: false,
  toggleFlip: () => {},
});

export const useLandscapeGate = () => React.useContext(LandscapeGateContext);

interface LandscapeGateProps {
  /** When false, landscape is not required (e.g. Choose Your Rummy hub). */
  enabled: boolean;
  /** When true, children render their own integrated flip control (e.g. table HUD) */
  hideFloatingBadge?: boolean;
  children: React.ReactNode;
}

/**
 * Real-rummy style mobile gate: blocks play screens in portrait until the
 * device is rotated to landscape (or forced via Enable landscape).
 */
export const LandscapeGate: React.FC<LandscapeGateProps> = ({
  enabled,
  hideFloatingBadge = false,
  children,
}) => {
  const {
    needsRotate,
    simulatedLandscape,
    isFlipped,
    toggleFlip,
    requestLandscape,
  } = useRequiresLandscape(enabled);

  React.useEffect(() => {
    document.body.classList.toggle('force-landscape-screens', enabled);
    return () => document.body.classList.remove('force-landscape-screens');
  }, [enabled]);

  if (!enabled) {
    return <>{children}</>;
  }

  return (
    <LandscapeGateContext.Provider value={{ simulatedLandscape, isFlipped, toggleFlip }}>
      <div
        className={
          needsRotate
            ? 'landscape-gate-content is-blocked'
            : 'landscape-gate-content'
        }
        aria-hidden={needsRotate}
      >
        {children}
      </div>

      {simulatedLandscape && !hideFloatingBadge && (
        <button
          type="button"
          className="simulated-flip-badge"
          onClick={(e) => {
            e.stopPropagation();
            try {
              soundEngine.play('click');
            } catch {
              // ignore audio error
            }
            toggleFlip();
          }}
          title="Flip orientation 180°"
          aria-label="Flip screen 180 degrees"
        >
          <RotateCw size={13} />
          <span>Flip 180°</span>
        </button>
      )}

      {needsRotate && (
        <div
          className="rotate-device-overlay is-visible"
          role="dialog"
          aria-modal="true"
          aria-labelledby="rotate-title"
        >
          <div className="rotate-device-card">
            <span className="rotate-device-suit rotate-device-suit--tl" aria-hidden>♠</span>
            <span className="rotate-device-suit rotate-device-suit--tr" aria-hidden>♦</span>

            <div className="rotate-device-icon-wrap" aria-hidden>
              <div className="rotate-device-crest-glow" />
              <div className="rotate-device-crest">
                <Smartphone className="rotate-device-phone" size={34} strokeWidth={1.75} />
              </div>
              <span className="rotate-device-arrow-badge">
                <RefreshCw className="rotate-device-arrow" size={14} strokeWidth={2.5} />
              </span>
            </div>

            <span className="rotate-device-kicker">LANDSCAPE MODE</span>
            <h2 id="rotate-title" className="rotate-device-title">
              Rotate Your Phone
            </h2>
            <p className="rotate-device-copy">
              Turn your device sideways or tap below to play in widescreen view.
            </p>

            <button
              type="button"
              className="rotate-device-btn"
              onClick={() => {
                try {
                  soundEngine.play('click');
                } catch {
                  // ignore audio error
                }
                void requestLandscape();
              }}
            >
              <RotateCw size={16} strokeWidth={2.5} />
              <span>Enable Landscape</span>
            </button>
          </div>
        </div>
      )}
    </LandscapeGateContext.Provider>
  );
};

