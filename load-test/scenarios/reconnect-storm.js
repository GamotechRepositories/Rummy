import WebSocket from 'ws';

const WS_URL = process.env.WS_URL || 'ws://localhost:8081/ws/game';
const NUM_PLAYERS = 10;

console.log(`\n===============================================================`);
console.log(`⚡ RECONNECTION STORM BENCHMARK SCENARIO`);
console.log(`===============================================================`);
console.log(`Target:           ${WS_URL}`);
console.log(`Disrupted Nodes:  ${NUM_PLAYERS} concurrent players`);
console.log(`===============================================================\n`);

let reconnectedCount = 0;
let failedReconnectCount = 0;

async function runReconnectStorm() {
  const NativeWS = typeof WebSocket !== 'undefined' ? WebSocket : globalThis.WebSocket;

  const players = [];
  for (let i = 0; i < NUM_PLAYERS; i++) {
    players.push({
      tableId: `STORM_TBL_${Math.floor(i / 2)}`,
      playerId: `STORM_USR_${i}`,
      seatIndex: i % 2,
    });
  }

  // Phase 1: Connect all
  console.log(`Phase 1: Establishing initial sessions for ${NUM_PLAYERS} players...`);
  const sockets = [];
  for (const p of players) {
    const ws = new NativeWS(WS_URL);
    await new Promise((res) => {
      ws.onopen = () => {
        ws.send(JSON.stringify({ type: 'AUTH', payload: { playerId: p.playerId } }));
        ws.send(JSON.stringify({ type: 'JOIN_TABLE', tableId: p.tableId, payload: { playerId: p.playerId, seatIndex: p.seatIndex } }));
        res();
      };
    });
    sockets.push(ws);
  }

  console.log(`✅ Phase 1 complete. All players seated.`);

  // Phase 2: Disconnect Storm
  console.log(`\n⚡ Phase 2: Triggering abrupt disconnect storm (dropping all sockets)...`);
  sockets.forEach((ws) => ws.close());
  await new Promise((r) => setTimeout(r, 1000));

  // Phase 3: Reconnection Storm
  console.log(`\n🔄 Phase 3: Initiating simultaneous reconnection storm...`);
  const reconnectPromises = players.map((p) => {
    return new Promise((resolve) => {
      const ws = new NativeWS(WS_URL);
      ws.onopen = () => {
        ws.send(JSON.stringify({ type: 'AUTH', payload: { playerId: p.playerId } }));
        ws.send(JSON.stringify({ type: 'JOIN_TABLE', tableId: p.tableId, payload: { playerId: p.playerId, seatIndex: p.seatIndex } }));
      };
      ws.onmessage = (evt) => {
        const data = JSON.parse(evt.data);
        if (data.type === 'GAME_VIEW') {
          reconnectedCount++;
          ws.close();
          resolve(true);
        }
      };
      ws.onerror = () => {
        failedReconnectCount++;
        resolve(false);
      };
    });
  });

  await Promise.all(reconnectPromises);

  console.log(`\n===============================================================`);
  console.log(`📊 RECONNECTION STORM RESULTS`);
  console.log(`===============================================================`);
  console.log(`Total Disrupted:         ${NUM_PLAYERS}`);
  console.log(`Successfully Recovered:  ${reconnectedCount}`);
  console.log(`Failed Reconnections:    ${failedReconnectCount}`);
  console.log(`Recovery Rate:           ${((reconnectedCount / NUM_PLAYERS) * 100).toFixed(1)}%`);
  console.log(`===============================================================\n`);

  process.exit(0);
}

runReconnectStorm();
