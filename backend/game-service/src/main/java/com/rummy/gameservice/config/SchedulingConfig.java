package com.rummy.gameservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs the periodic memory janitors (idle tables, rate-limit buckets, presence, tickets). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
