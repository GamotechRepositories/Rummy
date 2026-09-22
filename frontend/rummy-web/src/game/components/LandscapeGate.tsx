import React from 'react';
import { RefreshCw, Smartphone } from 'lucide-react';
import { useRequiresLandscape } from '../hooks/useRequiresLandscape';
import { soundEngine } from '../audio/soundEngine';

interface LandscapeGateProps {
  /** When false, landscape is not required (e.g. Choose Your Rummy hub). */
  enabled: boolean;
  children: React.ReactNode;
}

/**
 * Real-rummy style mobile gate: blocks play screens in portrait until the
 * device is rotated to landscape (optional Screen Orientation lock).
 *
 * Visibility = React detection OR CSS media-query backup (DevTools / odd UAs).
 */
export const LandscapeGate: React.FC<LandscapeGateProps> = ({ enabled, children }) => {
  const { needsRotate, requestLandscape } = useRequiresLandscape(enabled);

  React.useEffect(() => {
    document.body.classList.toggle('force-landscape-screens', enabled);
    return () => document.body.classList.remove('force-landscape-screens');
  }, [enabled]);

  if (!enabled) {
    return <>{children}</>;
  }

  return (
    <>
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

      <div
        className={
          needsRotate
            ? 'rotate-device-overlay is-visible'
            : 'rotate-device-overlay rotate-device-overlay--media'
        }
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
          <p className="rotate-device-copy">
            This table is built for landscape — like real online rummy. Turn your device sideways to
            continue.
          </p>

          <button
            type="button"
            className="rotate-device-btn"
            onClick={() => {
              soundEngine.play('click');
              void requestLandscape();
            }}
          >
            Enable landscape
          </button>
          <p className="rotate-device-hint">
            Tap the button to allow rotation lock, or rotate the phone yourself.
          </p>
        </div>
      </div>
    </>
  );
};
