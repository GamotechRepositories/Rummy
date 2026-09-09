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
}
