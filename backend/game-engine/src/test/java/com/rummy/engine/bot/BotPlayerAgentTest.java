package com.rummy.engine.bot;

import com.rummy.engine.command.*;
import com.rummy.engine.model.*;
import com.rummy.engine.rules.PointsRummyRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BotPlayerAgentTest {

    private final PointsRummyRules rules = new PointsRummyRules();

    private static CardInstance card(Suit suit, Rank rank, String id) {
        return new CardInstance(id, Card.of(suit, rank), 1);
    }

    @Test
    @DisplayName("Bot should draw from discard pile if it improves the hand")
    void testBotDrawsFromDiscardWhenHelpful() {
        BotPlayerAgent bot = new BotPlayerAgent("BOT_1", "Computer", BotDifficulty.HARD);

        // Bot hand has 4♥ 5♥
        List<CardInstance> hand = List.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                card(Suit.HEARTS, Rank.FIVE, "2")
        );

        // Top discard is 6♥ (completes 4-5-6 sequence)
        CardInstance topDiscard = card(Suit.HEARTS, Rank.SIX, "TOP");

        PlayerGameView view = new PlayerGameView(
                "T1", "G1", "BOT_1", GameStatus.IN_PROGRESS, 1L,
                hand, List.of(), topDiscard, null, 80,
                "BOT_1", TurnPhase.AWAITING_DRAW, Instant.now().plusSeconds(30), true
        );

        GameCommand cmd = bot.decideAction(view, rules);
        assertThat(cmd).isInstanceOf(DrawCommand.class);
        DrawCommand drawCmd = (DrawCommand) cmd;
        assertThat(drawCmd.source()).isEqualTo(DrawSource.DISCARD_PILE);
    }

    @Test
    @DisplayName("Bot should draw from closed deck if discard does not help")
    void testBotDrawsFromClosedDeckWhenDiscardUnhelpful() {
        BotPlayerAgent bot = new BotPlayerAgent("BOT_1", "Computer", BotDifficulty.HARD);

        // Bot hand has 4♥ 5♥
        List<CardInstance> hand = List.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                card(Suit.HEARTS, Rank.FIVE, "2")
        );

        // Top discard is King of Spades (useless for 4♥ 5♥)
        CardInstance topDiscard = card(Suit.SPADES, Rank.KING, "TOP");

        PlayerGameView view = new PlayerGameView(
                "T1", "G1", "BOT_1", GameStatus.IN_PROGRESS, 1L,
                hand, List.of(), topDiscard, null, 80,
                "BOT_1", TurnPhase.AWAITING_DRAW, Instant.now().plusSeconds(30), true
        );

        GameCommand cmd = bot.decideAction(view, rules);
        assertThat(cmd).isInstanceOf(DrawCommand.class);
        DrawCommand drawCmd = (DrawCommand) cmd;
        assertThat(drawCmd.source()).isEqualTo(DrawSource.CLOSED_DECK);
    }

    @Test
    @DisplayName("Bot should automatically declare when holding a 14-card winning hand")
    void testBotDeclaresWhenWinning() {
        BotPlayerAgent bot = new BotPlayerAgent("BOT_1", "Computer", BotDifficulty.HARD);

        // 14 winning cards
        List<CardInstance> hand14 = List.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                card(Suit.HEARTS, Rank.FIVE, "2"),
                card(Suit.HEARTS, Rank.SIX, "3"),
                card(Suit.HEARTS, Rank.SEVEN, "4"),

                card(Suit.CLUBS, Rank.NINE, "5"),
                card(Suit.CLUBS, Rank.TEN, "6"),
                card(Suit.CLUBS, Rank.JACK, "7"),

                card(Suit.SPADES, Rank.KING, "8"),
                card(Suit.HEARTS, Rank.KING, "9"),
                card(Suit.DIAMONDS, Rank.KING, "10"),

                card(Suit.SPADES, Rank.THREE, "11"),
                card(Suit.HEARTS, Rank.THREE, "12"),
                card(Suit.DIAMONDS, Rank.THREE, "13"),

                card(Suit.SPADES, Rank.TWO, "FINISH")
        );

        PlayerGameView view = new PlayerGameView(
                "T1", "G1", "BOT_1", GameStatus.IN_PROGRESS, 2L,
                hand14, List.of(), null, null, 78,
                "BOT_1", TurnPhase.AWAITING_DISCARD, Instant.now().plusSeconds(30), true
        );

        GameCommand cmd = bot.decideAction(view, rules);
        assertThat(cmd).isInstanceOf(DeclareCommand.class);
        DeclareCommand declareCmd = (DeclareCommand) cmd;
        assertThat(declareCmd.finishCardInstanceId()).isEqualTo("FINISH");
        assertThat(declareCmd.groups()).hasSize(4);
    }

    @Test
    @DisplayName("Bot should avoid discarding dangerous cards picked by opponents")
    void testDefensiveDiscardAvoidsFeedingOpponent() {
        BotPlayerAgent bot = new BotPlayerAgent("BOT_1", "Computer", BotDifficulty.HARD);
        // Opponent picked 8♠
        bot.recordOpponentPick(Card.of(Suit.SPADES, Rank.EIGHT));

        // Bot has deadwood cards: 9♠ (10 pts, adjacent to 8♠!), 10♦ (10 pts, safe!)
        CardInstance nineSpades = card(Suit.SPADES, Rank.NINE, "9S");
        CardInstance tenDiamonds = card(Suit.DIAMONDS, Rank.TEN, "10D");
        List<CardInstance> hand = List.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                card(Suit.HEARTS, Rank.FIVE, "2"),
                card(Suit.HEARTS, Rank.SIX, "3"),
                card(Suit.CLUBS, Rank.NINE, "4"),
                card(Suit.CLUBS, Rank.TEN, "5"),
                card(Suit.CLUBS, Rank.JACK, "6"),
                card(Suit.SPADES, Rank.KING, "7"),
                card(Suit.HEARTS, Rank.KING, "8"),
                card(Suit.DIAMONDS, Rank.KING, "9"),
                card(Suit.SPADES, Rank.THREE, "10"),
                card(Suit.HEARTS, Rank.THREE, "11"),
                card(Suit.DIAMONDS, Rank.THREE, "12"),
                nineSpades,
                tenDiamonds
        );

        PlayerGameView view = new PlayerGameView(
                "T1", "G1", "BOT_1", GameStatus.IN_PROGRESS, 5L,
                hand, List.of(), null, null, 70,
                "BOT_1", TurnPhase.AWAITING_DISCARD, Instant.now().plusSeconds(30), true
        );

        GameCommand cmd = bot.decideAction(view, rules);
        assertThat(cmd).isInstanceOf(DiscardCommand.class);
        DiscardCommand discardCmd = (DiscardCommand) cmd;
        // Bot should discard 10♦ instead of feeding 9♠ to the opponent!
        assertThat(discardCmd.cardInstanceId()).isEqualTo("10D");
    }

    @Test
    @DisplayName("Bot never drops on its opening turns, even with a hopeless hand")
    void testBotDoesNotDropStraightAfterTheDeal() {
        BotPlayerAgent bot = new BotPlayerAgent("BOT_1", "Computer", BotDifficulty.HARD, neverHesitates());

        assertThat(bot.decideAction(hopelessTurnView(false, 0, 0), rules)).isInstanceOf(DrawCommand.class);
        assertThat(bot.decideAction(hopelessTurnView(true, 0, 0), rules)).isInstanceOf(DrawCommand.class);
    }

    @Test
    @DisplayName("Heads-up bot middle-drops a hopeless hand late in the deal")
    void testBotMiddleDropsHopelessHandLater() {
        BotPlayerAgent bot = new BotPlayerAgent("BOT_1", "Computer", BotDifficulty.HARD, neverHesitates());

        GameCommand cmd = null;
        for (int turn = 1; turn <= 8; turn++) {
            cmd = bot.decideAction(hopelessTurnView(turn > 1, 0, 0), rules);
            if (turn < 8) {
                assertThat(cmd).as("turn %d", turn).isInstanceOf(DrawCommand.class);
            }
        }
        assertThat(cmd).isInstanceOf(DropCommand.class);
        assertThat(cmd.playerId()).isEqualTo("BOT_1");
    }

    @Test
    @DisplayName("Pool bot keeps playing rather than drop into elimination")
    void testPoolBotDoesNotDropIntoElimination() {
        BotPlayerAgent bot = new BotPlayerAgent("BOT_1", "Computer", BotDifficulty.HARD, neverHesitates());

        for (int turn = 1; turn <= 10; turn++) {
            assertThat(bot.decideAction(hopelessTurnView(turn > 1, 101, 85), rules))
                    .as("turn %d", turn).isInstanceOf(DrawCommand.class);
        }
    }

    private static java.util.Random neverHesitates() {
        return new java.util.Random() {
            @Override
            public double nextDouble() {
                return 0.99;
            }
        };
    }

    private static PlayerGameView hopelessTurnView(boolean hasTakenFirstTurn, int eliminationThreshold, int cumulativeScore) {
        // Dry 13-card hand with 0 jokers, 0 melds, high deadwood points
        List<CardInstance> dryHand = List.of(
                card(Suit.SPADES, Rank.KING, "1"),
                card(Suit.HEARTS, Rank.KING, "2"),
                card(Suit.DIAMONDS, Rank.QUEEN, "3"),
                card(Suit.CLUBS, Rank.JACK, "4"),
                card(Suit.SPADES, Rank.TEN, "5"),
                card(Suit.HEARTS, Rank.NINE, "6"),
                card(Suit.DIAMONDS, Rank.EIGHT, "7"),
                card(Suit.CLUBS, Rank.SEVEN, "8"),
                card(Suit.SPADES, Rank.FOUR, "9"),
                card(Suit.HEARTS, Rank.THREE, "10"),
                card(Suit.DIAMONDS, Rank.TWO, "11"),
                card(Suit.CLUBS, Rank.FIVE, "12"),
                card(Suit.SPADES, Rank.ACE, "13")
        );

        return new PlayerGameView(
                "T1", "G1", "BOT_1", GameStatus.IN_PROGRESS, 1L,
                dryHand, List.of(), null, null, 80,
                "BOT_1", TurnPhase.AWAITING_DRAW, Instant.now().plusSeconds(30), true,
                null, List.of(), 0, PlayerStatus.ACTIVE, List.of(), 0,
                "POINTS_13", 1, eliminationThreshold, cumulativeScore, false, List.of(), List.of(), null, null, 0,
                false, 0, 0, List.of(), 1, 0L, hasTakenFirstTurn, null, false
        );
    }

    @Test
    @DisplayName("Bot should prioritize closed deck when lacking pure sequence and discard card only forms a set")
    void testPureSequenceDrawPriority() {
        BotPlayerAgent bot = new BotPlayerAgent("BOT_1", "Computer", BotDifficulty.HARD);

        // Hand has pairs (8♠ 8♥) and scattered cards, but NO pure sequence
        List<CardInstance> hand = List.of(
                card(Suit.SPADES, Rank.EIGHT, "8S"),
                card(Suit.HEARTS, Rank.EIGHT, "8H"),
                card(Suit.CLUBS, Rank.TWO, "2C")
        );

        // Top discard is 8♦ (forms a set of 8s, but bot has 0 pure sequence)
        CardInstance topDiscard = card(Suit.DIAMONDS, Rank.EIGHT, "8D");

        PlayerGameView view = new PlayerGameView(
                "T1", "G1", "BOT_1", GameStatus.IN_PROGRESS, 2L,
                hand, List.of(), topDiscard, null, 75,
                "BOT_1", TurnPhase.AWAITING_DRAW, Instant.now().plusSeconds(30), true
        );

        GameCommand cmd = bot.decideAction(view, rules);
        assertThat(cmd).isInstanceOf(DrawCommand.class);
        DrawCommand drawCmd = (DrawCommand) cmd;
        // Without pure sequence, bot should draw from closed deck rather than picking for a set
        assertThat(drawCmd.source()).isEqualTo(DrawSource.CLOSED_DECK);
    }
}
