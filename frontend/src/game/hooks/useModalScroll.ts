import React from 'react';
import { useLandscapeGate } from '../components/LandscapeGate';

/**
 * Ensures smooth, reliable scrolling inside modal dialog cards and bodies on:
 * 1. iPhone (translates touch gestures across 90° / 270° rotated simulated-landscape coordinates)
 * 2. Desktop Browser (translates mouse wheel delta across rotated coordinate axes)
 */
export function useModalScroll() {
  const { simulatedLandscape, isFlipped } = useLandscapeGate();
  const touchStart = React.useRef({ x: 0, y: 0 });

  const onTouchStart = React.useCallback((e: React.TouchEvent<HTMLElement>) => {
    if (!e.touches[0]) return;
    touchStart.current = {
      x: e.touches[0].clientX,
      y: e.touches[0].clientY,
    };
  }, []);

  const onTouchMove = React.useCallback((e: React.TouchEvent<HTMLElement>) => {
    if (!e.touches[0]) return;
    const target = e.currentTarget;
    if (!target) return;
    const touch = e.touches[0];
    const dx = touchStart.current.x - touch.clientX;
    const dy = touchStart.current.y - touch.clientY;

    if (simulatedLandscape) {
      // In 90deg / 270deg rotation, visual vertical swipe corresponds to physical horizontal swipe
      const primaryDelta = Math.abs(dx) >= Math.abs(dy) ? (isFlipped ? -dx : dx) : dy;
      if (primaryDelta !== 0 && target.scrollHeight > target.clientHeight) {
        target.scrollTop += primaryDelta;
        touchStart.current = { x: touch.clientX, y: touch.clientY };
      }
    }
  }, [simulatedLandscape, isFlipped]);

  const onWheel = React.useCallback((e: React.WheelEvent<HTMLElement>) => {
    const target = e.currentTarget;
    if (!target) return;
    const delta = Math.abs(e.deltaY) >= Math.abs(e.deltaX) ? e.deltaY : e.deltaX;
    if (delta !== 0 && target.scrollHeight > target.clientHeight) {
      target.scrollTop += delta;
    }
  }, []);

  return { onTouchStart, onTouchMove, onWheel };
}
