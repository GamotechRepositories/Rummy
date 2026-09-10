import WebSocket from 'ws';

const WS_URL = process.env.WS_URL || 'ws://localhost:8081/ws/game';
const NUM_TABLES = parseInt(process.env.NUM_TABLES || '10', 10);
const PLAYERS_PER_TABLE = 2;

console.log(`\n===============================================================`);
console.log(`🃏 ROYAL RUMMY PLATFORM - LOAD SIMULATION HARNESS`);
console.log(`===============================================================`);
console.log(`Target WebSocket Gateway: ${WS_URL}`);
console.log(`Simulating Tables:       ${NUM_TABLES}`);
console.log(`Total Virtual Players:   ${NUM_TABLES * PLAYERS_PER_TABLE}`);
console.log(`===============================================================\n`);

let connectedClients = 0;
let totalMessagesSent = 0;
let totalMessagesReceived = 0;
let latencies = [];
let errorCount = 0;

class SimulatedPlayer {
  constructor(tableId, playerIndex) {
    this.tableId = tableId;
    this.playerId = `BOT_LOAD_${tableId}_${playerIndex}`;
    this.displayName = `SimPlayer ${playerIndex + 1}`;
    this.seatIndex = playerIndex;
    this.ws = null;
    this.myTurn = false;
    this.hand = [];
  }

  connect() {
    return new Promise((resolve, reject) => {
      try {
        const NativeWS = typeof WebSocket !== 'undefined' ? WebSocket : globalThis.WebSocket;
        this.ws = new NativeWS(WS_URL);

        this.ws.onopen = () => {
          connectedClients++;
          this.send('AUTH', { playerId: this.playerId });
          resolve();
        };

        this.ws.onmessage = (event) => {
          totalMessagesReceived++;
          try {
            const msg = JSON.parse(event.data);
            this.handleMessage(msg);
          } catch (e) {
            errorCount++;
          }
        };

        this.ws.onerror = (err) => {
          errorCount++;
        };

        this.ws.onclose = () => {
          connectedClients = Math.max(0, connectedClients - 1);
        };
      } catch (e) {
        errorCount++;
        reject(e);
      }
    });
  }

  send(type, payload = {}) {
    if (this.ws && this.ws.readyState === (typeof WebSocket !== 'undefined' ? WebSocket.OPEN : 1)) {
      const start = Date.now();
      const frame = {
        type,
        requestId: `req_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
        tableId: this.tableId,
        payload,
      };
      this.ws.send(JSON.stringify(frame));
      totalMessagesSent++;
      latencies.push(Date.now() - start);
    }
  }

  handleMessage(msg) {
    switch (msg.type) {
      case 'AUTH_SUCCESS':
        this.send('JOIN_TABLE', {
          playerId: this.playerId,
          displayName: this.displayName,
          seatIndex: this.seatIndex,
          isBot: false,
        });
        break;

      case 'GAME_VIEW':
        if (msg.payload) {
          const view = msg.payload;
          this.hand = view.hand || [];
          if (view.status === 'WAITING_FOR_PLAYERS') {
            this.send('READY');
          } else if (view.status === 'READY_TO_START' && this.seatIndex === 0) {
            this.send('START_GAME');
          } else if (view.status === 'IN_PROGRESS' && view.currentTurnPlayerId === this.playerId) {
            this.playTurn(view);
          }
        }
        break;
    }
  }

  playTurn(view) {
    if (this.turnInProgress) return;
    this.turnInProgress = true;

    setTimeout(() => {
      // Step 1: Draw from closed deck
      this.send('DRAW', { source: 'CLOSED_DECK' });

      setTimeout(() => {
        // Step 2: Discard arbitrary card
        if (this.hand && this.hand.length > 0) {
          const cardToDiscard = this.hand[this.hand.length - 1];
          const cardId = cardToDiscard.instanceId || cardToDiscard.id;
          if (cardId) {
            this.send('DISCARD', { cardInstanceId: cardId });
          }
        }
        this.turnInProgress = false;
      }, 50);
    }, 50);
  }

  disconnect() {
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }
}

async function runLoadSimulation() {
  const startTime = Date.now();
  const players = [];

  for (let t = 0; t < NUM_TABLES; t++) {
    const tableId = `LOAD_TBL_${t}_${Date.now().toString().slice(-4)}`;
    for (let p = 0; p < PLAYERS_PER_TABLE; p++) {
      const player = new SimulatedPlayer(tableId, p);
      players.push(player);
    }
  }

  console.log(`Connecting ${players.length} virtual clients...`);
  await Promise.all(players.map((p) => p.connect().catch(() => {})));
  console.log(`✅ All ${connectedClients} virtual clients connected successfully!`);

  // Run simulation for 6 seconds
  await new Promise((r) => setTimeout(r, 6000));

  // Compute metrics
  const durationSec = (Date.now() - startTime) / 1000;
  latencies.sort((a, b) => a - b);
  const p50 = latencies[Math.floor(latencies.length * 0.5)] || 0;
  const p95 = latencies[Math.floor(latencies.length * 0.95)] || 0;
  const p99 = latencies[Math.floor(latencies.length * 0.99)] || 0;

  console.log(`\n===============================================================`);
  console.log(`📊 LOAD SIMULATION METRICS & RESULTS`);
  console.log(`===============================================================`);
  console.log(`Duration:              ${durationSec.toFixed(2)}s`);
  console.log(`Total Messages Sent:   ${totalMessagesSent}`);
  console.log(`Total Messages Recv:   ${totalMessagesReceived}`);
  console.log(`Throughput:            ${(totalMessagesSent / durationSec).toFixed(1)} msg/sec`);
  console.log(`Latency p50:           ${p50}ms`);
  console.log(`Latency p95:           ${p95}ms`);
  console.log(`Latency p99:           ${p99}ms`);
  console.log(`Errors / Drops:        ${errorCount}`);
  console.log(`===============================================================\n`);

  players.forEach((p) => p.disconnect());
  process.exit(0);
}

runLoadSimulation();
