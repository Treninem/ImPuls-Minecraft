package ru.impuls.core;

import java.util.Locale;

public enum RankTier {
    H(0), G(1), F(2), E(3), D(4), C(5), B(6), A(7), S(8), SS(9), SSS(10), SSS_PLUS(11);

    private final int index;
    RankTier(int index) { this.index = index; }
    public int index() { return index; }
    public String display() { return this == SSS_PLUS ? "SSS+" : name(); }
    public static RankTier fromIndex(int value) {
        int i = Math.max(0, Math.min(values().length - 1, value));
        return values()[i];
    }
    public static RankTier parse(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace("+", "_PLUS");
        return valueOf(normalized);
    }
}
