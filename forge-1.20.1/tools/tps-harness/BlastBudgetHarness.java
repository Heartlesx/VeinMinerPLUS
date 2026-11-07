package com.extrarawstyle.veinminerplus;

/** Regression checks for adaptive blast budgets. */
public final class BlastBudgetHarness {
    private static int failures;

    private BlastBudgetHarness() {
    }

    public static void main(String[] args) {
        breakBudgetUsesHalfRateBelow16Tps();
        breakBudgetUsesQuarterRateBelow12Tps();
        thresholdValuesStayInTheHigherBand();
        searchBudgetUsesTheSameRateBands();
        scanBudgetUsesTheSameRateBands();
        smallBudgetsNeverBecomeZero();

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("all blast budget scenarios passed");
    }

    private static void breakBudgetUsesHalfRateBelow16Tps() {
        expect("512 blocks at 15 TPS", 256, BlastBudget.scale(512, 15.0D));
    }

    private static void breakBudgetUsesQuarterRateBelow12Tps() {
        expect("512 blocks at 11 TPS", 128, BlastBudget.scale(512, 11.0D));
    }

    private static void thresholdValuesStayInTheHigherBand() {
        expect("16 TPS keeps full break budget", 512, BlastBudget.scale(512, 16.0D));
        expect("12 TPS keeps half break budget", 256, BlastBudget.scale(512, 12.0D));
    }

    private static void searchBudgetUsesTheSameRateBands() {
        expect("search checks at 15 TPS", 8192, BlastBudget.scale(16384, 15.0D));
        expect("search checks at 11 TPS", 4096, BlastBudget.scale(16384, 11.0D));
    }

    private static void scanBudgetUsesTheSameRateBands() {
        expect("scan budget at 15 TPS", 2_500_000L, BlastBudget.scale(5_000_000L, 15.0D));
        expect("scan budget at 11 TPS", 1_250_000L, BlastBudget.scale(5_000_000L, 11.0D));
    }

    private static void smallBudgetsNeverBecomeZero() {
        expect("minimum break budget", 1, BlastBudget.scale(1, 0.0D));
        expect("minimum scan budget", 1L, BlastBudget.scale(1L, 0.0D));
    }

    private static void expect(String label, int expected, int actual) {
        if (expected != actual) {
            failures++;
            System.out.println("FAIL " + label + ": expected " + expected + ", got " + actual);
        } else {
            System.out.println("ok   " + label);
        }
    }

    private static void expect(String label, long expected, long actual) {
        if (expected != actual) {
            failures++;
            System.out.println("FAIL " + label + ": expected " + expected + ", got " + actual);
        } else {
            System.out.println("ok   " + label);
        }
    }
}
