import React, { useEffect, useState } from 'react';
import { Maximize2, Minimize2 } from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';

export interface FullscreenToggleProps {
  compact?: boolean;
  className?: string;
  style?: React.CSSProperties;
  showLabel?: boolean;
}

function isFullscreenActive(): boolean {
  if (typeof document === 'undefined') return false;
  const doc = document as Document & {
    webkitFullscreenElement?: Element;
    mozFullScreenElement?: Element;
    msFullscreenElement?: Element;
  };
  return Boolean(
    doc.fullscreenElement ||
    doc.webkitFullscreenElement ||
    doc.mozFullScreenElement ||
    doc.msFullscreenElement
  );
}

export async function toggleFullscreen(): Promise<boolean> {
  if (typeof document === 'undefined') return false;
  const doc = document as Document & {
    webkitFullscreenElement?: Element;
    mozFullScreenElement?: Element;
    msFullscreenElement?: Element;
    webkitExitFullscreen?: () => Promise<void> | void;
    mozCancelFullScreen?: () => Promise<void> | void;
    msExitFullscreen?: () => Promise<void> | void;
  };
  const docEl = document.documentElement as HTMLElement & {
    webkitRequestFullscreen?: () => Promise<void> | void;
    mozRequestFullScreen?: () => Promise<void> | void;
    msRequestFullscreen?: () => Promise<void> | void;
  };

  try {
    if (isFullscreenActive()) {
      if (doc.exitFullscreen) {
        await doc.exitFullscreen();
      } else if (doc.webkitExitFullscreen) {
        await doc.webkitExitFullscreen();
      } else if (doc.mozCancelFullScreen) {
        await doc.mozCancelFullScreen();
      } else if (doc.msExitFullscreen) {
        await doc.msExitFullscreen();
      }
      return false;
    } else {
      if (docEl.requestFullscreen) {
        await docEl.requestFullscreen();
      } else if (docEl.webkitRequestFullscreen) {
        await docEl.webkitRequestFullscreen();
      } else if (docEl.mozRequestFullScreen) {
        await docEl.mozRequestFullScreen();
      } else if (docEl.msRequestFullscreen) {
        await docEl.msRequestFullscreen();
      }
      return true;
    }
  } catch (err) {
    console.warn('Fullscreen toggle failed:', err);
    return isFullscreenActive();
  }
}

export const FullscreenToggle: React.FC<FullscreenToggleProps> = ({
  compact = true,
  className,
  style,
  showLabel = false,
}) => {
  const [isFullscreen, setIsFullscreen] = useState(isFullscreenActive);

  useEffect(() => {
    const handleFullscreenChange = () => {
      setIsFullscreen(isFullscreenActive());
    };

    document.addEventListener('fullscreenchange', handleFullscreenChange);
    document.addEventListener('webkitfullscreenchange', handleFullscreenChange);
    document.addEventListener('mozfullscreenchange', handleFullscreenChange);
    document.addEventListener('MSFullscreenChange', handleFullscreenChange);

    return () => {
      document.removeEventListener('fullscreenchange', handleFullscreenChange);
      document.removeEventListener('webkitfullscreenchange', handleFullscreenChange);
      document.removeEventListener('mozfullscreenchange', handleFullscreenChange);
      document.removeEventListener('MSFullscreenChange', handleFullscreenChange);
    };
  }, []);

  const handleClick = async () => {
    try {
      soundEngine.play('click');
    } catch {
      // ignore
    }
    await toggleFullscreen();
  };

  const title = isFullscreen ? 'Exit Fullscreen (Minimize)' : 'Enter Fullscreen';
  const label = isFullscreen ? 'Minimize' : 'Fullscreen';

  return (
    <button
      type="button"
      id="btn-fullscreen-toggle"
      className={className ?? 'btn-secondary'}
      title={title}
      aria-label={title}
      onClick={handleClick}
      style={{
        padding: compact ? '4px 8px' : '6px 10px',
        fontSize: 12,
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 6,
        ...style,
      }}
    >
      {isFullscreen ? (
        <Minimize2 size={compact ? 14 : 16} />
      ) : (
        <Maximize2 size={compact ? 14 : 16} />
      )}
      {showLabel && <span>{label}</span>}
    </button>
  );
};
