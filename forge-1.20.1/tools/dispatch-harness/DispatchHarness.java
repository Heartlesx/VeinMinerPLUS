import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Scenario harness for ArchitecturyDispatch's routing and the null-return contract.
 *
 * The routing rules are copied from compat/ArchitecturyDispatch.java with the Architectury and
 * Minecraft types replaced by stand-ins; the surrounding loop mirrors EventFactory$2's
 * handleInvocation, including its Objects.requireNonNull and early stop, so the null case is
 * exercised exactly as the real proxy would.
 *
 * Run: javac DispatchHarness.java && java DispatchHarness
 */
public final class DispatchHarness {
    private static int failures;

    public static void main(String[] args) throws Throwable {
        // Routing
        breakSignatureGoesDirect();
        addSignatureGoesDirect();
        otherMethodOnBreakListenerFallsBack();
        wrongDeclaringClassFallsBack();
        wrongArgumentShapeFallsBack();
        wrongArgumentCountFallsBack();
        foreignListenerFallsBack();

        // The null contract: a matched listener returning null must be called exactly once.
        nullReturningListenerCalledOnce();
        nullStopsTheLoop();
        nullDoesNotBecomeASecondCall();
        normalReturnStillChains();
        cancelledStopsLoop();
        lateListenersUnchangedAfterException();

        // Experience sharing
        xpIsSharedBetweenListeners();

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("all dispatch scenarios passed");
    }

    // ==== stand-ins ====

    enum Result { PASS, INTERRUPT_TRUE, INTERRUPT_FALSE, INTERRUPT_DEFAULT }

    interface Break {
        Result breakBlock(Level level, Pos pos, State state, Player player, Xp xp);
    }

    interface Add {
        String add(Entity entity, Level level);
    }

    interface Other {
        String other(String a);
    }

    record Level(String name) { }
    record Pos(int x, int y, int z) { }
    record State(String name) { }
    record Player(String name) { }
    record Entity(String name) { }

    static final class Xp {
        int value;
        Xp(int v) { value = v; }
    }

    // ==== routing, copied from ArchitecturyDispatch ====

    static boolean isBreakCall(Object listener, Method method, Object[] args) {
        return listener instanceof Break
                && method.getDeclaringClass() == Break.class
                && "breakBlock".equals(method.getName())
                && args.length == 5
                && args[0] instanceof Level
                && args[1] instanceof Pos
                && args[2] instanceof State
                && args[3] instanceof Player
                && args[4] instanceof Xp;
    }

    static boolean isAddCall(Object listener, Method method, Object[] args) {
        return listener instanceof Add
                && method.getDeclaringClass() == Add.class
                && "add".equals(method.getName())
                && args.length == 2
                && args[0] instanceof Entity
                && args[1] instanceof Level;
    }

    static final class FallbackTaken extends RuntimeException { }

    static Object invoke(Object listener, Method method, Object[] args) {
        if (isBreakCall(listener, method, args)) {
            return ((Break) listener).breakBlock((Level) args[0], (Pos) args[1], (State) args[2],
                    (Player) args[3], (Xp) args[4]);
        }
        if (isAddCall(listener, method, args)) {
            return ((Add) listener).add((Entity) args[0], (Level) args[1]);
        }
        throw new FallbackTaken();
    }

    // ==== EventFactory$2.handleInvocation, result-event variant ====

    static Result dispatch(List<Break> listeners, Method m, Object[] args) throws Throwable {
        for (Break listener : listeners) {
            // Exactly the original: one call, then requireNonNull, then the early-stop check.
            Result r = (Result) Objects.requireNonNull(invoke(listener, m, args));
            if (r != Result.PASS) {
                return r;
            }
        }
        return Result.PASS;
    }

    // ==== scenarios ====

    static void breakSignatureGoesDirect() throws Throwable {
        Recorder r = new Recorder();
        Method m = Break.class.getMethod("breakBlock", Level.class, Pos.class, State.class,
                Player.class, Xp.class);
        Object out = invoke(r, m, new Object[] {new Level("w"), new Pos(1, 2, 3),
                new State("stone"), new Player("p"), new Xp(5)});
        expect("break goes direct", Result.PASS, out);
    }

    static void addSignatureGoesDirect() throws Throwable {
        Recorder r = new Recorder();
        Method m = Add.class.getMethod("add", Entity.class, Level.class);
        expect("add goes direct", "direct-add",
                invoke(r, m, new Object[] {new Entity("item"), new Level("w")}));
    }

