import React from 'react';

export interface AvatarProfile {
  id: string;
  name: string;
  vipTier: string;
  bgGradient: string;
  borderColor: string;
  photo?: string;
  renderSvg: (size?: number) => React.ReactNode;
}

// 8 Distinct, High-end Stylized Casino Characters
export const AVATAR_CHARACTERS: AvatarProfile[] = [
  {
    id: 'maharaja',
    name: 'Maharaja',
    vipTier: 'VIP 5',
    bgGradient: 'linear-gradient(135deg, #78350f 0%, #b45309 50%, #d97706 100%)',
    borderColor: '#fbbf24',
    renderSvg: (size = 56) => (
      <svg width={size} height={size} viewBox="0 0 100 100" fill="none" xmlns="http://www.w3.org/2000/svg">
        <circle cx="50" cy="50" r="48" fill="url(#bg-maharaja)" />
        <defs>
          <radialGradient id="bg-maharaja" cx="50%" cy="30%" r="60%">
            <stop offset="0%" stopColor="#d97706" />
            <stop offset="100%" stopColor="#451a03" />
          </radialGradient>
        </defs>
        {/* Turban / Pheta */}
        <ellipse cx="50" cy="32" rx="30" ry="18" fill="#b91c1c" />
        <path d="M22 36C24 20 40 14 50 14C60 14 76 20 78 36C70 42 30 42 22 36Z" fill="#dc2626" />
        <ellipse cx="50" cy="22" rx="6" ry="6" fill="#fbbf24" />
        <path d="M50 16L50 8" stroke="#fef08a" strokeWidth="2.5" strokeLinecap="round" />
        <ellipse cx="50" cy="8" rx="2" ry="4" fill="#38bdf8" />
        {/* Face */}
        <ellipse cx="50" cy="54" rx="22" ry="24" fill="#fcd34d" />
        {/* Royal Sherwani Collar */}
        <path d="M24 88C24 74 36 68 50 68C64 68 76 74 76 88C68 96 32 96 24 88Z" fill="#1e1b4b" />
        <path d="M50 68L50 96" stroke="#fbbf24" strokeWidth="3" />
        <path d="M38 72L50 82L62 72" stroke="#f59e0b" strokeWidth="2" fill="none" />
        {/* Eyes & Eyebrows */}
        <path d="M38 48C40 46 44 46 46 48" stroke="#1c1917" strokeWidth="2" strokeLinecap="round" />
        <path d="M54 48C56 46 60 46 62 48" stroke="#1c1917" strokeWidth="2" strokeLinecap="round" />
        <circle cx="42" cy="52" r="2.5" fill="#1c1917" />
        <circle cx="58" cy="52" r="2.5" fill="#1c1917" />
        {/* Royal Mustache */}
        <path d="M42 62C46 62 48 59 50 61C52 59 54 62 58 62C64 62 67 56 68 54C64 56 60 59 55 58C53 58 52 58 50 59C48 58 47 58 45 58C40 59 36 56 32 54C33 56 36 62 42 62Z" fill="#1c1917" />
        {/* Earring */}
        <circle cx="28" cy="56" r="2" fill="#fbbf24" />
        <circle cx="72" cy="56" r="2" fill="#fbbf24" />
      </svg>
    ),
  },
  {
    id: 'card_shark',
    name: 'Card Shark',
    vipTier: 'PRO',
    bgGradient: 'linear-gradient(135deg, #064e3b 0%, #047857 50%, #10b981 100%)',
    borderColor: '#34d399',
    renderSvg: (size = 56) => (
      <svg width={size} height={size} viewBox="0 0 100 100" fill="none" xmlns="http://www.w3.org/2000/svg">
        <circle cx="50" cy="50" r="48" fill="url(#bg-cardshark)" />
        <defs>
          <radialGradient id="bg-cardshark" cx="50%" cy="30%" r="60%">
            <stop offset="0%" stopColor="#059669" />
            <stop offset="100%" stopColor="#022c22" />
          </radialGradient>
        </defs>
        {/* Tuxedo Body */}
        <path d="M22 92C22 74 34 68 50 68C66 68 78 74 78 92Z" fill="#0f172a" />
        <path d="M42 68L50 82L58 68" fill="#ffffff" />
        <polygon points="50,78 46,74 54,74" fill="#dc2626" />
        <polygon points="50,78 46,82 54,82" fill="#dc2626" />
        {/* Face */}
        <ellipse cx="50" cy="50" rx="20" ry="22" fill="#fde047" />
        {/* Slicked Dark Hair */}
        <path d="M28 42C28 28 38 22 50 22C62 22 72 28 72 42C70 32 60 26 50 26C40 26 30 32 28 42Z" fill="#1e293b" />
        {/* Sunglasses */}
        <rect x="33" y="44" width="15" height="11" rx="3" fill="#020617" stroke="#fbbf24" strokeWidth="1.5" />
        <rect x="52" y="44" width="15" height="11" rx="3" fill="#020617" stroke="#fbbf24" strokeWidth="1.5" />
        <line x1="48" y1="48" x2="52" y2="48" stroke="#fbbf24" strokeWidth="2" />
        <line x1="35" y1="46" x2="42" y2="52" stroke="#64748b" strokeWidth="1.2" strokeLinecap="round" />
        <line x1="54" y1="46" x2="61" y2="52" stroke="#64748b" strokeWidth="1.2" strokeLinecap="round" />
        {/* Smirk */}
        <path d="M46 62C48 64 54 64 56 62" stroke="#713f12" strokeWidth="2" strokeLinecap="round" />
      </svg>
    ),
  },
  {
    id: 'diva',
    name: 'Bollywood Diva',
    vipTier: 'VIP 4',
    bgGradient: 'linear-gradient(135deg, #831843 0%, #be185d 50%, #f472b6 100%)',
    borderColor: '#f472b6',
    renderSvg: (size = 56) => (
      <svg width={size} height={size} viewBox="0 0 100 100" fill="none" xmlns="http://www.w3.org/2000/svg">
        <circle cx="50" cy="50" r="48" fill="url(#bg-diva)" />
        <defs>
          <radialGradient id="bg-diva" cx="50%" cy="30%" r="60%">
            <stop offset="0%" stopColor="#db2777" />
            <stop offset="100%" stopColor="#500724" />
          </radialGradient>
        </defs>
        {/* Hair Back */}
        <ellipse cx="50" cy="52" rx="28" ry="30" fill="#18181b" />
        {/* Saree / Blouse */}
        <path d="M24 92C24 74 36 68 50 68C64 68 76 74 76 92Z" fill="#9d174d" />
        <path d="M36 70L66 94" stroke="#fbbf24" strokeWidth="4" />
        <circle cx="50" cy="68" r="4" fill="#fbbf24" />
        {/* Face */}
        <ellipse cx="50" cy="48" rx="18" ry="21" fill="#fde68a" />
        {/* Hair Front */}
        <path d="M30 42C32 26 42 22 50 22C58 22 68 26 70 42C64 30 58 30 50 32C42 30 36 30 30 42Z" fill="#18181b" />
        {/* Bindi */}
        <circle cx="50" cy="38" r="2" fill="#dc2626" />
        {/* Eyes & Eyelashes */}
        <ellipse cx="42" cy="46" rx="3.5" ry="2.5" fill="#18181b" />
        <ellipse cx="58" cy="46" rx="3.5" ry="2.5" fill="#18181b" />
        <path d="M37 44L44 42" stroke="#18181b" strokeWidth="1.5" />
        <path d="M63 44L56 42" stroke="#18181b" strokeWidth="1.5" />
        {/* Lips */}
        <path d="M45 58C47 60 53 60 55 58" stroke="#be123c" strokeWidth="2.5" strokeLinecap="round" />
        {/* Big Earrings */}
        <circle cx="30" cy="52" r="3" fill="#fbbf24" stroke="#f59e0b" strokeWidth="1" />
        <circle cx="70" cy="52" r="3" fill="#fbbf24" stroke="#f59e0b" strokeWidth="1" />
      </svg>
    ),
  },
  {
    id: 'pro_gamer',
    name: 'Pro Gamer',
    vipTier: 'ELITE',
    bgGradient: 'linear-gradient(135deg, #1e1b4b 0%, #3730a3 50%, #6366f1 100%)',
    borderColor: '#818cf8',
    renderSvg: (size = 56) => (
      <svg width={size} height={size} viewBox="0 0 100 100" fill="none" xmlns="http://www.w3.org/2000/svg">
        <circle cx="50" cy="50" r="48" fill="url(#bg-progamer)" />
        <defs>
          <radialGradient id="bg-progamer" cx="50%" cy="30%" r="60%">
            <stop offset="0%" stopColor="#4f46e5" />
            <stop offset="100%" stopColor="#0f172a" />
          </radialGradient>
        </defs>
        {/* Hoodie */}
        <path d="M22 92C22 74 34 68 50 68C66 68 78 74 78 92Z" fill="#1e293b" />
        <path d="M40 70L50 84L60 70" stroke="#6366f1" strokeWidth="3" fill="#0f172a" />
        {/* Face */}
        <ellipse cx="50" cy="50" rx="19" ry="21" fill="#fed7aa" />
        {/* Modern Hair */}
        <path d="M30 40C30 26 40 22 54 20C64 22 70 30 70 40C66 32 58 30 48 30C38 30 32 34 30 40Z" fill="#475569" />
        {/* Eyes */}
        <circle cx="43" cy="48" r="2.5" fill="#0f172a" />
        <circle cx="57" cy="48" r="2.5" fill="#0f172a" />
        <path d="M47 58C49 60 53 60 55 58" stroke="#c2410c" strokeWidth="2" strokeLinecap="round" />
        {/* Gamer Headset */}
        <path d="M26 46C26 30 36 20 50 20C64 20 74 30 74 46" stroke="#38bdf8" strokeWidth="4" strokeLinecap="round" fill="none" />
        <rect x="22" y="44" width="7" height="14" rx="3" fill="#0284c7" stroke="#38bdf8" strokeWidth="1" />
        <rect x="71" y="44" width="7" height="14" rx="3" fill="#0284c7" stroke="#38bdf8" strokeWidth="1" />
        {/* Mic */}
        <path d="M26 56C26 64 36 68 44 66" stroke="#38bdf8" strokeWidth="2" strokeLinecap="round" fill="none" />
        <circle cx="44" cy="66" r="2.5" fill="#ef4444" />
      </svg>
    ),
  },
  {
    id: 'casino_boss',
    name: 'Casino Boss',
    vipTier: 'VIP 6',
    bgGradient: 'linear-gradient(135deg, #451a03 0%, #78350f 50%, #b45309 100%)',
    borderColor: '#f59e0b',
    renderSvg: (size = 56) => (
      <svg width={size} height={size} viewBox="0 0 100 100" fill="none" xmlns="http://www.w3.org/2000/svg">
        <circle cx="50" cy="50" r="48" fill="url(#bg-boss)" />
        <defs>
          <radialGradient id="bg-boss" cx="50%" cy="30%" r="60%">
            <stop offset="0%" stopColor="#92400e" />
            <stop offset="100%" stopColor="#1c1917" />
          </radialGradient>
        </defs>
        {/* Suit */}
        <path d="M22 92C22 74 34 68 50 68C66 68 78 74 78 92Z" fill="#18181b" />
        <path d="M42 68L50 86L58 68" fill="#f8fafc" />
        <line x1="50" y1="74" x2="50" y2="88" stroke="#b91c1c" strokeWidth="3" />
        {/* Face */}
        <ellipse cx="50" cy="48" rx="20" ry="21" fill="#fcd34d" />
        {/* Graying Hair */}
        <path d="M28 42C28 26 38 20 50 20C62 20 72 26 72 42C68 30 60 26 50 26C40 26 32 30 28 42Z" fill="#94a3b8" />
        {/* Full Beard */}
        <path d="M32 50C32 68 40 74 50 74C60 74 68 68 68 50C64 54 60 56 50 56C40 56 36 54 32 50Z" fill="#475569" />
        {/* Spectacles */}
        <circle cx="43" cy="46" r="6" stroke="#fbbf24" strokeWidth="1.5" fill="rgba(255,255,255,0.15)" />
        <circle cx="57" cy="46" r="6" stroke="#fbbf24" strokeWidth="1.5" fill="rgba(255,255,255,0.15)" />
        <line x1="49" y1="46" x2="51" y2="46" stroke="#fbbf24" strokeWidth="1.5" />
        <circle cx="43" cy="46" r="2" fill="#0f172a" />
        <circle cx="57" cy="46" r="2" fill="#0f172a" />
      </svg>
    ),
  },
  {
    id: 'ace_queen',
    name: 'Ace Queen',
    vipTier: 'MASTER',
    bgGradient: 'linear-gradient(135deg, #581c87 0%, #7e22ce 50%, #a855f7 100%)',
    borderColor: '#c084fc',
    renderSvg: (size = 56) => (
      <svg width={size} height={size} viewBox="0 0 100 100" fill="none" xmlns="http://www.w3.org/2000/svg">
        <circle cx="50" cy="50" r="48" fill="url(#bg-acequeen)" />
        <defs>
          <radialGradient id="bg-acequeen" cx="50%" cy="30%" r="60%">
            <stop offset="0%" stopColor="#9333ea" />
            <stop offset="100%" stopColor="#2e1065" />
          </radialGradient>
        </defs>
        {/* Leather Jacket */}
        <path d="M22 92C22 74 34 68 50 68C66 68 78 74 78 92Z" fill="#3b0764" />
        <path d="M42 68L50 82L58 68" fill="#f8fafc" />
        {/* Face */}
        <ellipse cx="50" cy="48" rx="19" ry="21" fill="#fde047" />
        {/* Sleek Asymmetrical Hair */}
        <path d="M28 46C28 26 40 20 54 20C68 20 74 30 74 54C70 42 66 32 50 32C38 32 30 38 28 46Z" fill="#172554" />
        {/* Cat-eye Shades */}
        <polygon points="34,44 48,46 46,54 36,52" fill="#1e1b4b" stroke="#fbbf24" strokeWidth="1" />
        <polygon points="66,44 52,46 54,54 64,52" fill="#1e1b4b" stroke="#fbbf24" strokeWidth="1" />
        <line x1="48" y1="46" x2="52" y2="46" stroke="#fbbf24" strokeWidth="1.5" />
        {/* Lips */}
        <path d="M45 60C47 62 53 62 55 60" stroke="#9333ea" strokeWidth="2.5" strokeLinecap="round" />
        {/* Crown Hairpin */}
        <path d="M60 24L63 20L66 24L69 20L72 24" stroke="#fbbf24" strokeWidth="2" fill="none" />
      </svg>
    ),
  },
  {
    id: 'maverick',
    name: 'Maverick',
    vipTier: 'PRO',
    bgGradient: 'linear-gradient(135deg, #1c1917 0%, #44403c 50%, #78716c 100%)',
    borderColor: '#e7e5e4',
    renderSvg: (size = 56) => (
      <svg width={size} height={size} viewBox="0 0 100 100" fill="none" xmlns="http://www.w3.org/2000/svg">
        <circle cx="50" cy="50" r="48" fill="url(#bg-maverick)" />
        <defs>
          <radialGradient id="bg-maverick" cx="50%" cy="30%" r="60%">
            <stop offset="0%" stopColor="#57534e" />
            <stop offset="100%" stopColor="#0c0a09" />
          </radialGradient>
        </defs>
        {/* Leather Bomber */}
        <path d="M22 92C22 74 34 68 50 68C66 68 78 74 78 92Z" fill="#292524" />
        <circle cx="50" cy="80" r="4" fill="#fbbf24" />
        {/* Face */}
        <ellipse cx="50" cy="48" rx="19" ry="21" fill="#fed7aa" />
        {/* Spiky Hair */}
        <path d="M30 38L34 26L40 32L46 22L52 30L60 22L66 32L70 38C64 30 56 28 50 28C42 28 34 32 30 38Z" fill="#1c1917" />
        {/* Eyebrow slit */}
        <path d="M37 44L45 42" stroke="#1c1917" strokeWidth="2" strokeLinecap="round" />
        <path d="M55 42L63 44" stroke="#1c1917" strokeWidth="2" strokeLinecap="round" />
        <circle cx="43" cy="48" r="2.5" fill="#1c1917" />
        <circle cx="57" cy="48" r="2.5" fill="#1c1917" />
        {/* Confident Smile */}
        <path d="M46 58C48 62 54 62 56 58" stroke="#9a3412" strokeWidth="2" strokeLinecap="round" />
      </svg>
    ),
  },
  {
    id: 'high_roller',
    name: 'High Roller',
    vipTier: 'VIP 7',
    bgGradient: 'linear-gradient(135deg, #1e3a8a 0%, #1d4ed8 50%, #3b82f6 100%)',
    borderColor: '#60a5fa',
    renderSvg: (size = 56) => (
      <svg width={size} height={size} viewBox="0 0 100 100" fill="none" xmlns="http://www.w3.org/2000/svg">
        <circle cx="50" cy="50" r="48" fill="url(#bg-roller)" />
        <defs>
          <radialGradient id="bg-roller" cx="50%" cy="30%" r="60%">
            <stop offset="0%" stopColor="#2563eb" />
            <stop offset="100%" stopColor="#0f172a" />
          </radialGradient>
        </defs>
        {/* Royal Blazer */}
        <path d="M22 92C22 74 34 68 50 68C66 68 78 74 78 92Z" fill="#1e1b4b" />
        <path d="M42 68L50 82L58 68" fill="#fbbf24" />
        {/* Face */}
        <ellipse cx="50" cy="48" rx="20" ry="21" fill="#fde047" />
        {/* Dark Classic Hair with Fedora / Cap */}
        <ellipse cx="50" cy="30" rx="28" ry="12" fill="#0f172a" />
        <path d="M32 30C34 18 42 14 50 14C58 14 66 18 68 30Z" fill="#1e293b" />
        <line x1="32" y1="30" x2="68" y2="30" stroke="#f59e0b" strokeWidth="2" />
        {/* Eyes */}
        <circle cx="43" cy="46" r="2.5" fill="#0f172a" />
        <circle cx="57" cy="46" r="2.5" fill="#0f172a" />
        {/* Smile */}
        <path d="M45 56C48 59 52 59 55 56" stroke="#854d0e" strokeWidth="2" strokeLinecap="round" />
      </svg>
    ),
  },
];

