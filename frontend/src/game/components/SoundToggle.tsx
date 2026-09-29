import React, { useState } from 'react';
import { Volume2, VolumeX } from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';

export const SoundToggle: React.FC<{ compact?: boolean }> = ({ compact }) => {
  const [muted, setMuted] = useState(soundEngine.isMuted());

  return (
    <button
      type="button"
      className="btn-secondary"
      title={muted ? 'Unmute sounds' : 'Mute sounds'}
      aria-label={muted ? 'Unmute' : 'Mute'}
      onClick={() => {
        soundEngine.unlock();
        const next = soundEngine.toggleMute();
        setMuted(next);
      }}
      style={{
        padding: compact ? '4px 8px' : '6px 10px',
        fontSize: 12,
      }}
    >
      {muted ? <VolumeX size={compact ? 14 : 16} /> : <Volume2 size={compact ? 14 : 16} />}
      {!compact && <span>{muted ? 'Muted' : 'Sound'}</span>}
    </button>
  );
};
