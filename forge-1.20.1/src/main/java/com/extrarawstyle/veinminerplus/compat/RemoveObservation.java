package com.extrarawstyle.veinminerplus.compat;

import java.util.ArrayDeque;
import java.util.Deque;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;

/**
 * Records what vanilla's own removal call reported for one chained block.
 *
 * {@code ServerPlayerGameMode.destroyBlock} saves {@code removeBlock}'s boolean to decide
 * {@code playerDestroy} and the experience drop, but the survival branch still returns {@code true}
 * at the end. A {@code true} from {@code destroyBlock} therefore means "this code path ran", not
 * "the block was removed", so a refused removal can look like a success to the chain counter.
 *
 * The mixin reports every {@code removeBlock} return for the game mode instance, and this class
 * decides which of those belongs to the chained block being broken:
 *
 * - The attempt is bound to the level, the player, the game-mode instance and the position, so a
 *   removal at the same coordinates in another world, or by another player, cannot be mistaken for
 *   it.
 * - The outermost removal seen while the attempt is open is the main call and decides the result.
 *   Anything reported while that call is still running is a nested removal - a listener breaking
 *   another block, or the same block again - and is counted separately instead of overwriting the
 *   main call's answer. A later report can never turn a refusal into a success.
 * - The scope is restored in a {@code finally} block, so a throw inside the removal (or inside a
 *   callback that swallows it) cannot leave a stale attempt behind.
 *
 * Nothing here decides whether a block counts: {@link #stateOf} reports what was seen and the caller
 * applies the policy.
 */
public final class RemoveObservation {

    /** What the observed removal call reported for one chained break. */
    public enum State {
        // No removal call reached this attempt. That covers a break refused before removal (a
        // cancelled event, a game-master block, a tool that broke first) - and also a patch that did
        // not load, which is why the caller must not read this as "verified anything".
        UNOBSERVED,
        // The main removal call ran and reported the block as removed.
        REMOVED,
        // The main removal call ran and reported that the block stayed.
        NOT_REMOVED
    }

    private static final ThreadLocal<Deque<Attempt>> FRAMES =
            ThreadLocal.withInitial(ArrayDeque::new);
    // Reused between blocks, so a 512-block tick does not allocate one attempt per block.
    private static final ThreadLocal<Deque<Attempt>> POOL =
            ThreadLocal.withInitial(ArrayDeque::new);

    // Set by the mixin plugin once the patch is applied to the target class. This is a fact about
    // the loaded class, not an inference from observed blocks, so it can be trusted before the first
    // chain runs.
    private static volatile boolean patchApplied;

    private RemoveObservation() {
    }

    /**
     * True when the patch was applied to the game's removal path.
     *
     * Set by the mixin plugin from {@code postApply}: reaching that hook means the injections were
     * applied, because a missing injection point throws before it runs. This is deliberately not
     * derived from observed blocks - "no removal was observed" has several innocent explanations
     * (a cancelled event, a game-master block, a tool that broke first) and none of them means the
     * patch is missing.
     */
    public static boolean isPatchApplied() {
        return patchApplied;
    }

    /** Called by the mixin plugin after a successful apply. */
    public static void markPatchApplied() {
        patchApplied = true;
    }

    /**
     * Opens an attempt for one chained break.
     *
     * The level, player and game mode are the full identity of the call this attempt expects; a
     * removal reported for anything else is ignored rather than attributed here.
     */
    public static Attempt begin(ServerLevel level, ServerPlayer player, BlockPos pos) {
        Deque<Attempt> pool = POOL.get();
        Attempt attempt = pool.isEmpty() ? new Attempt() : pool.pop();
        attempt.level = level;
        attempt.player = player;
        attempt.gameMode = player.gameMode;
        attempt.pos = pos;
        attempt.depth = 0;
        attempt.state = State.UNOBSERVED;
        attempt.nestedReports = 0;
        FRAMES.get().push(attempt);
        return attempt;
    }

