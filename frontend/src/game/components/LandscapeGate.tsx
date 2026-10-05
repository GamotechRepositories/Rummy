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
            <div className="rotate-device-icon-wrap" aria-hidden>
              <Smartphone className="rotate-device-phone" size={56} strokeWidth={1.6} />
              <RefreshCw className="rotate-device-arrow" size={22} strokeWidth={2.4} />
            </div>

            <h2 id="rotate-title" className="rotate-device-title">
              Rotate your phone
            </h2>

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
              Enable landscape
            </button>
          </div>
        </div>
      )}
    </LandscapeGateContext.Provider>
  );
};