const BOY_PHOTOS = Array.from(
  { length: 15 },
  (_, i) => `/avatars/boys/boy-${String(i + 1).padStart(2, '0')}.png`
);
const GIRL_PHOTOS = Array.from(
  { length: 15 },
  (_, i) => `/avatars/girls/girl-${String(i + 1).padStart(2, '0')}.png`
);

const BOY_FIRST = new Set([
  'aarav', 'vivaan', 'aditya', 'vihaan', 'arjun', 'sai', 'reyansh', 'ayaan', 'krishna', 'ishaan',
  'shaurya', 'atharv', 'advik', 'pranav', 'aryan', 'kabir', 'ansh', 'rudra', 'yuvaan', 'dhruv',
  'kartik', 'rohan', 'kunal', 'nikhil', 'rahul', 'amit', 'suresh', 'vikram', 'rajesh', 'sanjay',
]);

const GIRL_FIRST = new Set([
  'ananya', 'aadhya', 'diya', 'pari', 'anika', 'navya', 'myra', 'sara', 'aisha', 'kiara',
  'isha', 'riya', 'saanvi', 'aarohi', 'meera', 'kavya', 'nisha', 'pooja', 'priya', 'neha',
]);

function hashIdentifier(identifier: string): number {
  let hash = 0;
  for (let i = 0; i < identifier.length; i++) {
    hash = (hash << 5) - hash + identifier.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash);
}

