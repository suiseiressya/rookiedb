package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TransactionContext;

import java.util.ArrayList;
import java.util.List;

/**
 * LockUtil is a declarative layer which simplifies multigranularity lock
 * acquisition for the user (you, in the last task of Part 2). Generally
 * speaking, you should use LockUtil for lock acquisition instead of calling
 * LockContext methods directly.
 */
public class LockUtil {
    /**
     * Ensure that the current transaction can perform actions requiring
     * `requestType` on `lockContext`.
     *
     * `requestType` is guaranteed to be one of: S, X, NL.
     *
     * This method should promote/escalate/acquire as needed, but should only
     * grant the least permissive set of locks needed. We recommend that you
     * think about what to do in each of the following cases:
     * - The current lock type can effectively substitute the requested type
     * - The current lock type is IX and the requested lock is S
     * - The current lock type is an intent lock
     * - None of the above: In this case, consider what values the explicit
     *   lock type can be, and think about how ancestor looks will need to be
     *   acquired or changed.
     *
     * You may find it useful to create a helper method that ensures you have
     * the appropriate locks on all ancestors.
     */
    public static void ensureSufficientLockHeld(LockContext lockContext, LockType requestType) {
        // requestType must be S, X, or NL
        assert (requestType == LockType.S || requestType == LockType.X || requestType == LockType.NL);

        // Do nothing if the transaction or lockContext is null
        TransactionContext transaction = TransactionContext.getTransaction();
        if (transaction == null || lockContext == null) return;

        // You may find these variables useful
        LockContext parentContext = lockContext.getParentContext();
        LockType effectiveLockType = lockContext.getEffectiveLockType(transaction);
        LockType explicitLockType = lockContext.getExplicitLockType(transaction);

        // TODO(proj4_part2): implement
        // if can substitute: do nothing
        if (LockType.substitutable(effectiveLockType, requestType)) return;

        // if explicit = IX and request = S, promote directly to SIX
        // if ancestor have SIX, substitutable() above would have caught it
        // and S cannot be children of SIX
        if (explicitLockType == LockType.IX && requestType == LockType.S) {
            lockContext.promote(transaction, LockType.SIX);
            return;
        }

        // update all ancestors lock before changing current
        ensureAncestorLockSufficient(lockContext, requestType, transaction);

        // intent: escalate
        if (explicitLockType.isIntent()) {
            lockContext.escalate(transaction);

            // if escalated to S but requestType is X, promote
            // ancestors updated in ensureAncestorLockSufficient
            if (requestType == LockType.X && lockContext.getExplicitLockType(transaction) == LockType.S)
                lockContext.promote(transaction, requestType);
        }
        else if (explicitLockType == LockType.NL) lockContext.acquire(transaction, requestType);
        else if (explicitLockType == LockType.S) {
            // requestType only S, X, NL
            // S, NL caught above, only X left
            lockContext.promote(transaction, requestType);
        }
        // explicit = X: skip (highest lock possible)
    }

    // TODO(proj4_part2) add any helper methods you want
    public static void ensureAncestorLockSufficient(LockContext lockContext,
                                                    LockType requestType,
                                                    TransactionContext transaction) {
        LockType required = LockType.parentLock(requestType); // IS or IX

        List<LockContext> ancestors = new ArrayList<>();
        LockContext ctx = lockContext.getParentContext();

        while (ctx != null) {
            ancestors.add(ctx);
            ctx = ctx.getParentContext();
        }

        // update from top down
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            LockType current = ancestors.get(i).getExplicitLockType(transaction);
            if (current == LockType.NL) ancestors.get(i).acquire(transaction, required);
            else if (required == LockType.IX) {
                if (current == LockType.S) {
                    try {
                        ancestors.get(i).promote(transaction, LockType.SIX);
                    } catch (InvalidLockException e) {
                        ancestors.get(i).promote(transaction, LockType.X);
                    }
                }
                else if (current == LockType.IS) ancestors.get(i).promote(transaction, required);

                // current = X, IX, SIX: ok
            }
            // else: required = IS: current = anything is ok (NL handled above)
        }
    }
}
