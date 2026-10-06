package com.rummy.gameservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

@SpringBootApplication
public class GameServiceApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(GameServiceApplication.class, args);
    }

    private static void loadDotEnv() {
        File[] candidatePaths = new File[] {
            new File(".env"),
            new File("../.env"),
            new File("../../.env")
        };
        for (File file : candidatePaths) {
            if (file.exists() && file.isFile()) {
                try {
                    List<String> lines = Files.readAllLines(file.toPath());
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                            int eqIdx = trimmed.indexOf('=');
                            String key = trimmed.substring(0, eqIdx).trim();
                            String val = trimmed.substring(eqIdx + 1).trim();
                            // application.yml resolves MONGODB_URI / PORT placeholders from these properties.
                            // Never set spring.* directly: system properties outrank real env vars.
                            if (System.getProperty(key) == null && System.getenv(key) == null) {
                                System.setProperty(key, val);
                            }
                        }
                    }
                    System.out.println("[Environment] Loaded environment configuration from: " + file.getAbsolutePath());
                    break;
                } catch (Exception e) {
                    System.err.println("[Environment] Warning: Failed to read .env file: " + e.getMessage());
                }
            }
        }
    }
}
