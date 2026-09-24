/**
 * Public table art only. Must match the background URL on `.casino-table-stage`.
 * Fetched during matchmaking so the felt is already cached when the table opens.
 * Does not join a table or request any game state.
 */
const TABLE_SHELL_IMAGES = ['/table-board.png?v=071656'] as const;

export function preloadTableShell(): void {
  for (const src of TABLE_SHELL_IMAGES) {
    const img = new Image();
    img.decoding = 'async';
    img.src = src;
  }
}
