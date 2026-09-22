import { useCallback, useEffect, useState } from 'react';

function isPortrait(): boolean {
  if (typeof window === 'undefined') return false;
  // Prefer CSS orientation, fall back to geometry (DevTools responsive mode)
  if (window.matchMedia('(orientation: portrait)').matches) return true;
  return window.innerHeight > window.innerWidth + 24;
}

/**
 * Phone / narrow tablet play surface — NOT only mobile UA.
 * Desktop DevTools device mode + real phones both match.
 */
function isCompactPlayViewport(): boolean {
  if (typeof window === 'undefined') return false;
  const w = window.innerWidth;
  const h = window.innerHeight;
  const shortSide = Math.min(w, h);
  // Typical phone short side, or portrait width under tablet breakpoint
  return shortSide <= 720 || (w <= 900 && h > w);
}

function isLikelyTouchOrPhone(): boolean {
  if (typeof window === 'undefined') return false;
  const ua = /Android|iPhone|iPad|iPod|Mobile|webOS|BlackBerry|IEMobile|Opera Mini/i.test(
    navigator.userAgent
  );
  const coarse = window.matchMedia('(pointer: coarse)').matches;
  return ua || coarse || isCompactPlayViewport();
}

/** Call from a user gesture (e.g. Play / variant tap) for best lock success rate. */
export async function tryLockLandscape(): Promise<void> {
  try {
    const docEl = document.documentElement as HTMLElement & {
      webkitRequestFullscreen?: () => Promise<void> | void;
    };
    if (!document.fullscreenElement) {
      if (docEl.requestFullscreen) {
        await docEl.requestFullscreen();
      } else if (docEl.webkitRequestFullscreen) {
        await docEl.webkitRequestFullscreen();
      }
    }
  } catch {
    // ignore
  }
  try {
    const orientation = screen.orientation as ScreenOrientation & {
      lock?: (orientation: string) => Promise<void>;
    };
    if (orientation?.lock) {
      await orientation.lock('landscape');
    }
  } catch {
    // ignore — overlay will prompt manual rotate
  }
}

export function useRequiresLandscape(enabled: boolean) {
  const [needsRotate, setNeedsRotate] = useState(false);
  const [isMobile, setIsMobile] = useState(false);

  const recompute = useCallback(() => {
    const compact = isCompactPlayViewport();
    const mobileLike = isLikelyTouchOrPhone();
    setIsMobile(mobileLike);
    // Require landscape for play screens whenever viewport is portrait + compact
    setNeedsRotate(Boolean(enabled && isPortrait() && compact));
  }, [enabled]);

  useEffect(() => {
    recompute();
    const onChange = () => recompute();
    window.addEventListener('resize', onChange);
    window.addEventListener('orientationchange', onChange);
    window.visualViewport?.addEventListener('resize', onChange);
    const mqPortrait = window.matchMedia('(orientation: portrait)');
    const mqCoarse = window.matchMedia('(pointer: coarse)');
    mqPortrait.addEventListener?.('change', onChange);
    mqCoarse.addEventListener?.('change', onChange);
    return () => {
      window.removeEventListener('resize', onChange);
      window.removeEventListener('orientationchange', onChange);
      window.visualViewport?.removeEventListener('resize', onChange);
      mqPortrait.removeEventListener?.('change', onChange);
      mqCoarse.removeEventListener?.('change', onChange);
    };
  }, [recompute]);

  useEffect(() => {
    document.body.classList.toggle('landscape-gate-active', needsRotate);
    document.body.classList.toggle(
      'mobile-landscape-play',
      Boolean(enabled && isMobile && !needsRotate)
    );
    return () => {
      document.body.classList.remove('landscape-gate-active');
      document.body.classList.remove('mobile-landscape-play');
    };
  }, [needsRotate, enabled, isMobile]);

  const requestLandscape = useCallback(async () => {
    await tryLockLandscape();
    // Give iOS a tick after orientationchange
    window.setTimeout(recompute, 120);
    recompute();
  }, [recompute]);

  return { needsRotate, isMobile, requestLandscape };
}
