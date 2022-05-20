package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TransactionContext;

import java.util.ArrayList;
import java.util.Collections;
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
        LockContext parentContext = lockContext.parentContext();
        LockType effectiveLockType = lockContext.getEffectiveLockType(transaction);
        LockType explicitLockType = lockContext.getExplicitLockType(transaction);

        // TODO(proj4_part2): implement
        if (requestType == LockType.NL || LockType.substitutable(effectiveLockType, requestType)) return;

        if (explicitLockType == LockType.IX && requestType == LockType.S) {
            lockContext.promote(transaction, LockType.SIX);
        } else if (explicitLockType.isIntent()) {
            lockContext.escalate(transaction);
        }

        ensureSufficientAncestors(lockContext, requestType);

        LockType newLockType = lockContext.getExplicitLockType(transaction);
        if (lockContext.getEffectiveLockType(transaction) == requestType) return;
        if (newLockType == LockType.NL) {
            lockContext.acquire(transaction, requestType);
        } else if (newLockType == LockType.S && requestType == LockType.X) {
            lockContext.promote(transaction, requestType);
        }
    }

    private static void ensureSufficientAncestors(LockContext lockContext, LockType requestType) {

        LockContext parentContext = lockContext.parentContext();
        TransactionContext transaction = TransactionContext.getTransaction();
        if (parentContext == null) return;

        ResourceName currResource = lockContext.getResourceName();
        List<ResourceName> ancestors = new ArrayList<>();
        currResource = currResource.parent();
        while (currResource != null) {
            ancestors.add(currResource);
            currResource = currResource.parent();
        }
        Collections.reverse(ancestors);

        for (ResourceName rName : ancestors) {
            LockContext currContext = LockContext.fromResourceName(lockContext.lockman, rName);
            LockType parentLockType = currContext.getExplicitLockType(transaction);
            if (LockType.canBeParentLock(parentLockType, requestType)) continue;
            if (requestType == LockType.X) {
                if (parentLockType == LockType.S) {
                    currContext.promote(transaction, LockType.SIX);
                } else if (parentLockType == LockType.IS) {
                    currContext.promote(transaction, LockType.IX);
                } else {
                    currContext.acquire(transaction, LockType.IX);
                }
            } else if (requestType == LockType.S) {
                currContext.acquire(transaction, LockType.IS);
            }
        }
    }

    // TODO(proj4_part2) add any helper methods you want
}
