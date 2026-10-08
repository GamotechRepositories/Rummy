import re

file_path = r'D:\Projects\Rummy\Rummy\frontend\src\game\components\GameResultModal.tsx'

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

target = '''              allPlayers.map((p, i) => {
                const isDropped = p.status === 'DROPPED';
                let playerGroups: ShowdownGroup[] = [];

                if (p.isWinner) {
                  playerGroups = autoGroupShowdownCards(
                    p.hand,
                    gameState.cutJoker,
                    true,
                    gameState.winningGroups,
                    isRummy21
                  );
                } else if ('''

replacement = '''              allPlayers.map((p, i) => {
                const isDropped = p.status === 'DROPPED';
                let playerGroups: ShowdownGroup[] = [];
                const submittedMeld = gameState.submittedMelds?.[p.playerId];

                if (p.isWinner) {
                  playerGroups = autoGroupShowdownCards(
                    p.hand,
                    gameState.cutJoker,
                    true,
                    gameState.winningGroups,
                    isRummy21
                  );
                } else if (submittedMeld && submittedMeld.length > 0) {
                  const mapped = submittedMeld.map((g) => {
                    const groupType = evaluateCardGroup(g.cards, gameState.cutJoker);
                    return {
                      type: groupType,
                      cards: sortGroupCardsForDisplay(g.cards, groupType, gameState.cutJoker),
                      pts: 0,
                    };
                  });
                  playerGroups = applyRummyGroupPenalties(mapped, gameState.cutJoker, false, isRummy21);
                } else if ('''

content = content.replace(target, replacement)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
