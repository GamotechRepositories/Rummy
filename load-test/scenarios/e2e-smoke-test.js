// ==============================================================================
// Automated End-to-End Production Smoke Suite
// Validates Full Backend API, Security, Ledger, Compliance & WebSocket Handshake
// ==============================================================================

import WebSocket from 'ws';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8081';
const WS_URL = process.env.WS_URL || 'ws://localhost:8081/ws/game';

let passed = 0;
let failed = 0;

function assert(condition, message) {
  if (condition) {
    console.log(`  [PASS] ${message}`);
    passed++;
  } else {
    console.error(`  [FAIL] ${message}`);
    failed++;
  }
}

async function runSmokeSuite() {
  console.log('===========================================================');
  console.log('       RUMMY PLATFORM - AUTOMATED E2E SMOKE TEST SUITE     ');
  console.log('===========================================================');
  console.log(`Target Backend: ${BASE_URL}\n`);

  try {
    // 1. Health Probe
    console.log('1. Checking Actuator Health Probe...');
    const healthRes = await fetch(`${BASE_URL}/actuator/health`);
    assert(healthRes.ok, `Health endpoint returned status ${healthRes.status}`);
    const healthData = await healthRes.json();
    assert(healthData.status === 'UP', `System status is ${healthData.status}`);

    // 2. Guest Authentication Token
    console.log('\n2. Testing Cryptographic Guest Authentication...');
    const authRes = await fetch(`${BASE_URL}/api/auth/guest?displayName=SmokeTester`, { method: 'POST' });
    assert(authRes.ok, `Guest auth endpoint returned status ${authRes.status}`);
    const authData = await authRes.json();
    assert(!!authData.token, `Received signed JWT token: ${authData.token.substring(0, 20)}...`);
    const playerId = authData.playerId;
    const jwtToken = authData.token;

    // 3. Admin Diagnostics & Compliance Telemetry
    console.log('\n3. Testing Admin Diagnostics & Compliance API...');
    const diagRes = await fetch(`${BASE_URL}/api/admin/diagnostics`);
    assert(diagRes.ok, `Admin diagnostics status is ${diagRes.status}`);
    const diagData = await diagRes.json();
    assert(diagData.complianceMode === 'FREE_PLAY_ONLY', 'Compliance mode is FREE_PLAY_ONLY');
    assert(diagData.fairPlayCompliant === true, 'Fair-play compliance flag is active');

    // 4. Wallet Ledger & Faucet
    console.log('\n4. Testing Wallet Ledger & Token Faucet...');
    const walletRes = await fetch(`${BASE_URL}/api/wallet/balance?playerId=${playerId}`);
    assert(walletRes.ok, `Wallet balance query status is ${walletRes.status}`);
    const walletData = await walletRes.json();
    assert(walletData.freePlayBalance >= 1000, `Initial token balance is ${walletData.freePlayBalance}`);

    const faucetRes = await fetch(`${BASE_URL}/api/wallet/faucet?playerId=${playerId}&amount=250`, { method: 'POST' });
    assert(faucetRes.ok, `Faucet credit status is ${faucetRes.status}`);
    const faucetData = await faucetRes.json();
    assert(faucetData.success === true, 'Faucet successfully credited 250 tokens');

    // 5. Responsible Gaming & Player Safety
    console.log('\n5. Testing Responsible Gaming Controls...');
    const rgRes = await fetch(`${BASE_URL}/api/responsible-gambling/settings?playerId=${playerId}`);
    assert(rgRes.ok, `Responsible gaming settings status is ${rgRes.status}`);
    const rgData = await rgRes.json();
    assert(rgData.dailySessionLimitMinutes === 120, 'Default daily session limit is 120 minutes');
    assert(rgData.eligibilityStatus.isEligible === true, 'Player eligibility status is true');

    // 6. Anti-Fraud IP Collision Detection
    console.log('\n6. Testing Anti-Fraud IP Collision Guard...');
    const fraudRes1 = await fetch(
      `${BASE_URL}/api/fraud/verify-table-join?tableId=SMOKE_TBL_1&playerId=USER_SMOKE_A&clientIp=198.51.100.99`,
      { method: 'POST' }
    );
    const fraudData1 = await fraudRes1.json();
    assert(fraudData1.allowed === true, 'First player on IP permitted to join');

    const fraudRes2 = await fetch(
      `${BASE_URL}/api/fraud/verify-table-join?tableId=SMOKE_TBL_1&playerId=USER_SMOKE_B&clientIp=198.51.100.99`,
      { method: 'POST' }
    );
    const fraudData2 = await fraudRes2.json();
    assert(fraudData2.allowed === false, 'Second player on identical IP blocked due to collusion');

    // 7. WebSocket Handshake & Session Protocol
    console.log('\n7. Testing Real-Time WebSocket Handshake...');
    await new Promise((resolve) => {
      const ws = new WebSocket(`${WS_URL}?token=${jwtToken}`);

      ws.on('open', () => {
        assert(true, 'WebSocket connection established with cryptographic JWT');
        ws.send(JSON.stringify({
          action: 'JOIN_TABLE',
          tableId: 'TBL_SMOKE_01',
          playerId: playerId,
          playerName: 'SmokeTester',
          seatIndex: 0
        }));
      });

      ws.on('message', (msg) => {
        const payload = JSON.parse(msg.toString());
        if (payload.type === 'TABLE_JOINED' || payload.type === 'GAME_VIEW' || payload.type === 'CONNECTED') {
          assert(true, `Received server table message: ${payload.type}`);
          ws.close();
          resolve();
        }
      });

      ws.on('error', (err) => {
        assert(false, `WebSocket error: ${err.message}`);
        resolve();
      });

      setTimeout(() => {
        ws.close();
        resolve();
      }, 3000);
    });

    console.log('\n===========================================================');
    console.log(` E2E SMOKE SUITE COMPLETE: ${passed} PASSED, ${failed} FAILED `);
    console.log('===========================================================');

    if (failed > 0) {
      process.exit(1);
    }
  } catch (err) {
    console.error(`\n[CRITICAL ERROR] Smoke suite execution failed: ${err.message}`);
    process.exit(1);
  }
}

runSmokeSuite();
