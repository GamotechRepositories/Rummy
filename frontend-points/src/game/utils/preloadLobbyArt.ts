/**
 * Lobby art: the game picker cards, lobby background and logo.
 * Decoded once and held for the life of the page, so coming back from a table
 * shows them instantly instead of re-downloading and re-decoding.
 */
export const LOBBY_VARIANT_ART = {
  POINTS: '/variant-points.webp',
  POOL: '/variant-pool.webp',
  DEALS: '/variant-deals.webp',
  RUMMY_21: '/variant-21card.webp',
} as const;

const LOBBY_IMAGES = [
  ...Object.values(LOBBY_VARIANT_ART),
  '/mode-tournaments.webp',
  '/mode-private-room.webp',
  '/lobby-bg.webp',
  '/image.webp',
];

/** Live references keep the decoded images in the browser's memory cache. */
const held: HTMLImageElement[] = [];

export function preloadLobbyArt(): void {
  if (held.length > 0) return;
  for (const src of LOBBY_IMAGES) {
    const img = new Image();
    img.decoding = 'async';
    img.src = src;
    img.decode?.().catch(() => undefined);
    held.push(img);
  }
}
