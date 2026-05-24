package edu.berkeley.cs186.database.concurrency;

/**
 * Utility methods to track the relationships between different lock types.
 */
public enum LockType {
    S,   // shared
    X,   // exclusive
    IS,  // intention shared
    IX,  // intention exclusive
    SIX, // shared intention exclusive
    NL;  // no lock held

    /**
     * This method checks whether lock types A and B are compatible with
     * each other. If a transaction can hold lock type A on a resource
     * at the same time another transaction holds lock type B on the same
     * resource, the lock types are compatible.
     *
     * FOR TWO DIFFERENT TRANSACTIONS.
     */
    public static boolean compatible(LockType a, LockType b) {
        if (a == null || b == null) {
            throw new NullPointerException("null lock type");
        }

        switch (a) {
            // compatible with anything
            case NL:
                return true;

            // a = IS: means intent to readonly in children
            // b = NL: ok
            // b = IX: still ok, since this node only advertise intent
            // if children have conflict S and X, will be handled in children instead
            // b = SIX: = S + IX. S (true) ^ IX (true) = true --> true
            // b = X: no
            case IS:
                return b != X;

            // a = IX: intent to exclusively lock below
            // b = NL: ok
            // b = IX: ok
            // b = IS: ok (explained above)
            // b = S: no. S readonly the whole subtree. IX wants to xlock children.
            // b == X: no. X own entire subtree. IX says "i am going to change something to X below"
            // and that node will have X, which conflicts with current X
            case IX:
                return b == NL || b == IS || b == IX;

            // a = S: readonly entire node
            // b = NL: true
            // b = IS: readonly below. ok
            // b = S: ok
            // b = IX: no. entire subtree readonly. IX says some children can write.
            // DOES NOT BECOME SIX. SIX is reserved for ONE transaction. a = S and b = IX are two transactions.
            // b = SIX: S ^ IX = true ^ false = false.
            // b = X: hell no
            case S:
                return b == NL || b == IS || b == S;

            // a = SIX: S + IX
            // b = NL: true
            // b = IS: true. S and IX both compatible with IS
            // b = IX: a = S, b = IX wrong
            // b = S: a = IX, b = S wrong
            // b = X: hell no
            case SIX:
                return b == NL || b == IS;

            // a = X: not compatible with anything except NL
            case X:
                return b == NL;
        }

        // unreachable
        return false;
    }

    /**
     * This method returns the lock on the parent resource
     * that should be requested for a lock of type A to be granted.
     */
    public static LockType parentLock(LockType a) {
        if (a == null) {
            throw new NullPointerException("null lock type");
        }
        switch (a) {
        case S: return IS;
        case X: return IX;
        case IS: return IS;
        case IX: return IX;
        case SIX: return IX;
        case NL: return NL;
        default: throw new UnsupportedOperationException("bad lock type");
        }
    }

    /**
     * This method returns if parentLockType has permissions to grant a childLockType
     * on a child.
     *
     * FOR LOCK WITHIN SAME TRANSACTIONS.
     */
    public static boolean canBeParentLock(LockType parentLockType, LockType childLockType) {
        if (parentLockType == null || childLockType == null) {
            throw new NullPointerException("null lock type");
        }

        switch (parentLockType) {
            // NL cannot be parent of anything except NL
            case NL:
                return childLockType == NL;

            // child = NL: ok
            // child = IS: ok
            // child = IX: conflict. parent = IS means "all children below have at most S authority".
            // IX says some children can have X.
            // child = S: ok
            // child = SIX: no. parent = IS child = IX no.
            // child = X: no
            case IS:
                return childLockType == NL || childLockType == IS || childLockType == S;

            // child = NL: ok
            // child = IS: ok
            // child = IX: ok
            // child = S: ok. IX means "children below can be both S and X"
            // child = SIX: ok since S and IX both ok
            // child = X: ok
            case IX:
                return true;

            // child = NL: ok
            // child = IS: ok
            // child = IX: no. parent S mean readonly. IX mean some child going to modify.
            // if want SIX escalate later.
            // child = S: ok
            // child = SIX: parent = S child = IX not ok
            // child = X: hell no
            case S:
                return childLockType == NL || childLockType == IS || childLockType == S;

            // child == NL: ok
            // child == IS: ok. IX > IS
            // child == IX: ok. SIX allows restrictive updates in children.
            // child = S: ok
            // child = SIX: ok. still readonly on child and IX somewhere below.
            // child = X: ok. six allow changes
            case SIX:
                return true;

            // child = NL: ok
            // child = IS: no
            // child = IX: no. X grabs entire subtree. IX says some children can modify
            // child = S: no
            // child = SIX: no
            // child = X: no
            case X:
                return childLockType == NL;
        }

        // should be unreachable
        return false;
    }

    /**
     * This method returns whether a lock can be used for a situation
     * requiring another lock (e.g. an S lock can be substituted with
     * an X lock, because an X lock allows the transaction to do everything
     * the S lock allowed it to do).
     */
    public static boolean substitutable(LockType substitute, LockType required) {
        if (required == null || substitute == null) {
            throw new NullPointerException("null lock type");
        }

        // trying to: substitute "required" with "substitute"
        // question: can "substitute" do anything that "required" can?
        switch (required) {
            case NL:
                return true; // every lock have greater permission than NL

            // IS: intent to readonly
            // every lock have permission >= IS
            // except NL
            case IS:
                return substitute != NL;

            // IX: intent to xlock child
            // subs = IS: no. does not allow child locking
            // subs = IX: ok
            // subs = S: no. does not allow child locking
            // subs = SIX: ok
            // subs = X: ok
            case IX:
                return substitute != NL && substitute != IS && substitute != S;

            // S: readonly permission
            // subs = IS: no. doesn't give read permission
            // subs = IX: no. same as IS
            // subs = S: ok
            // subs = SIX: ok
            // subs = X: ok
            case S:
                return substitute == S || substitute == SIX || substitute == X;

            // SIX: readonly + xlock child
            // subs = IS: no. doesn't give read permission
            // subs = IX: no. same as IS
            // subs = S: no. doesn't allow child locking
            // subs = SIX: ok
            // subs = X: ok
            case SIX:
                return substitute == SIX || substitute == X;

            // subs = X only
            case X:
                return substitute == X;
        }

        // unreachable
        return false;
    }

    /**
     * @return True if this lock is IX, IS, or SIX. False otherwise.
     */
    public boolean isIntent() {
        return this == LockType.IX || this == LockType.IS || this == LockType.SIX;
    }

    public boolean isExclusive() {
        return this == LockType.X || this == LockType.IX || this == LockType.SIX;
    }

    @Override
    public String toString() {
        switch (this) {
        case S: return "S";
        case X: return "X";
        case IS: return "IS";
        case IX: return "IX";
        case SIX: return "SIX";
        case NL: return "NL";
        default: throw new UnsupportedOperationException("bad lock type");
        }
    }
}

