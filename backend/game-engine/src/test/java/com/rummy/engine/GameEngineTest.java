package com.rummy.engine;

import com.rummy.engine.command.*;
import com.rummy.engine.event.*;
import com.rummy.engine.model.*;
import com.rummy.engine.rules.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GameEngineTest {

    private final GameEngine engine = new GameEngine();
    private final PointsRummyRules rules = new PointsRummyRules();

    @Test
    @DisplayName("Complete game lifecycle: Join -> Ready -> Start -> Draw -> Discard -> Turn Progression")
    void testGameLifecycle() {
        Deck deck = Deck.createStandard13CardDeck();
        GameState state = new GameState("G1", "T1", "POINTS_13", "1.0.0", List.of(), deck);
        Instant now = Instant.now();

        // 1. Join Player 1
        EngineResult r1 = engine.process(state, new JoinCommand("c1", "G1", "P1", "Alice", 0, false, now), rules);
        assertThat(r1.isSuccess()).isTrue();
        assertThat(r1.state().getPlayers()).hasSize(1);
        state = r1.state();

        // 2. Join Player 2
        EngineResult r2 = engine.process(state, new JoinCommand("c2", "G1", "P2", "Bob", 1, false, now), rules);
        assertThat(r2.isSuccess()).isTrue();
        assertThat(r2.state().getPlayers()).hasSize(2);
        state = r2.state();

        // 3. Ready up
        engine.process(state, new ReadyCommand("c3", "G1", "P1", now), rules);
        engine.process(state, new ReadyCommand("c4", "G1", "P2", now), rules);

        // 4. Start Game
        EngineResult rStart = engine.process(state, new StartGameCommand("c5", "G1", "P1", now), rules);
        assertThat(rStart.isSuccess()).isTrue();
        assertThat(state.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(state.getCutJoker()).isNotNull();
        assertThat(state.getDiscardPile()).hasSize(1);
        assertThat(state.requirePlayer("P1").getHandSize()).isEqualTo(13);
        assertThat(state.requirePlayer("P2").getHandSize()).isEqualTo(13);

        TurnState turn = state.getTurnState();
        assertThat(turn).isNotNull();
        assertThat(turn.getCurrentPlayerId()).isEqualTo("P1");
        assertThat(turn.getPhase()).isEqualTo(TurnPhase.AWAITING_DRAW);

        // 5. P1 Draws from Closed Deck
        EngineResult rDraw = engine.process(state, new DrawCommand("c6", "G1", "P1", DrawSource.CLOSED_DECK, now), rules);
        assertThat(rDraw.isSuccess()).isTrue();
        assertThat(state.requirePlayer("P1").getHandSize()).isEqualTo(14);
        assertThat(state.getTurnState().getPhase()).isEqualTo(TurnPhase.AWAITING_DISCARD);

        // 6. P1 Discards one non-joker card
        Card cutCard = state.getCutJoker() != null ? state.getCutJoker().getCard() : null;
        CardInstance cardToDiscard = state.requirePlayer("P1").getHandSnapshot().stream()
                .filter(c -> !c.isPrintedJoker() && (cutCard == null || !c.getCard().isWildJoker(cutCard)))
                .findFirst()
                .orElse(state.requirePlayer("P1").getHandSnapshot().get(0));
        EngineResult rDiscard = engine.process(state, new DiscardCommand("c7", "G1", "P1", cardToDiscard.getInstanceId(), now), rules);
        assertThat(rDiscard.isSuccess()).isTrue();
        assertThat(state.requirePlayer("P1").getHandSize()).isEqualTo(13);
        assertThat(state.topDiscard()).isEqualTo(cardToDiscard);

        // Turn must now be with P2
        TurnState p2Turn = state.getTurnState();
        assertThat(p2Turn.getCurrentPlayerId()).isEqualTo("P2");
        assertThat(p2Turn.getPhase()).isEqualTo(TurnPhase.AWAITING_DRAW);

        // 7. P2 Draws from Discard Pile (picks up cardToDiscard!)
        EngineResult rP2Draw = engine.process(state, new DrawCommand("c8", "G1", "P2", DrawSource.DISCARD_PILE, now), rules);
        assertThat(rP2Draw.isSuccess()).isTrue();
        assertThat(state.requirePlayer("P2").getHandSize()).isEqualTo(14);
        assertThat(state.requirePlayer("P2").hasCard(cardToDiscard.getInstanceId())).isTrue();
    }

    @Test
    @DisplayName("Card conservation invariant: Total cards across hands + closed deck + discard + cut joker must always equal 106")
    void testCardConservation() {
        Deck deck = Deck.createStandard13CardDeck();
        GameState state = new GameState("G1", "T1", "POINTS_13", "1.0.0", List.of(), deck);
        Instant now = Instant.now();

        engine.process(state, new JoinCommand("c1", "G1", "P1", "Alice", 0, false, now), rules);
        engine.process(state, new JoinCommand("c2", "G1", "P2", "Bob", 1, false, now), rules);
        engine.process(state, new ReadyCommand("c3", "G1", "P1", now), rules);
        engine.process(state, new ReadyCommand("c4", "G1", "P2", now), rules);
        engine.process(state, new StartGameCommand("c5", "G1", "P1", now), rules);

        int total = state.requirePlayer("P1").getHandSize()
                + state.requirePlayer("P2").getHandSize()
                + (state.getCutJoker() != null ? 1 : 0)
                + state.getDiscardPile().size()
                + state.getDeck().remaining();

        // 13 + 13 + 1 + 1 + 78 = 106 cards
        assertThat(total).isEqualTo(106);
    }

    @Test
    @DisplayName("First drop penalty (20 pts) vs middle drop penalty (40 pts)")
    void testDropPenalties() {
        Deck deck = Deck.createStandard13CardDeck();
        GameState state = new GameState("G1", "T1", "POINTS_13", "1.0.0", List.of(), deck);
        Instant now = Instant.now();

        engine.process(state, new JoinCommand("c1", "G1", "P1", "Alice", 0, false, now), rules);
        engine.process(state, new JoinCommand("c2", "G1", "P2", "Bob", 1, false, now), rules);
        engine.process(state, new ReadyCommand("c3", "G1", "P1", now), rules);
        engine.process(state, new ReadyCommand("c4", "G1", "P2", now), rules);
        engine.process(state, new StartGameCommand("c5", "G1", "P1", now), rules);

        // P1 drops before playing any completed turn -> First Drop (20 points)
        EngineResult rDrop = engine.process(state, new DropCommand("c6", "G1", "P1", now), rules);
        assertThat(rDrop.isSuccess()).isTrue();
        assertThat(state.requirePlayer("P1").getStatus()).isEqualTo(PlayerStatus.DROPPED);
        assertThat(state.requirePlayer("P1").getScore()).isEqualTo(20);

        // Since only P2 remains, P2 automatically wins with 0 points
        assertThat(state.getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(state.getWinnerPlayerId()).isEqualTo("P2");
        assertThat(state.requirePlayer("P2").getScore()).isEqualTo(0);
    }

    @Test
    @DisplayName("3 consecutive missed turns triggers automatic middle-drop (40 pts)")
    void testAutoDropOnThreeConsecutiveTimeouts() {
        Deck deck = Deck.createStandard13CardDeck();
        GameState state = new GameState("G1", "T1", "POINTS_13", "1.0.0", List.of(), deck);
        Instant now = Instant.now();

        engine.process(state, new JoinCommand("c1", "G1", "P1", "Alice", 0, false, now), rules);
        engine.process(state, new JoinCommand("c2", "G1", "P2", "Bob", 1, false, now), rules);
        engine.process(state, new ReadyCommand("c3", "G1", "P1", now), rules);
        engine.process(state, new ReadyCommand("c4", "G1", "P2", now), rules);
        engine.process(state, new StartGameCommand("c5", "G1", "P1", now), rules);

        // Timeout 1 for P1
        engine.process(state, new TimeoutCommand("t1", "G1", "P1", now), rules);
        assertThat(state.requirePlayer("P1").getConsecutiveMissedTurns()).isEqualTo(1);
        assertThat(state.requirePlayer("P1").getStatus()).isEqualTo(PlayerStatus.ACTIVE);

        // P2 plays normal turn
        engine.process(state, new DrawCommand("d1", "G1", "P2", DrawSource.CLOSED_DECK, now), rules);
        CardInstance p2Card = state.requirePlayer("P2").getHandSnapshot().get(0);
        engine.process(state, new DiscardCommand("d2", "G1", "P2", p2Card.getInstanceId(), now), rules);

        // Timeout 2 for P1
        engine.process(state, new TimeoutCommand("t2", "G1", "P1", now), rules);
        assertThat(state.requirePlayer("P1").getConsecutiveMissedTurns()).isEqualTo(2);

        // P2 plays normal turn
        engine.process(state, new DrawCommand("d3", "G1", "P2", DrawSource.CLOSED_DECK, now), rules);
        CardInstance p2Card2 = state.requirePlayer("P2").getHandSnapshot().get(0);
        engine.process(state, new DiscardCommand("d4", "G1", "P2", p2Card2.getInstanceId(), now), rules);

        // Timeout 3 for P1 -> Auto Drop!
        engine.process(state, new TimeoutCommand("t3", "G1", "P1", now), rules);
        assertThat(state.requirePlayer("P1").getStatus()).isEqualTo(PlayerStatus.DROPPED);
        assertThat(state.requirePlayer("P1").getScore()).isEqualTo(40);
        assertThat(state.getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(state.getWinnerPlayerId()).isEqualTo("P2");
    }

    @Test
    @DisplayName("Valid declaration finishes game with 0 points for winner")
    void testValidDeclarationFinishesGame() {
        Deck deck = Deck.createStandard13CardDeck();
        GameState state = new GameState("G1", "T1", "POINTS_13", "1.0.0", List.of(), deck);
        Instant now = Instant.now();

        engine.process(state, new JoinCommand("c1", "G1", "P1", "Alice", 0, false, now), rules);
        engine.process(state, new JoinCommand("c2", "G1", "P2", "Bob", 1, false, now), rules);
        engine.process(state, new ReadyCommand("c3", "G1", "P1", now), rules);
        engine.process(state, new ReadyCommand("c4", "G1", "P2", now), rules);
        engine.process(state, new StartGameCommand("c5", "G1", "P1", now), rules);

        // P1 draws
        engine.process(state, new DrawCommand("c6", "G1", "P1", DrawSource.CLOSED_DECK, now), rules);

        // Fabricate valid 13-card hand for P1 + 1 finish card
        PlayerState p1 = state.requirePlayer("P1");
        List<CardInstance> originalHand = List.copyOf(p1.getHandSnapshot());
        for (CardInstance c : originalHand) {
            p1.removeCard(c.getInstanceId());
        }

        CardInstance finishCard = new CardInstance("FINISH", Card.of(Suit.SPADES, Rank.TWO), 1);
        p1.addCard(finishCard);

        CardGroup g1 = CardGroup.of(
                new CardInstance("H4", Card.of(Suit.HEARTS, Rank.FOUR), 1),
                new CardInstance("H5", Card.of(Suit.HEARTS, Rank.FIVE), 1),
                new CardInstance("H6", Card.of(Suit.HEARTS, Rank.SIX), 1),
                new CardInstance("H7", Card.of(Suit.HEARTS, Rank.SEVEN), 1)
        );
        CardGroup g2 = CardGroup.of(
                new CardInstance("C9", Card.of(Suit.CLUBS, Rank.NINE), 1),
                new CardInstance("C10", Card.of(Suit.CLUBS, Rank.TEN), 1),
                new CardInstance("CJ", Card.of(Suit.CLUBS, Rank.JACK), 1)
        );
        CardGroup g3 = CardGroup.of(
                new CardInstance("SK", Card.of(Suit.SPADES, Rank.KING), 1),
                new CardInstance("HK", Card.of(Suit.HEARTS, Rank.KING), 1),
                new CardInstance("DK", Card.of(Suit.DIAMONDS, Rank.KING), 1)
        );
        CardGroup g4 = CardGroup.of(
                new CardInstance("S3", Card.of(Suit.SPADES, Rank.THREE), 1),
                new CardInstance("H3", Card.of(Suit.HEARTS, Rank.THREE), 1),
                new CardInstance("D3", Card.of(Suit.DIAMONDS, Rank.THREE), 1)
        );

        p1.addCards(g1.getCards());
        p1.addCards(g2.getCards());
        p1.addCards(g3.getCards());
        p1.addCards(g4.getCards());
        assertThat(p1.getHandSize()).isEqualTo(14); // 13 groups + 1 finish

        EngineResult rDeclare = engine.process(state, new DeclareCommand("dec1", "G1", "P1",
                finishCard.getInstanceId(), List.of(g1, g2, g3, g4), now), rules);

        assertThat(rDeclare.isSuccess()).isTrue();
        assertThat(state.getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(state.getWinnerPlayerId()).isEqualTo("P1");
        assertThat(p1.getScore()).isEqualTo(0);
        assertThat(state.requirePlayer("P2").getScore()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Play again after COMPLETED starts a fresh deal")
    void testRematchAfterCompleted() {
        Deck deck = Deck.createStandard13CardDeck();
        GameState state = new GameState("G1", "T1", "POINTS_13", "1.0.0", List.of(), deck);
        Instant now = Instant.now();

        engine.process(state, new JoinCommand("c1", "G1", "P1", "Alice", 0, false, now), rules);
        engine.process(state, new JoinCommand("c2", "G1", "P2", "Bob", 1, false, now), rules);
        engine.process(state, new ReadyCommand("c3", "G1", "P1", now), rules);
        engine.process(state, new ReadyCommand("c4", "G1", "P2", now), rules);
        engine.process(state, new StartGameCommand("c5", "G1", "P1", now), rules);

        // End game via first drop so status becomes COMPLETED
        EngineResult drop = engine.process(state, new DropCommand("c6", "G1", "P1", now), rules);
        assertThat(drop.isSuccess()).isTrue();
        assertThat(state.getStatus()).isEqualTo(GameStatus.COMPLETED);

        // Play again / rematch
        EngineResult rematch = engine.process(state, new StartGameCommand("c7", "G1", "P2", now), rules);
        assertThat(rematch.isSuccess()).isTrue();
        assertThat(state.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(state.getWinnerPlayerId()).isNull();
        assertThat(state.requirePlayer("P1").getHandSize()).isEqualTo(13);
        assertThat(state.requirePlayer("P2").getHandSize()).isEqualTo(13);
        assertThat(state.requirePlayer("P1").getStatus()).isEqualTo(PlayerStatus.ACTIVE);
        assertThat(state.requirePlayer("P2").getStatus()).isEqualTo(PlayerStatus.ACTIVE);
        assertThat(state.getCutJoker()).isNotNull();
        assertThat(state.topDiscard()).isNotNull();
    }

    @Test
    @DisplayName("21-Card Rummy: deals 21 cards, handles 22-card draw, auto-discards on timeout, and applies 30-pt first drop")
    void testTwentyOneCardRummyGameFlow() {
        RummyRules r21 = RulesetRegistry.requireRuleset("RUMMY_21");
        Deck deck = Deck.createMultiPackDeck(r21.getDeckCount(), r21.getPrintedJokersPerDeck(), new java.security.SecureRandom());
        GameState state = new GameState("G_21", "T_21", "RUMMY_21", "1.0.0", List.of(), deck);
        Instant now = Instant.now();

        engine.process(state, new JoinCommand("c1", "G_21", "P1", "Player 1", 0, false, now), r21);
        engine.process(state, new JoinCommand("c2", "G_21", "P2", "Player 2", 1, false, now), r21);
        engine.process(state, new ReadyCommand("c3", "G_21", "P1", now), r21);
        engine.process(state, new ReadyCommand("c4", "G_21", "P2", now), r21);

        EngineResult start = engine.process(state, new StartGameCommand("c5", "G_21", "P1", now), r21);
        assertThat(start.isSuccess()).isTrue();
        assertThat(state.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);

        PlayerState p1 = state.requirePlayer("P1");
        PlayerState p2 = state.requirePlayer("P2");
        assertThat(p1.getHandSize()).isEqualTo(21);
        assertThat(p2.getHandSize()).isEqualTo(21);

        String activePlayerId = state.getTurnState().getCurrentPlayerId();
        PlayerState activePlayer = state.requirePlayer(activePlayerId);

        // Active player draws from closed deck -> hand size becomes 22 (21 + 1)
        EngineResult drawResult = engine.process(state, new DrawCommand("c6", "G_21", activePlayerId, DrawSource.CLOSED_DECK, now), r21);
        assertThat(drawResult.isSuccess()).isTrue();
        assertThat(activePlayer.getHandSize()).isEqualTo(22);
        assertThat(state.getTurnState().getPhase()).isEqualTo(TurnPhase.AWAITING_DISCARD);

        // Timeout during discard phase -> MUST auto-discard drawn card back to 21 cards
        EngineResult timeoutResult = engine.process(state, new TimeoutCommand("c7", "G_21", activePlayerId, now), r21);
        assertThat(timeoutResult.isSuccess()).isTrue();
        assertThat(activePlayer.getHandSize()).isEqualTo(21); // Restored to 21!

        // The other player now drops (first drop = 30 pts)
        String nextPlayerId = state.getTurnState().getCurrentPlayerId();
        EngineResult dropResult = engine.process(state, new DropCommand("c8", "G_21", nextPlayerId, now), r21);
        assertThat(dropResult.isSuccess()).isTrue();
        assertThat(state.getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(state.requirePlayer(nextPlayerId).getScore()).isEqualTo(30);
    }

    private GameState createAndStartStandardGame(String gameId) {
        Deck deck = Deck.createStandard13CardDeck();
        GameState state = new GameState(gameId, "T1", "POINTS_13", "1.0.0", List.of(), deck);
        Instant now = Instant.now();
        engine.process(state, new JoinCommand("c1", gameId, "P1", "Alice", 0, false, now), rules);
        engine.process(state, new JoinCommand("c2", gameId, "P2", "Bob", 1, false, now), rules);
        engine.process(state, new ReadyCommand("c3", gameId, "P1", now), rules);
        engine.process(state, new ReadyCommand("c4", gameId, "P2", now), rules);
        engine.process(state, new StartGameCommand("c5", gameId, "P1", now), rules);
        return state;
    }

    @Test
    @DisplayName("Points Rummy: same card discard restriction, joker draw restriction, and drop after draw validation")
    void testPointsRummyRuleSafeguards() {
        GameState state = createAndStartStandardGame("G_SAFE");
        Instant now = Instant.now();
        String activePlayerId = state.getTurnState().getCurrentPlayerId();
        PlayerState player = state.requirePlayer(activePlayerId);

        // 1. Drop after draw is strictly disallowed
        // Player draws from closed deck
        EngineResult drawClosed = engine.process(state, new DrawCommand("c10", state.getGameId(), activePlayerId, DrawSource.CLOSED_DECK, now), rules);
        assertThat(drawClosed.isSuccess()).isTrue();
        assertThat(state.getTurnState().getPhase()).isEqualTo(TurnPhase.AWAITING_DISCARD);

        EngineResult illegalDrop = engine.process(state, new DropCommand("c11", state.getGameId(), activePlayerId, now), rules);
        assertThat(illegalDrop.isSuccess()).isFalse();
        assertThat(illegalDrop.errorMessage()).contains("Cannot drop after drawing a card");

        // Player discards a card normally
        CardInstance cardToDiscard = player.getHandSnapshot().get(0);
        EngineResult normalDiscard = engine.process(state, new DiscardCommand("c12", state.getGameId(), activePlayerId, cardToDiscard.getInstanceId(), now), rules);
        assertThat(normalDiscard.isSuccess()).isTrue();

        // 2. Next player draws from open discard pile
        String nextPlayerId = state.getTurnState().getCurrentPlayerId();
        PlayerState nextPlayer = state.requirePlayer(nextPlayerId);
        CardInstance topOpenDiscard = state.topDiscard();

        EngineResult drawOpen = engine.process(state, new DrawCommand("c13", state.getGameId(), nextPlayerId, DrawSource.DISCARD_PILE, now), rules);
        assertThat(drawOpen.isSuccess()).isTrue();

        // Next player tries to immediately discard the exact same card just drawn
        EngineResult sameCardDiscard = engine.process(state, new DiscardCommand("c14", state.getGameId(), nextPlayerId, topOpenDiscard.getInstanceId(), now), rules);
        assertThat(sameCardDiscard.isSuccess()).isFalse();
        assertThat(sameCardDiscard.errorMessage()).contains("Cannot discard the card you just picked from the open discard pile");

        // Next player discards a different natural card
        CardInstance diffCard = nextPlayer.getHandSnapshot().stream()
                .filter(c -> !c.getInstanceId().equals(topOpenDiscard.getInstanceId()))
                .findFirst().orElseThrow();
        engine.process(state, new DiscardCommand("c15", state.getGameId(), nextPlayerId, diffCard.getInstanceId(), now), rules);

        // 3. Joker from discard pile is rejected
        // Put a printed joker on top of discard pile to test draw
        CardInstance printedJoker = new CardInstance("PJ_TEST", Card.printedJoker(), 1);
        state.addToDiscardPile(printedJoker);

        // The next active player now tries to draw the joker from discard pile
        String thirdTurnPlayerId = state.getTurnState().getCurrentPlayerId();
        EngineResult drawJoker = engine.process(state, new DrawCommand("c16", state.getGameId(), thirdTurnPlayerId, DrawSource.DISCARD_PILE, now), rules);
        assertThat(drawJoker.isSuccess()).isFalse();
        assertThat(drawJoker.errorMessage()).contains("Cannot pick a joker from the open discard pile");
    }

    @Test
    @DisplayName("Points Rummy: drop after timeout is a middle drop (40 points)")
    void testDropAfterTimeoutIsMiddleDrop() {
        GameState state = createAndStartStandardGame("G_TIMEOUT");
        Instant now = Instant.now();
        String activePlayerId = state.getTurnState().getCurrentPlayerId();

        // Player times out without drawing
        engine.process(state, new TimeoutCommand("c20", state.getGameId(), activePlayerId, now), rules);
        String secondPlayerId = state.getTurnState().getCurrentPlayerId();

        // Second player draws and discards
        engine.process(state, new DrawCommand("c21", state.getGameId(), secondPlayerId, DrawSource.CLOSED_DECK, now), rules);
        PlayerState p2 = state.requirePlayer(secondPlayerId);
        engine.process(state, new DiscardCommand("c22", state.getGameId(), secondPlayerId, p2.getHandSnapshot().get(0).getInstanceId(), now), rules);

        // Turn returns to first player. Since first player missed turn 1, dropping now is a Middle Drop (40 pts)
        assertThat(state.getTurnState().getCurrentPlayerId()).isEqualTo(activePlayerId);
        EngineResult dropResult = engine.process(state, new DropCommand("c23", state.getGameId(), activePlayerId, now), rules);
        assertThat(dropResult.isSuccess()).isTrue();
        assertThat(state.requirePlayer(activePlayerId).getScore()).isEqualTo(40); // 40 points middle drop!
    }
}
