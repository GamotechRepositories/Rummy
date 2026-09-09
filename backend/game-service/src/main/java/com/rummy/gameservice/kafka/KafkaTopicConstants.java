package com.rummy.gameservice.kafka;

public final class KafkaTopicConstants {

    private KafkaTopicConstants() {}

    public static final String TOPIC_GAME_EVENTS = "rummy.game.events";
    public static final String TOPIC_AUDIT_EVENTS = "rummy.audit.events";
    public static final String TOPIC_ANALYTICS_EVENTS = "rummy.analytics.events";
    public static final String TOPIC_NOTIFICATIONS = "rummy.player.notifications";

    public static final String GROUP_ANALYTICS = "rummy-analytics-consumer-group";
    public static final String GROUP_AUDIT = "rummy-audit-consumer-group";
}