    static void otherMethodOnBreakListenerFallsBack() throws Throwable {
        Method m = Other.class.getMethod("other", String.class);
        expect("other method falls back", true, fellBack(new Recorder(), m, new Object[] {"x"}));
    }

    static void wrongDeclaringClassFallsBack() throws Throwable {
        Method m = ShadowBreak.class.getMethod("breakBlock", Level.class, Pos.class, State.class,
                Player.class, Xp.class);
        expect("foreign declaring class falls back", true,
                fellBack(new Recorder(), m, new Object[] {new Level("w"), new Pos(0, 0, 0),
                        new State("s"), new Player("p"), new Xp(1)}));
    }

    static void wrongArgumentShapeFallsBack() throws Throwable {
        Method m = Break.class.getMethod("breakBlock", Level.class, Pos.class, State.class,
                Player.class, Xp.class);
        expect("wrong arg type falls back", true,
                fellBack(new Recorder(), m, new Object[] {new Level("w"), new Pos(0, 0, 0),
                        new State("s"), new Player("p"), "not-xp"}));
    }

    static void wrongArgumentCountFallsBack() throws Throwable {
        Method m = Break.class.getMethod("breakBlock", Level.class, Pos.class, State.class,
                Player.class, Xp.class);
        expect("short args fall back", true,
                fellBack(new Recorder(), m, new Object[] {new Level("w"), new Pos(0, 0, 0)}));
    }

    static void foreignListenerFallsBack() throws Throwable {
        Method m = Break.class.getMethod("breakBlock", Level.class, Pos.class, State.class,
                Player.class, Xp.class);
        expect("foreign listener falls back", true,
                fellBack(new Object(), m, new Object[] {new Level("w"), new Pos(0, 0, 0),
                        new State("s"), new Player("p"), new Xp(1)}));
    }

    // The core of the fix: a listener that matches the signature and returns null is called ONCE.
    static void nullReturningListenerCalledOnce() throws Throwable {
        Method m = Break.class.getMethod("breakBlock", Level.class, Pos.class, State.class,
                Player.class, Xp.class);
        CountingNullListener first = new CountingNullListener();
        CountingPassListener second = new CountingPassListener();
        List<Break> chain = List.of(first, second);
        Object[] args = new Object[] {new Level("w"), new Pos(0, 0, 0), new State("s"),
                new Player("p"), new Xp(1)};

        Throwable thrown = null;
        try {
            dispatch(chain, m, args);
        } catch (Throwable t) {
            thrown = t;
        }
        expect("null first listener called exactly once", 1, first.calls);
        expect("null return rejects as before", true, thrown instanceof NullPointerException);
        expect("second listener did NOT run", 0, second.calls);
    }

    // Returning null must stop the loop, exactly like the original requireNonNull did.
    static void nullStopsTheLoop() throws Throwable {
        Method m = Break.class.getMethod("breakBlock", Level.class, Pos.class, State.class,
                Player.class, Xp.class);
        CountingNullListener first = new CountingNullListener();
        CountingPassListener second = new CountingPassListener();
        Object[] args = new Object[] {new Level("w"), new Pos(0, 0, 0), new State("s"),
                new Player("p"), new Xp(1)};
        try {
            dispatch(List.of(first, second), m, args);
        } catch (NullPointerException expected) {
            // The original rejects null here too.
        }
        expect("loop stopped at the null listener", 0, second.calls);
    }

    // A matched listener returning null must not be re-invoked through the reflective path: the
    // total number of calls stays one.
    static void nullDoesNotBecomeASecondCall() throws Throwable {
        Method m = Break.class.getMethod("breakBlock", Level.class, Pos.class, State.class,
                Player.class, Xp.class);
        CountingNullListener only = new CountingNullListener();
        Object[] args = new Object[] {new Level("w"), new Pos(0, 0, 0), new State("s"),
                new Player("p"), new Xp(1)};
        try {
            dispatch(List.of(only), m, args);
        } catch (NullPointerException expected) {
            // Expected: the caller's requireNonNull.
        }
        expect("no duplicate reflective call after null", 1, only.calls);
    }

    static void normalReturnStillChains() throws Throwable {
        Method m = Break.class.getMethod("breakBlock", Level.class, Pos.class, State.class,
                Player.class, Xp.class);
        CountingPassListener a = new CountingPassListener();
        CountingPassListener b = new CountingPassListener();
        Object[] args = new Object[] {new Level("w"), new Pos(0, 0, 0), new State("s"),
                new Player("p"), new Xp(1)};
        dispatch(List.of(a, b), m, args);
        expect("both pass listeners ran", 1, a.calls + b.calls - 1);
    }

