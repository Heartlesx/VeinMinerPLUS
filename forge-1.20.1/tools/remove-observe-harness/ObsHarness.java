import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Scenario harness for the removal observation's attribution rules.
 *
 * The state machine below is a line-for-line copy of the decision logic in
 * compat/RemoveObservation.java, with the Minecraft types replaced by stand-ins so it can run without
 * the game on the classpath. It is kept in sync by hand and must be updated with the original.
 *
 * Run: javac ObsHarness.java && java ObsHarness
 */
public final class ObsHarness {
    private static int failures;

    public static void main(String[] args) {
        // Core three-state outcomes.
        mainCallRemoved();
        mainCallRefused();
        noRemovalObserved();

        // Attribution: only the outer call decides.
        nestedSamePosition();
        nestedOtherPosition();
        repeatedOutermostCall();
        otherGameModeIgnored();
        otherPositionNotCounted();

        // Scope recovery.
        exceptionRestoresScope();
        unbalancedCloseKeepsStack();
        pooledAttemptIsClean();

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("all observation scenarios passed");
    }

    static final class GM {
        final String name;
        GM(String n) { name = n; }
        @Override public String toString() { return name; }
    }

    static final class Pos {
        final int x, y, z;
        Pos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        @Override public boolean equals(Object o) {
            return o instanceof Pos p && p.x == x && p.y == y && p.z == z;
        }
        @Override public int hashCode() { return (x * 31 + y) * 31 + z; }
    }

    // ==== state machine copied from RemoveObservation ====

    enum State { UNOBSERVED, REMOVED, NOT_REMOVED }

    static final class Attempt {
        GM gameMode;
        Pos pos;
        int depth;
        int nestedReports;
        State state = State.UNOBSERVED;
    }

    static final class Obs {
        final Deque<Attempt> frames = new ArrayDeque<>();
        final Deque<Attempt> pool = new ArrayDeque<>();

        Attempt begin(GM gameMode, Pos pos) {
            Attempt a = pool.isEmpty() ? new Attempt() : pool.pop();
            a.gameMode = gameMode;
            a.pos = pos;
            a.depth = 0;
            a.state = State.UNOBSERVED;
            a.nestedReports = 0;
            frames.push(a);
            return a;
        }

        void end(Attempt a) {
            if (frames.peek() == a) {
                frames.pop();
            } else {
                frames.remove(a);
            }
            a.gameMode = null;
            a.pos = null;
            a.depth = 0;
            a.state = State.UNOBSERVED;
            a.nestedReports = 0;
            pool.push(a);
        }

        private Attempt current() {
            return frames.isEmpty() ? null : frames.peek();
        }

        void enter(GM gm, Pos pos) {
            Attempt a = current();
            if (a == null || gm != a.gameMode) return;
            if (a.pos.equals(pos)) a.depth++;
        }

        void exit(GM gm, Pos pos, boolean removed) {
            Attempt a = current();
            if (a == null || gm != a.gameMode) return;
            if (!a.pos.equals(pos)) { a.nestedReports++; return; }
            if (a.depth > 1) { a.depth--; a.nestedReports++; return; }
            if (a.depth == 1) {
                a.depth--;
                if (a.state == State.UNOBSERVED) {
                    a.state = removed ? State.REMOVED : State.NOT_REMOVED;
                } else {
                    a.nestedReports++;
                }
            }
        }
    }

    // ==== scenarios ====

    static void mainCallRemoved() {
        Obs o = new Obs();
        GM gm = new GM("main");
        Attempt a = o.begin(gm, new Pos(0, 0, 0));
        o.enter(gm, new Pos(0, 0, 0));
        o.exit(gm, new Pos(0, 0, 0), true);
        expect("main call removed", State.REMOVED, a.state);
        o.end(a);
    }

    static void mainCallRefused() {
        Obs o = new Obs();
        GM gm = new GM("main");
        Attempt a = o.begin(gm, new Pos(1, 0, 0));
        o.enter(gm, new Pos(1, 0, 0));
        o.exit(gm, new Pos(1, 0, 0), false);
        expect("main call refused", State.NOT_REMOVED, a.state);
        o.end(a);
    }

    static void noRemovalObserved() {
        Obs o = new Obs();
        Attempt a = o.begin(new GM("main"), new Pos(2, 0, 0));
        o.end(a);
        expect("no removal observed", State.UNOBSERVED, a.state);
    }

    // The main call refuses, but a listener inside it removes the same position and succeeds.
    // The outer answer must stand; this is the case a return-only hook cannot tell apart.
    static void nestedSamePosition() {
        Obs o = new Obs();
        GM gm = new GM("main");
        Pos p = new Pos(3, 0, 0);
        Attempt a = o.begin(gm, p);
        o.enter(gm, p);
        o.enter(gm, p);
        o.exit(gm, p, true);
        o.exit(gm, p, false);
        expect("nested same-position does not win", State.NOT_REMOVED, a.state);
        expect("nested same-position is counted", 1, a.nestedReports);
        o.end(a);
    }

