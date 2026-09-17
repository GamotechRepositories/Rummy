package com.rummy.engine.bot;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pool of 1000 unique Indian display names for AI seats.
 * Bots should look like regular players — never use "Bot" / "AI" labels.
 */
public final class IndianBotNames {

    private static final String[] FIRST_NAMES = {
            "Aarav", "Vivaan", "Aditya", "Vihaan", "Arjun", "Sai", "Reyansh", "Ayaan", "Krishna", "Ishaan",
            "Shaurya", "Atharv", "Advik", "Pranav", "Aryan", "Kabir", "Ansh", "Rudra", "Yuvaan", "Dhruv",
            "Kartik", "Rohan", "Kunal", "Nikhil", "Rahul", "Amit", "Suresh", "Vikram", "Rajesh", "Sanjay",
            "Ananya", "Aadhya", "Diya", "Pari", "Anika", "Navya", "Myra", "Sara", "Aisha", "Kiara",
            "Isha", "Riya", "Saanvi", "Aarohi", "Meera", "Kavya", "Nisha", "Pooja", "Priya", "Neha"
    };

    private static final String[] LAST_NAMES = {
            "Sharma", "Verma", "Patel", "Singh", "Kumar", "Reddy", "Nair", "Iyer", "Mehta", "Joshi",
            "Gupta", "Malhotra", "Kapoor", "Chopra", "Bhat", "Desai", "Jain", "Rao", "Pillai", "Das"
    };

    /** Exactly 1000 unique "First Last" names (50 × 20). */
    private static final String[] NAMES;

    static {
        String[] built = new String[FIRST_NAMES.length * LAST_NAMES.length];
        int i = 0;
        for (String first : FIRST_NAMES) {
            for (String last : LAST_NAMES) {
                built[i++] = first + " " + last;
            }
        }
        List<String> shuffled = Arrays.asList(built);
        Collections.shuffle(shuffled, new java.util.Random(20260317L));
        NAMES = shuffled.toArray(new String[0]);
    }

    private IndianBotNames() {
    }

    public static int size() {
        return NAMES.length;
    }

    public static List<String> all() {
        return Collections.unmodifiableList(Arrays.asList(NAMES));
    }

    /** Random name from the full pool (may collide if called many times). */
    public static String random() {
        return NAMES[ThreadLocalRandom.current().nextInt(NAMES.length)];
    }

    /**
     * Picks a random unused name and adds it to {@code used}.
     * Falls back to a numeric suffix only if the entire pool is exhausted.
     */
    public static String nextUnique(Set<String> used) {
        Set<String> occupied = used != null ? used : new HashSet<>();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (int attempt = 0; attempt < NAMES.length * 3; attempt++) {
            String candidate = NAMES[rng.nextInt(NAMES.length)];
            if (occupied.add(candidate)) {
                return candidate;
            }
        }

        for (String candidate : NAMES) {
            if (occupied.add(candidate)) {
                return candidate;
            }
        }

        String fallback = NAMES[rng.nextInt(NAMES.length)] + " " + rng.nextInt(10, 99);
        occupied.add(fallback);
        return fallback;
    }

    public static String nextUnique(Collection<String> alreadyTaken) {
        Set<String> used = alreadyTaken == null ? new HashSet<>() : new HashSet<>(alreadyTaken);
        return nextUnique(used);
    }
}