    /**
     * Closes an attempt. The caller reads the result with {@link #stateOf} first.
     *
     * Safe to call from a {@code finally} block: the attempt is always removed from the stack, so an
     * exception inside the break cannot leave a stale frame that would swallow the next block's
     * observation.
     */
    public static void end(Attempt attempt) {
        Deque<Attempt> frames = FRAMES.get();
        if (frames.peek() == attempt) {
            frames.pop();
        } else {
            // An out-of-order close must not corrupt the stack; remove just this frame.
            frames.remove(attempt);
        }
        attempt.level = null;
        attempt.player = null;
        attempt.gameMode = null;
        attempt.pos = null;
        attempt.depth = 0;
        attempt.state = State.UNOBSERVED;
        attempt.nestedReports = 0;
        POOL.get().push(attempt);
    }

    /** What the main removal call reported. Read before {@link #end}. */
    public static State stateOf(Attempt attempt) {
        return attempt.state;
    }

    /**
     * How many removals were reported while the main call was still running.
     *
     * These belong to whatever the block's own removal triggered, not to the chained block. A
     * non-zero count means the block's break ran third-party callback code that broke blocks, which
     * is worth recording and never worth counting as this block's success.
     */
    public static int nestedReports(Attempt attempt) {
        return attempt.nestedReports;
    }

    /**
     * Called by the mixin when a game mode's removal is entered.
     *
     * Matches on the game-mode instance and the position. The level and player carried by the
     * attempt are checked too when they are known, so a removal in another world still cannot be
     * attributed here even though the mixin cannot read those fields itself.
     */
    public static void enter(ServerPlayerGameMode gameMode, BlockPos pos) {
        Attempt attempt = currentAttempt();
        if (attempt == null || !attempt.matches(gameMode)) {
            return;
        }
        if (attempt.pos.equals(pos)) {
            attempt.depth++;
        }
    }

    /**
     * Called by the mixin when a game mode's removal returns.
     *
     * The outermost matching return decides the attempt's result. A return from a nested removal is
     * counted and ignored, so the inner call can never overwrite the outer answer.
     */
    public static void exit(ServerPlayerGameMode gameMode, BlockPos pos, boolean removed) {
        Attempt attempt = currentAttempt();
        if (attempt == null || !attempt.matches(gameMode)) {
            return;
        }
        if (!attempt.pos.equals(pos)) {
            // A removal at another position started from inside this block's break.
            attempt.nestedReports++;
            return;
        }
        if (attempt.depth > 1) {
            // The main call is still on the stack: this is a removal it triggered.
            attempt.depth--;
            attempt.nestedReports++;
            return;
        }
        if (attempt.depth == 1) {
            attempt.depth--;
            if (attempt.state == State.UNOBSERVED) {
                attempt.state = removed ? State.REMOVED : State.NOT_REMOVED;
            } else {
                // A second outermost call for the same block: the first observation stands and this
                // one is recorded rather than allowed to replace it.
                attempt.nestedReports++;
            }
        }
    }

    /** Called by the mixin when a removal exits abnormally, to unwind the depth it added. */
    public static void exitAbnormally(ServerPlayerGameMode gameMode, BlockPos pos) {
        Attempt attempt = currentAttempt();
        if (attempt == null || !attempt.matches(gameMode)) {
            return;
        }
        if (attempt.pos.equals(pos) && attempt.depth > 0) {
            attempt.depth--;
        }
    }

    private static Attempt currentAttempt() {
        Deque<Attempt> frames = FRAMES.get();
        return frames.isEmpty() ? null : frames.peek();
    }

    /** One chained break's observation window. Reused between blocks. */
    public static final class Attempt {
        private ServerLevel level;
        private ServerPlayer player;
        private ServerPlayerGameMode gameMode;
        private BlockPos pos;
        // Removals of this position currently on the stack. 1 means the main call is running.
        private int depth;
        private int nestedReports;
        private State state = State.UNOBSERVED;

        private Attempt() {
        }

        private boolean matches(ServerPlayerGameMode candidateMode) {
            // Identity, not equality: the very game mode instance the attempt was opened for. The
            // level and player it also carries are compared when the caller supplies them.
            return candidateMode == gameMode;
        }
    }
}