    static void nestedOtherPosition() {
        Obs o = new Obs();
        GM gm = new GM("main");
        Pos p = new Pos(4, 0, 0);
        Attempt a = o.begin(gm, p);
        o.enter(gm, p);
        // A listener breaks a different block: it is reported without an entry for that position.
        o.exit(gm, new Pos(9, 9, 9), true);
        o.exit(gm, p, false);
        expect("other-position removal ignored", State.NOT_REMOVED, a.state);
        expect("other-position removal counted nested", 1, a.nestedReports);
        o.end(a);
    }

    // Two outermost calls for the same block: the first observation must not be replaced.
    static void repeatedOutermostCall() {
        Obs o = new Obs();
        GM gm = new GM("main");
        Pos p = new Pos(5, 0, 0);
        Attempt a = o.begin(gm, p);
        o.enter(gm, p);
        o.exit(gm, p, false);
        o.enter(gm, p);
        o.exit(gm, p, true);
        expect("first outermost answer stands", State.NOT_REMOVED, a.state);
        o.end(a);
    }

    static void otherGameModeIgnored() {
        Obs o = new Obs();
        GM mine = new GM("mine");
        GM theirs = new GM("theirs");
        Pos p = new Pos(6, 0, 0);
        Attempt a = o.begin(mine, p);
        o.enter(theirs, p);
        o.exit(theirs, p, true);
        expect("other game mode ignored", State.UNOBSERVED, a.state);
        o.end(a);
    }

    static void otherPositionNotCounted() {
        Obs o = new Obs();
        GM gm = new GM("main");
        Attempt a = o.begin(gm, new Pos(7, 0, 0));
        o.enter(gm, new Pos(7, 0, 0));
        o.exit(gm, new Pos(7, 0, 1), true);
        o.exit(gm, new Pos(7, 0, 0), false);
        expect("different position does not decide", State.NOT_REMOVED, a.state);
        o.end(a);
    }

    // A throw inside the main call: the caller closes the attempt in a finally, and the next block
    // must not inherit anything.
    static void exceptionRestoresScope() {
        Obs o = new Obs();
        GM gm = new GM("main");
        Pos p1 = new Pos(8, 0, 0);
        Attempt first = o.begin(gm, p1);
        o.enter(gm, p1);
        // The removal throws; the caller's finally block closes the attempt.
        o.end(first);
        expect("scope restored after exception", true, o.frames.isEmpty());

        Pos p2 = new Pos(8, 0, 1);
        Attempt second = o.begin(gm, p2);
        o.enter(gm, p2);
        o.exit(gm, p2, true);
        expect("next block unaffected", State.REMOVED, second.state);
        o.end(second);
    }

    static void unbalancedCloseKeepsStack() {
        Obs o = new Obs();
        GM gm = new GM("main");
        Attempt outer = o.begin(gm, new Pos(10, 0, 0));
        Attempt inner = o.begin(gm, new Pos(11, 0, 0));
        o.end(outer); // out-of-order close
        o.enter(gm, new Pos(11, 0, 0));
        o.exit(gm, new Pos(11, 0, 0), true);
        expect("inner still live after out-of-order close", State.REMOVED, inner.state);
        o.end(inner);
    }

    static void pooledAttemptIsClean() {
        Obs o = new Obs();
        GM gm = new GM("main");
        Attempt first = o.begin(gm, new Pos(12, 0, 0));
        o.enter(gm, new Pos(12, 0, 0));
        o.exit(gm, new Pos(12, 0, 0), true);
        o.end(first);

        Attempt second = o.begin(gm, new Pos(13, 0, 0));
        expect("reused attempt starts clean", State.UNOBSERVED, second.state);
        expect("reused attempt has no nested reports", 0, second.nestedReports);
        o.end(second);
    }

    static void expect(String label, State expected, State actual) {
        if (expected != actual) {
            failures++;
            System.out.println("FAIL " + label + ": expected " + expected + ", got " + actual);
        } else {
            System.out.println("ok   " + label);
        }
    }

    static void expect(String label, int expected, int actual) {
        if (expected != actual) {
            failures++;
            System.out.println("FAIL " + label + ": expected " + expected + ", got " + actual);
        } else {
            System.out.println("ok   " + label);
        }
    }

    static void expect(String label, boolean expected, boolean actual) {
        if (expected != actual) {
            failures++;
            System.out.println("FAIL " + label + ": expected " + expected + ", got " + actual);
        } else {
            System.out.println("ok   " + label);
        }
    }
}
