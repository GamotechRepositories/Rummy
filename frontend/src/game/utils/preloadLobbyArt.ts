/**
 * Lobby art: the game picker cards, lobby background and logo.
 * Decoded once and held for the life of the page, so coming back from a table
 * shows them instantly instead of re-downloading and re-decoding.
 */
export const LOBBY_VARIANT_ART = {
  POINTS: '/3cc9cf25-042c-4870-9b09-f7f07c2ddc39.jpg',
  POOL: '/7b9b7c96-a115-450d-ae27-568b5b8fbc89.jpg',
  DEALS: '/8a9db3f4-972d-4293-b2df-9c50601211ea.jpg',
  RUMMY_21: '/7ac1f003-0294-4d18-ae39-93b53258c895.jpg',
} as const;

const LOBBY_IMAGES = [
  ...Object.values(LOBBY_VARIANT_ART),
  '/7c3ceae1-a60f-477b-a534-8b4be01d90a3.jpg',
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