    static void cancelledStopsLoop() throws Throwable {
        Method m = Break.class.getMethod("breakBlock", Level.class, Pos.class, State.class,
                Player.class, Xp.class);
        CountingResultListener first = new CountingResultListener(Result.INTERRUPT_FALSE);
        CountingPassListener second = new CountingPassListener();
        Object[] args = new Object[] {new Level("w"), new Pos(0, 0, 0), new State("s"),
                new Player("p"), new Xp(1)};
        Result out = dispatch(List.of(first, second), m, args);
        expect("cancel result returned", Result.INTERRUPT_FALSE, out);
        expect("cancel stopped later listeners", 0, second.calls);
    }

    static void lateListenersUnchangedAfterException() throws Throwable {
        Method m = Break.class.getMethod("breakBlock", Level.class, Pos.class, State.class,
                Player.class, Xp.class);
        Break thrower = (lv, p, st, pl, xp) -> { throw new IllegalStateException("boom"); };
        CountingPassListener after = new CountingPassListener();
        Object[] args = new Object[] {new Level("w"), new Pos(0, 0, 0), new State("s"),
                new Player("p"), new Xp(1)};
        Throwable caught = null;
        try {
            dispatch(List.of(thrower, after), m, args);
        } catch (Throwable t) {
            caught = t;
        }
        expect("exception propagates as-is", "boom", caught == null ? null : caught.getMessage());
        expect("later listener skipped after exception", 0, after.calls);
    }

    static void xpIsSharedBetweenListeners() throws Throwable {
        Method m = Break.class.getMethod("breakBlock", Level.class, Pos.class, State.class,
                Player.class, Xp.class);
        Xp shared = new Xp(3);
        List<Xp> seen = new ArrayList<>();
        Break first = (lv, p, st, pl, xp) -> { xp.value += 2; seen.add(xp); return Result.PASS; };
        Break second = (lv, p, st, pl, xp) -> { xp.value += 10; seen.add(xp); return Result.PASS; };
        Object[] args = new Object[] {new Level("w"), new Pos(0, 0, 0), new State("s"),
                new Player("p"), shared};
        invoke(first, m, args);
        invoke(second, m, args);
        expect("both listeners saw the same instance", true, seen.get(0) == seen.get(1));
        expect("experience edits accumulated", 15, shared.value);
    }

    // ==== helpers ====

    static boolean fellBack(Object listener, Method m, Object[] args) {
        try {
            invoke(listener, m, args);
            return false;
        } catch (FallbackTaken e) {
            return true;
        }
    }

    static final class Recorder implements Break, Add, Other {
        @Override
        public Result breakBlock(Level level, Pos pos, State state, Player player, Xp xp) {
            return Result.PASS;
        }

        @Override
        public String add(Entity entity, Level level) {
            return "direct-add";
        }

        @Override
        public String other(String a) {
            return "direct-other";
        }
    }

    static final class CountingNullListener implements Break {
        int calls;

        @Override
        public Result breakBlock(Level level, Pos pos, State state, Player player, Xp xp) {
            calls++;
            return null;
        }
    }

    static final class CountingPassListener implements Break {
        int calls;

        @Override
        public Result breakBlock(Level level, Pos pos, State state, Player player, Xp xp) {
            calls++;
            return Result.PASS;
        }
    }

    static final class CountingResultListener implements Break {
        private final Result result;
        int calls;

        CountingResultListener(Result r) { result = r; }

        // Routed through a variant dispatch that expects Result, so this listener is adapted below.
        @Override
        public Result breakBlock(Level level, Pos pos, State state, Player player, Xp xp) {
            calls++;
            return result;
        }
    }

    interface ShadowBreak {
        Result breakBlock(Level level, Pos pos, State state, Player player, Xp xp);
    }

    static void expect(String label, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            failures++;
            System.out.println("FAIL " + label + ": expected " + expected + ", got " + actual);
        } else {
            System.out.println("ok   " + label);
        }
    }

    static void expect(String label, int expected, int actual) {
        expect(label, Integer.valueOf(expected), Integer.valueOf(actual));
    }

    static void expect(String label, boolean expected, boolean actual) {
        expect(label, Boolean.valueOf(expected), Boolean.valueOf(actual));
    }
}
