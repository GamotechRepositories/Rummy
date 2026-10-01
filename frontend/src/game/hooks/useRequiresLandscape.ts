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

/**
 * Try locking orientation to landscape.
 * On mobile browsers (Chromium on Android), orientation.lock() requires the
 * document to be in fullscreen mode first.
 */
export async function tryLockLandscape(): Promise<boolean> {
  if (typeof window === 'undefined') return false;

  let locked = false;

  // Attempt screen orientation lock to landscape without requesting HTML5 fullscreen.
  // Note: Requesting HTML5 fullscreen triggers the intrusive Android OS prompt:
  // "to exit full screen, drag from the top and touch the back button".
  try {
    const orientation = screen.orientation as ScreenOrientation & {
      lock?: (orientation: string) => Promise<void>;
    };
    if (orientation && typeof orientation.lock === 'function') {
      await orientation.lock('landscape');
      locked = true;
    } else if ((screen as any).lockOrientation) {
      locked = Boolean((screen as any).lockOrientation('landscape'));
    } else if ((screen as any).webkitLockOrientation) {
      locked = Boolean((screen as any).webkitLockOrientation('landscape'));
    } else if ((screen as any).mozLockOrientation) {
      locked = Boolean((screen as any).mozLockOrientation('landscape'));
    }
  } catch (err) {
    // If native lock is rejected without fullscreen, orientation lock simply won't run,
    // and our seamless CSS simulated-landscape handles the layout without any intrusive OS toast.
  }

  return locked;
}

export function useRequiresLandscape(enabled: boolean) {
  const [needsRotate, setNeedsRotate] = useState(false);
  const [isMobile, setIsMobile] = useState(false);
  const [simulatedLandscape, setSimulatedLandscape] = useState(false);
  const [isFlipped, setIsFlipped] = useState(false);

  const recompute = useCallback(() => {
    if (!enabled) {
      setNeedsRotate(false);
      setSimulatedLandscape(false);
      return;
    }

    const compact = isCompactPlayViewport();
    const mobileLike = isLikelyTouchOrPhone();
    setIsMobile(mobileLike);

    const currentlyPortrait = isPortrait();

    // If native landscape is active, clear simulated landscape
    if (!currentlyPortrait) {
      setSimulatedLandscape(false);
      setNeedsRotate(false);
      return;
    }

    // If already in simulated landscape (user tapped Enable landscape with auto-rotate off),
    // don't show the gate
    if (simulatedLandscape) {
      setNeedsRotate(false);
      return;
    }

    // Require landscape for play screens whenever viewport is portrait + compact
    setNeedsRotate(Boolean(compact));
  }, [enabled, simulatedLandscape]);

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
      Boolean(enabled && isMobile && (!needsRotate || simulatedLandscape))
    );
    document.body.classList.toggle('simulated-landscape', simulatedLandscape);
    document.body.classList.toggle('simulated-flipped', Boolean(simulatedLandscape && isFlipped));

    return () => {
      document.body.classList.remove('landscape-gate-active');
      document.body.classList.remove('mobile-landscape-play');
      document.body.classList.remove('simulated-landscape');
      document.body.classList.remove('simulated-flipped');
    };
  }, [needsRotate, enabled, isMobile, simulatedLandscape, isFlipped]);

  const toggleFlip = useCallback(() => {
    setIsFlipped((prev) => !prev);
  }, []);

  const requestLandscape = useCallback(async () => {
    // 1. Try native fullscreen + orientation lock
    await tryLockLandscape();

    // 2. Wait a tick for browser window resize/orientationchange
    await new Promise((resolve) => setTimeout(resolve, 180));

    // 3. Check if native landscape resolved
    if (!isPortrait()) {
      setSimulatedLandscape(false);
      setNeedsRotate(false);
      return;
    }

    // 4. Fallback for Auto-Rotate OFF / iOS / unsupported lock:
    // Force landscape mode via CSS virtual landscape so user can play immediately!
    setSimulatedLandscape(true);
    setNeedsRotate(false);
  }, []);

  return {
    needsRotate,
    isMobile,
    simulatedLandscape,
    isFlipped,
    toggleFlip,
    requestLandscape,
  };
}

