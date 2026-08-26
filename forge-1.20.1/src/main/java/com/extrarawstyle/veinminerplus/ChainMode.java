package com.extrarawstyle.veinminerplus;

public enum ChainMode {
    NORMAL("chain.veinminerplus.normal"),
    AREA_1X1("chain.veinminerplus.area_1x1"),
    AREA_3X3("chain.veinminerplus.area_3x3"),
    BLAST_SAME("chain.veinminerplus.blast_same"),
    BLAST_ORES("chain.veinminerplus.blast_ores"),
    BLAST_ANY("chain.veinminerplus.blast_any"),
    BLAST_LOGS("chain.veinminerplus.blast_logs");

    private final String translationKey;

    ChainMode(String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return translationKey;
    }

    public static ChainMode fromOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : NORMAL;
    }

    public static ChainMode cycle(ChainMode current, int direction) {
        ChainMode[] modes = values();
        int next = Math.floorMod(current.ordinal() + direction, modes.length);
        return modes[next];
    }

    public boolean isArea() {
        return this == AREA_1X1 || this == AREA_3X3;
    }

    public boolean isBlast() {
        return this == BLAST_SAME || this == BLAST_ORES || this == BLAST_ANY || this == BLAST_LOGS;
    }
}
