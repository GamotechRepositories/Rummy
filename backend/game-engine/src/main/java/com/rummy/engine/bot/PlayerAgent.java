package com.rummy.engine.bot;

import com.rummy.engine.command.GameCommand;
import com.rummy.engine.model.PlayerGameView;
import com.rummy.engine.rules.RummyRules;

/**
 * Common interface for table agents.
 * By consuming only PlayerGameView, agents operate under strict zero-knowledge constraints.
 */
public interface PlayerAgent {

    String getPlayerId();

    boolean isBot();

    /**
     * Determine the next action given the current private player view.
     *
     * @param view  sanitized view containing player's own hand, top discard, and public info
     * @param rules the table ruleset
     * @return GameCommand to execute (DrawCommand, DiscardCommand, or DeclareCommand)
     */
    GameCommand decideAction(PlayerGameView view, RummyRules rules);
}