export type PlayerCharacter = {
  id: string;
  photo: string;
  group: 'boy' | 'girl';
};

function characterList(group: 'boy' | 'girl'): PlayerCharacter[] {
  const folder = group === 'boy' ? 'boys' : 'girls';
  const prefix = group === 'boy' ? 'boy' : 'girl';
  return Array.from({ length: 15 }, (_, i) => {
    const n = String(i + 1).padStart(2, '0');
    const id = `${prefix}-${n}`;
    return { id, photo: `/avatars/${folder}/${id}.png`, group };
  });
}

export const PLAYER_CHARACTERS: PlayerCharacter[] = [
  ...characterList('boy'),
  ...characterList('girl'),
];

const AVATAR_KEY = 'rummy_avatar_id';

export function isCharacterId(id: string | null | undefined): id is string {
  return !!id && PLAYER_CHARACTERS.some((c) => c.id === id);
}

export function photoForCharacter(id: string | null | undefined): string {
  return PLAYER_CHARACTERS.find((c) => c.id === id)?.photo ?? PLAYER_CHARACTERS[0].photo;
}

/** First visit picks one face at random and keeps it until the player changes it. */
export function readOrCreateAvatarId(): string {
  try {
    const saved = localStorage.getItem(AVATAR_KEY);
    if (isCharacterId(saved)) return saved;
  } catch {
    // ignore
  }
  const pick = PLAYER_CHARACTERS[Math.floor(Math.random() * PLAYER_CHARACTERS.length)].id;
  try {
    localStorage.setItem(AVATAR_KEY, pick);
  } catch {
    // ignore
  }
  return pick;
}

export function persistAvatarId(id: string): void {
  if (!isCharacterId(id)) return;
  try {
    localStorage.setItem(AVATAR_KEY, id);
  } catch {
    // ignore
  }
}

/**
 * Opposite seats use a real photo. Boys and girls are separate pools of 15.
 * The same name always gets the same face, and a boy's name never gets a girl's photo.
 */
export function getAvatarForPlayer(identifier?: string | null): AvatarProfile {
  const id = identifier?.trim() || 'player';
  const hash = hashIdentifier(id);
  const first = id.split(/\s+/)[0]?.toLowerCase() ?? '';
  const girl = GIRL_FIRST.has(first) || (!BOY_FIRST.has(first) && hash % 2 === 1);
  const photos = girl ? GIRL_PHOTOS : BOY_PHOTOS;
  const character = AVATAR_CHARACTERS[hash % AVATAR_CHARACTERS.length];
  return { ...character, photo: photos[hash % photos.length] };
}
