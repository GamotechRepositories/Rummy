import React from 'react';
import { useLandscapeGate } from '../components/LandscapeGate';

/**
 * Ensures smooth, reliable scrolling inside modal dialog cards, sheets, and pages on:
 * 1. iPhone Safari (translates touch gestures across 90° / 270° rotated simulated-landscape coordinates,
 *    and prevents iOS Safari rubber-band page cancellation).
 * 2. Desktop Browser & Mobile Chrome (translates mouse wheel & touch delta across all orientations).
 */
export function useModalScroll<T extends HTMLElement = HTMLDivElement>() {
  const { simulatedLandscape, isFlipped } = useLandscapeGate();
  const touchStart = React.useRef({ x: 0, y: 0, originX: 0, originY: 0, dragged: false, isTracking: false });
  const ref = React.useRef<T | null>(null);

  const handleScrollDelta = React.useCallback(
    (target: HTMLElement, rawDeltaX: number, rawDeltaY: number) => {
      let delta = 0;
      if (simulatedLandscape) {
        // Visual vertical axis is physical horizontal axis
        // When dragging up/swiping to scroll down, dx > 0
        delta = isFlipped ? -rawDeltaX : rawDeltaX;
      } else {
        // Native orientation or standard desktop browser
        delta = rawDeltaY;
      }

      if (delta !== 0 && target.scrollHeight > target.clientHeight) {
        target.scrollTop += delta;
        return true;
      }
      return false;
    },
    [simulatedLandscape, isFlipped]
  );

  // JSX Synthetic Event Handlers (fallback only when ref isn't attached and simulatedLandscape is active)
  const onTouchStart = React.useCallback((e: React.TouchEvent<HTMLElement>) => {
    if (ref.current || !simulatedLandscape || !e.touches[0]) return;
    const tx = e.touches[0].clientX;
    const ty = e.touches[0].clientY;
    touchStart.current = {
      x: tx,
      y: ty,
      originX: tx,
      originY: ty,
      dragged: false,
      isTracking: true,
    };
  }, [simulatedLandscape]);

  const onTouchMove = React.useCallback(
    (e: React.TouchEvent<HTMLElement>) => {
      if (ref.current || !simulatedLandscape) return;
      if (!touchStart.current.isTracking || !e.touches[0]) return;
      const target = e.currentTarget;
      if (!target) return;
      const touch = e.touches[0];
      if (!touchStart.current.dragged) {
        const totalDist = Math.hypot(
          touch.clientX - touchStart.current.originX,
          touch.clientY - touchStart.current.originY
        );
        if (totalDist <= 6) return;
        touchStart.current.dragged = true;
      }
      const dx = touchStart.current.x - touch.clientX;
      const dy = touchStart.current.y - touch.clientY;

      const scrolled = handleScrollDelta(target, dx, dy);
      if (scrolled) {
        touchStart.current.x = touch.clientX;
        touchStart.current.y = touch.clientY;
      }
    },
    [handleScrollDelta, simulatedLandscape]
  );

  const onTouchEnd = React.useCallback(() => {
    touchStart.current.isTracking = false;
    touchStart.current.dragged = false;
  }, []);

  const onWheel = React.useCallback(
    (e: React.WheelEvent<HTMLElement>) => {
      if (ref.current) return;
      const target = e.currentTarget;
      if (!target) return;
      // In desktop browser or simulated landscape, determine dominant wheel delta
      const dominantDelta = Math.abs(e.deltaY) >= Math.abs(e.deltaX) ? e.deltaY : e.deltaX;
      if (dominantDelta !== 0 && target.scrollHeight > target.clientHeight) {
        target.scrollTop += dominantDelta;
      }
    },
    []
  );

  // Active Native Listeners with passive: false only when simulatedLandscape rotates coordinates
  React.useEffect(() => {
    const el = ref.current;
    if (!el || !simulatedLandscape) return;

    let originX = 0;
    let originY = 0;
    let startX = 0;
    let dragged = false;
    let tracking = false;

    const nativeTouchStart = (e: TouchEvent) => {
      if (!e.touches[0]) return;
      originX = e.touches[0].clientX;
      originY = e.touches[0].clientY;
      startX = originX;
      dragged = false;
      tracking = true;
    };

    const nativeTouchMove = (e: TouchEvent) => {
      if (!tracking || !e.touches[0]) return;
      const touch = e.touches[0];
      if (!dragged) {
        if (Math.hypot(touch.clientX - originX, touch.clientY - originY) <= 6) {
          return;
        }
        dragged = true;
      }
      const dx = startX - touch.clientX;
      const delta = isFlipped ? -dx : dx;

      if (delta !== 0 && el.scrollHeight > el.clientHeight) {
        el.scrollTop += delta;
        startX = touch.clientX;
        if (e.cancelable) {
          e.preventDefault();
        }
      }
    };

    const nativeTouchEnd = () => {
      tracking = false;
      dragged = false;
    };

    const nativeWheel = (e: WheelEvent) => {
      const delta = Math.abs(e.deltaY) >= Math.abs(e.deltaX) ? e.deltaY : e.deltaX;
      if (delta !== 0 && el.scrollHeight > el.clientHeight) {
        el.scrollTop += delta;
        if (e.cancelable) {
          e.preventDefault();
        }
      }
    };

    el.addEventListener('touchstart', nativeTouchStart, { passive: true });
    el.addEventListener('touchmove', nativeTouchMove, { passive: false });
    el.addEventListener('touchend', nativeTouchEnd, { passive: true });
    el.addEventListener('touchcancel', nativeTouchEnd, { passive: true });
    el.addEventListener('wheel', nativeWheel, { passive: false });

    return () => {
      el.removeEventListener('touchstart', nativeTouchStart);
      el.removeEventListener('touchmove', nativeTouchMove);
      el.removeEventListener('touchend', nativeTouchEnd);
      el.removeEventListener('touchcancel', nativeTouchEnd);
      el.removeEventListener('wheel', nativeWheel);
    };
  }, [simulatedLandscape, isFlipped]);

  return { containerRef: ref, ref, onTouchStart, onTouchMove, onTouchEnd, onWheel };
}
