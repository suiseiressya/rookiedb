package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TimeoutScaling;
import edu.berkeley.cs186.database.TransactionContext;
import edu.berkeley.cs186.database.categories.HiddenTests;
import edu.berkeley.cs186.database.categories.Proj4Part2Tests;
import edu.berkeley.cs186.database.categories.Proj4Tests;
import edu.berkeley.cs186.database.categories.PublicTests;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@Category({Proj4Tests.class, Proj4Part2Tests.class})
public class TestLockContext {
    private LoggingLockManager lockManager;

    private LockContext dbLockContext;
    private LockContext tableLockContext;
    private LockContext pageLockContext;

    private TransactionContext[] transactions;

    // 1 second per test
    @Rule
    public TestRule globalTimeout = new DisableOnDebug(Timeout.millis((long) (
            1000 * TimeoutScaling.factor)));

    @Before
    public void setUp() {
        lockManager = new LoggingLockManager();
        /**
         * For all of these tests we have the following resource hierarchy
         *                     database
         *                        |
         *                      table1
         *                        |
         *                      page1
         */
        dbLockContext = lockManager.databaseContext();
        tableLockContext = dbLockContext.childContext("table1");
        pageLockContext = tableLockContext.childContext("page1");

        transactions = new TransactionContext[8];
        for (int i = 0; i < transactions.length; i++) {
            transactions[i] = new DummyTransactionContext(lockManager, i);
        }
    }

    @Test
    @Category(PublicTests.class)
    public void testSimpleAcquireFail() {
        dbLockContext.acquire(transactions[0], LockType.IS);
        try {
            tableLockContext.acquire(transactions[0], LockType.X);
            fail("Attempting to acquire an X lock with an IS lock on " +
                    "the parent should throw an InvalidLockException.");
        } catch (InvalidLockException e) {
            // do nothing
        }
    }

    @Test
    @Category(PublicTests.class)
    public void testSimpleAcquirePass() {
        dbLockContext.acquire(transactions[0], LockType.IS);
        tableLockContext.acquire(transactions[0], LockType.S);
        // both locks should have been acquired
        Assert.assertEquals(Arrays.asList(new Lock(dbLockContext.getResourceName(), LockType.IS, 0L),
                new Lock(tableLockContext.getResourceName(), LockType.S, 0L)),
                lockManager.getLocks(transactions[0]));
    }

    @Test
    @Category(PublicTests.class)
    public void testTreeAcquirePass() {
        dbLockContext.acquire(transactions[0], LockType.IX);
        tableLockContext.acquire(transactions[0], LockType.IS);
        pageLockContext.acquire(transactions[0], LockType.S);
        // all three locks should have been acquired
        Assert.assertEquals(Arrays.asList(new Lock(dbLockContext.getResourceName(), LockType.IX, 0L),
                new Lock(tableLockContext.getResourceName(), LockType.IS, 0L),
                new Lock(pageLockContext.getResourceName(), LockType.S, 0L)),
                lockManager.getLocks(transactions[0]));
    }

    @Test
    @Category(PublicTests.class)
    public void testSimpleReleasePass() {
        dbLockContext.acquire(transactions[0], LockType.IS);
        tableLockContext.acquire(transactions[0], LockType.S);
        tableLockContext.release(transactions[0]);
        // After the sequence above, T0 should only have its lock on the database
        Assert.assertEquals(Collections.singletonList(new Lock(dbLockContext.getResourceName(), LockType.IS,
                        0L)),
                lockManager.getLocks(transactions[0]));
    }

    @Test
    @Category(PublicTests.class)
    public void testNoop() {
        dbLockContext.acquire(transactions[0], LockType.IS);
        try {
            tableLockContext.acquire(transactions[0], LockType.NL);
            fail("Attempting to acquire an NL lock, should instead use release");
        } catch (InvalidLockException e) {
            // do nothing
        }
    }

    @Test
    @Category(PublicTests.class)
    public void testSimpleReleaseFail() {
        dbLockContext.acquire(transactions[0], LockType.IS);
        tableLockContext.acquire(transactions[0], LockType.S);
        try {
            dbLockContext.release(transactions[0]);
            fail("Attemptng to release an IS lock when a child resource " +
                    "still holds an S locks should throw an InvalidLockException");
        } catch (InvalidLockException e) {
            // do nothing
        }
    }

    @Test
    @Category(PublicTests.class)
    public void testSharedPage() {
        DeterministicRunner runner = new DeterministicRunner(2);

        TransactionContext t1 = transactions[1];
        TransactionContext t2 = transactions[2];

        LockContext r0 = tableLockContext;
        LockContext r1 = pageLockContext;

        runner.run(0, () -> dbLockContext.acquire(t1, LockType.IS));
        runner.run(1, () -> dbLockContext.acquire(t2, LockType.IS));
        runner.run(0, () -> r0.acquire(t1, LockType.IS));
        runner.run(1, () -> r0.acquire(t2, LockType.IS));
        runner.run(0, () -> r1.acquire(t1, LockType.S));
        runner.run(1, () -> r1.acquire(t2, LockType.S));

        assertTrue(TestLockManager.holds(lockManager, t1, r0.getResourceName(), LockType.IS));
        assertTrue(TestLockManager.holds(lockManager, t2, r0.getResourceName(), LockType.IS));
        assertTrue(TestLockManager.holds(lockManager, t1, r1.getResourceName(), LockType.S));
        assertTrue(TestLockManager.holds(lockManager, t2, r1.getResourceName(), LockType.S));

        runner.joinAll();
    }

    @Test
    @Category(PublicTests.class)
    public void testSandIS() {
        DeterministicRunner runner = new DeterministicRunner(2);

        TransactionContext t1 = transactions[1];
        TransactionContext t2 = transactions[2];

        LockContext r0 = dbLockContext;
        LockContext r1 = tableLockContext;

        runner.run(0, () -> r0.acquire(t1, LockType.S));
        runner.run(1, () -> r0.acquire(t2, LockType.IS));
        runner.run(1, () -> r1.acquire(t2, LockType.S));
        runner.run(0, () -> r0.release(t1));

        assertTrue(TestLockManager.holds(lockManager, t2, r0.getResourceName(), LockType.IS));
        assertTrue(TestLockManager.holds(lockManager, t2, r1.getResourceName(), LockType.S));
        assertFalse(TestLockManager.holds(lockManager, t1, r0.getResourceName(), LockType.S));

        runner.joinAll();
    }

    @Test
    @Category(PublicTests.class)
    public void testSharedIntentConflict() {
        DeterministicRunner runner = new DeterministicRunner(2);

        TransactionContext t1 = transactions[1];
        TransactionContext t2 = transactions[2];

        LockContext r0 = dbLockContext;
        LockContext r1 = tableLockContext;

        runner.run(0, () -> r0.acquire(t1, LockType.IS));
        runner.run(1, () -> r0.acquire(t2, LockType.IX));
        runner.run(0, () -> r1.acquire(t1, LockType.S));
        runner.run(1, () -> r1.acquire(t2, LockType.X));

        assertTrue(TestLockManager.holds(lockManager, t1, r0.getResourceName(), LockType.IS));
        assertTrue(TestLockManager.holds(lockManager, t2, r0.getResourceName(), LockType.IX));
        assertTrue(TestLockManager.holds(lockManager, t1, r1.getResourceName(), LockType.S));
        assertFalse(TestLockManager.holds(lockManager, t2, r1.getResourceName(), LockType.X));

        runner.join(0);
    }

    @Test
    @Category(PublicTests.class)
    public void testSharedIntentConflictRelease() {
        DeterministicRunner runner = new DeterministicRunner(2);

        TransactionContext t1 = transactions[1];
        TransactionContext t2 = transactions[2];

        LockContext r0 = dbLockContext;
        LockContext r1 = tableLockContext;

        runner.run(0, () -> r0.acquire(t1, LockType.IS));
        runner.run(1, () -> r0.acquire(t2, LockType.IX));
        runner.run(0, () -> r1.acquire(t1, LockType.S));
        runner.run(1, () -> r1.acquire(t2, LockType.X));
        runner.run(0, () -> r1.release(t1));

        assertTrue(TestLockManager.holds(lockManager, t1, r0.getResourceName(), LockType.IS));
        assertTrue(TestLockManager.holds(lockManager, t2, r0.getResourceName(), LockType.IX));
        assertFalse(TestLockManager.holds(lockManager, t1, r1.getResourceName(), LockType.S));
        assertTrue(TestLockManager.holds(lockManager, t2, r1.getResourceName(), LockType.X));

        runner.joinAll();
    }

    @Test
    @Category(PublicTests.class)
    public void testSimplePromote() {
        TransactionContext t1 = transactions[1];
        dbLockContext.acquire(t1, LockType.S);
        dbLockContext.promote(t1, LockType.X);
        assertTrue(TestLockManager.holds(lockManager, t1, dbLockContext.getResourceName(), LockType.X));
    }

    @Test
    @Category(PublicTests.class)
    public void testEscalateFail() {
        TransactionContext t1 = transactions[1];

        LockContext r0 = dbLockContext;

        try {
            r0.escalate(t1);
            fail();
        } catch (NoLockHeldException e) {
            // do nothing
        }
    }

    @Test
    @Category(PublicTests.class)
    public void testEscalateISS() {
        TransactionContext t1 = transactions[1];

        LockContext r0 = dbLockContext;

        r0.acquire(t1, LockType.IS);
        r0.escalate(t1);
        assertTrue(TestLockManager.holds(lockManager, t1, r0.getResourceName(), LockType.S));
    }

    @Test
    @Category(PublicTests.class)
    public void testEscalateIXX() {
        TransactionContext t1 = transactions[1];

        LockContext r0 = dbLockContext;

        r0.acquire(t1, LockType.IX);
        r0.escalate(t1);
        assertTrue(TestLockManager.holds(lockManager, t1, r0.getResourceName(), LockType.X));
    }

    @Test
    @Category(PublicTests.class)
    public void testEscalateIdempotent() {
        TransactionContext t1 = transactions[1];

        LockContext r0 = dbLockContext;

        r0.acquire(t1, LockType.IS);
        r0.escalate(t1);
        lockManager.startLog();
        r0.escalate(t1);
        r0.escalate(t1);
        r0.escalate(t1);
        assertEquals(Collections.emptyList(), lockManager.log);
    }

    @Test
    @Category(PublicTests.class)
    public void testEscalateS() {
        TransactionContext t1 = transactions[1];

        LockContext r0 = dbLockContext;
        LockContext r1 = tableLockContext;

        r0.acquire(t1, LockType.IS);
        r1.acquire(t1, LockType.S);
        r0.escalate(t1);

        assertTrue(TestLockManager.holds(lockManager, t1, r0.getResourceName(), LockType.S));
        assertFalse(TestLockManager.holds(lockManager, t1, r1.getResourceName(), LockType.S));
    }

    @Test
    @Category(PublicTests.class)
    public void testEscalateMultipleS() {
        TransactionContext t1 = transactions[1];

        LockContext r0 = dbLockContext;
        LockContext r1 = tableLockContext;
        LockContext r2 = dbLockContext.childContext("table2");
        LockContext r3 = dbLockContext.childContext("table3");

        r0.acquire(t1, LockType.IS);
        r1.acquire(t1, LockType.S);
        r2.acquire(t1, LockType.IS);
        r3.acquire(t1, LockType.S);

        assertEquals(3, r0.getNumChildren(t1));
        r0.escalate(t1);
        assertEquals(0, r0.getNumChildren(t1));

        assertTrue(TestLockManager.holds(lockManager, t1, r0.getResourceName(), LockType.S));
        assertFalse(TestLockManager.holds(lockManager, t1, r1.getResourceName(), LockType.S));
        assertFalse(TestLockManager.holds(lockManager, t1, r2.getResourceName(), LockType.IS));
        assertFalse(TestLockManager.holds(lockManager, t1, r3.getResourceName(), LockType.S));
    }

    @Test
    @Category(PublicTests.class)
    public void testGetLockType() {
        DeterministicRunner runner = new DeterministicRunner(4);

        TransactionContext t1 = transactions[1];
        TransactionContext t2 = transactions[2];
        TransactionContext t3 = transactions[3];
        TransactionContext t4 = transactions[4];

        runner.run(0, () -> dbLockContext.acquire(t1, LockType.S));
        runner.run(1, () -> dbLockContext.acquire(t2, LockType.IS));
        runner.run(2, () -> dbLockContext.acquire(t3, LockType.IS));
        runner.run(3, () -> dbLockContext.acquire(t4, LockType.IS));

        runner.run(1, () -> tableLockContext.acquire(t2, LockType.S));
        runner.run(2, () -> tableLockContext.acquire(t3, LockType.IS));

        runner.run(2, () -> pageLockContext.acquire(t3, LockType.S));

        assertEquals(LockType.S, pageLockContext.getEffectiveLockType(t1));
        assertEquals(LockType.S, pageLockContext.getEffectiveLockType(t2));
        assertEquals(LockType.S, pageLockContext.getEffectiveLockType(t3));
        assertEquals(LockType.NL, pageLockContext.getEffectiveLockType(t4));
        assertEquals(LockType.NL, pageLockContext.getExplicitLockType(t1));
        assertEquals(LockType.NL, pageLockContext.getExplicitLockType(t2));
        assertEquals(LockType.S, pageLockContext.getExplicitLockType(t3));
        assertEquals(LockType.NL, pageLockContext.getExplicitLockType(t4));

        runner.joinAll();
    }

    @Test
    @Category(PublicTests.class)
    public void testReadonly() {
        dbLockContext.disableChildLocks();
        LockContext tableContext = dbLockContext.childContext("table2");
        TransactionContext t1 = transactions[1];
        dbLockContext.acquire(t1, LockType.IX);
        try {
            tableContext.acquire(t1, LockType.IX);
            fail();
        } catch (UnsupportedOperationException e) {
            // do nothing
        }
        try {
            tableContext.release(t1);
            fail();
        } catch (UnsupportedOperationException e) {
            // do nothing
        }
        try {
            tableContext.promote(t1, LockType.IX);
            fail();
        } catch (UnsupportedOperationException e) {
            // do nothing
        }
        try {
            tableContext.escalate(t1);
            fail();
        } catch (UnsupportedOperationException e) {
            // do nothing
        }
    }

    @Test
    @Category(PublicTests.class)
    public void testAcquireNoParentLock() {
        // Child lock requires parent lock — no parent means InvalidLockException
        TransactionContext t1 = transactions[1];
        try {
            tableLockContext.acquire(t1, LockType.S);
            fail("Acquiring child lock without parent lock should throw InvalidLockException");
        } catch (InvalidLockException e) {
            // expected
        }
    }

    @Test
    @Category(PublicTests.class)
    public void testReleaseNoLockHeld() {
        TransactionContext t1 = transactions[1];
        dbLockContext.acquire(t1, LockType.IS);
        try {
            tableLockContext.release(t1);
            fail("Releasing unheld lock should throw NoLockHeldException");
        } catch (NoLockHeldException e) {
            // expected
        }
    }

    @Test
    @Category(PublicTests.class)
    public void testPromoteToSIX() {
        // IX(db) + IX(table) + S(page) → promote table IX→SIX → S/IS descendants released atomically
        TransactionContext t1 = transactions[1];
        dbLockContext.acquire(t1, LockType.IX);
        tableLockContext.acquire(t1, LockType.IX);
        pageLockContext.acquire(t1, LockType.S);

        assertEquals(1, tableLockContext.getNumChildren(t1));

        tableLockContext.promote(t1, LockType.SIX);

        assertTrue(TestLockManager.holds(lockManager, t1, tableLockContext.getResourceName(), LockType.SIX));
        assertFalse(TestLockManager.holds(lockManager, t1, pageLockContext.getResourceName(), LockType.S));
        assertEquals(0, tableLockContext.getNumChildren(t1));
    }

    @Test
    @Category(PublicTests.class)
    public void testPromoteStoSIX() {
        // IX(db) + S(table) + IS(page) → promote table S→SIX → IS descendants released
        TransactionContext t1 = transactions[1];
        dbLockContext.acquire(t1, LockType.IX);
        tableLockContext.acquire(t1, LockType.S);
        pageLockContext.acquire(t1, LockType.IS);

        tableLockContext.promote(t1, LockType.SIX);

        assertTrue(TestLockManager.holds(lockManager, t1, tableLockContext.getResourceName(), LockType.SIX));
        assertFalse(TestLockManager.holds(lockManager, t1, pageLockContext.getResourceName(), LockType.IS));
        assertEquals(0, tableLockContext.getNumChildren(t1));
    }

    @Test
    @Category(PublicTests.class)
    public void testPromoteIStoSIX() {
        // IX(db) + IS(table) → promote table IS→SIX (no S/IS descendants to release)
        TransactionContext t1 = transactions[1];
        dbLockContext.acquire(t1, LockType.IX);
        tableLockContext.acquire(t1, LockType.IS);

        tableLockContext.promote(t1, LockType.SIX);

        assertTrue(TestLockManager.holds(lockManager, t1, tableLockContext.getResourceName(), LockType.SIX));
    }

    @Test
    @Category(PublicTests.class)
    public void testPromoteChildContext() {
        // IX(db) + IS(table) → promote table IS→IX; parent numChildLocks unchanged
        TransactionContext t1 = transactions[1];
        dbLockContext.acquire(t1, LockType.IX);
        tableLockContext.acquire(t1, LockType.IS);

        assertEquals(1, dbLockContext.getNumChildren(t1));

        tableLockContext.promote(t1, LockType.IX);

        assertTrue(TestLockManager.holds(lockManager, t1, tableLockContext.getResourceName(), LockType.IX));
        assertTrue(TestLockManager.holds(lockManager, t1, dbLockContext.getResourceName(), LockType.IX));
        assertEquals(1, dbLockContext.getNumChildren(t1));
    }

    @Test
    @Category(PublicTests.class)
    public void testAcquireRedundantUnderSIX() {
        // SIX on ancestor already grants S to all descendants — acquiring S/IS below is redundant/invalid
        TransactionContext t1 = transactions[1];
        dbLockContext.acquire(t1, LockType.SIX);
        try {
            tableLockContext.acquire(t1, LockType.IS);
            fail("Acquiring IS under SIX ancestor should throw InvalidLockException");
        } catch (InvalidLockException e) {
            // expected
        }
        try {
            tableLockContext.acquire(t1, LockType.S);
            fail("Acquiring S under SIX ancestor should throw InvalidLockException");
        } catch (InvalidLockException e) {
            // expected
        }
    }

    @Test
    @Category(PublicTests.class)
    public void testPromoteToSIXWithSIXAncestor() {
        // SIX already on ancestor — redundant SIX on descendant is invalid
        TransactionContext t1 = transactions[1];
        dbLockContext.acquire(t1, LockType.SIX);
        tableLockContext.acquire(t1, LockType.IX);

        try {
            tableLockContext.promote(t1, LockType.SIX);
            fail("Promoting to SIX with SIX ancestor should throw InvalidLockException");
        } catch (InvalidLockException e) {
            // expected
        }
    }

    @Test
    @Category(PublicTests.class)
    public void testPromoteFailInvalidParentConstraint() {
        // IS(db) + IS(table) → promote table IS→X: canBeParentLock(IS, X)=false
        TransactionContext t1 = transactions[1];
        dbLockContext.acquire(t1, LockType.IS);
        tableLockContext.acquire(t1, LockType.IS);

        try {
            tableLockContext.promote(t1, LockType.X);
            fail("Promoting to X with IS parent should throw InvalidLockException");
        } catch (InvalidLockException e) {
            // expected
        }

        assertTrue(TestLockManager.holds(lockManager, t1, tableLockContext.getResourceName(), LockType.IS));
    }

    @Test
    @Category(PublicTests.class)
    public void testEscalateX() {
        // IX(db) + IX(table) + X(page) → escalate table → X(table), page released
        TransactionContext t1 = transactions[1];
        dbLockContext.acquire(t1, LockType.IX);
        tableLockContext.acquire(t1, LockType.IX);
        pageLockContext.acquire(t1, LockType.X);

        assertEquals(1, tableLockContext.getNumChildren(t1));

        tableLockContext.escalate(t1);

        assertTrue(TestLockManager.holds(lockManager, t1, tableLockContext.getResourceName(), LockType.X));
        assertFalse(TestLockManager.holds(lockManager, t1, pageLockContext.getResourceName(), LockType.X));
        assertEquals(0, tableLockContext.getNumChildren(t1));
        // Parent db lock unchanged
        assertTrue(TestLockManager.holds(lockManager, t1, dbLockContext.getResourceName(), LockType.IX));
    }

    @Test
    @Category(PublicTests.class)
    public void testEscalateMixedDescendants() {
        // IX(db) + IX(table) + S(page1) + X(page2) → escalate table → X (X present)
        TransactionContext t1 = transactions[1];
        LockContext page2 = tableLockContext.childContext("page2");

        dbLockContext.acquire(t1, LockType.IX);
        tableLockContext.acquire(t1, LockType.IX);
        pageLockContext.acquire(t1, LockType.S);
        page2.acquire(t1, LockType.X);

        assertEquals(2, tableLockContext.getNumChildren(t1));

        tableLockContext.escalate(t1);

        assertTrue(TestLockManager.holds(lockManager, t1, tableLockContext.getResourceName(), LockType.X));
        assertFalse(TestLockManager.holds(lockManager, t1, pageLockContext.getResourceName(), LockType.S));
        assertFalse(TestLockManager.holds(lockManager, t1, page2.getResourceName(), LockType.X));
        assertEquals(0, tableLockContext.getNumChildren(t1));
    }

    @Test
    @Category(PublicTests.class)
    public void testEscalateDbLevel() {
        // IX(db) + X(table) → escalate db → X(db), table released, numChildren=0
        TransactionContext t1 = transactions[1];
        dbLockContext.acquire(t1, LockType.IX);
        tableLockContext.acquire(t1, LockType.X);

        assertEquals(1, dbLockContext.getNumChildren(t1));

        dbLockContext.escalate(t1);

        assertTrue(TestLockManager.holds(lockManager, t1, dbLockContext.getResourceName(), LockType.X));
        assertFalse(TestLockManager.holds(lockManager, t1, tableLockContext.getResourceName(), LockType.X));
        assertEquals(0, dbLockContext.getNumChildren(t1));
    }

    @Test
    @Category(PublicTests.class)
    public void testEffectiveLockTypeSIXAncestor() {
        // SIX(db) → no explicit lock at table/page, but effective = S (SIX implies S on descendants)
        TransactionContext t1 = transactions[1];
        dbLockContext.acquire(t1, LockType.SIX);

        assertEquals(LockType.NL, tableLockContext.getExplicitLockType(t1));
        assertEquals(LockType.NL, pageLockContext.getExplicitLockType(t1));
        assertEquals(LockType.S, tableLockContext.getEffectiveLockType(t1));
        assertEquals(LockType.S, pageLockContext.getEffectiveLockType(t1));
    }

    @Test
    @Category(PublicTests.class)
    public void testEffectiveLockTypeXAncestor() {
        // X(db) → effective = X at table and page (no explicit lock needed below)
        TransactionContext t1 = transactions[1];
        dbLockContext.acquire(t1, LockType.X);

        assertEquals(LockType.NL, tableLockContext.getExplicitLockType(t1));
        assertEquals(LockType.X, tableLockContext.getEffectiveLockType(t1));
        assertEquals(LockType.X, pageLockContext.getEffectiveLockType(t1));
    }

    @Test
    @Category(PublicTests.class)
    public void testEffectiveLockTypeNoAncestor() {
        // IS/IX on ancestors don't count as effective locks on descendants
        TransactionContext t1 = transactions[1];
        dbLockContext.acquire(t1, LockType.IX);
        tableLockContext.acquire(t1, LockType.IS);

        assertEquals(LockType.NL, pageLockContext.getEffectiveLockType(t1));
        assertEquals(LockType.NL, pageLockContext.getExplicitLockType(t1));
    }

    @Test
    @Category(PublicTests.class)
    public void testNumChildLocksTracking() {
        // numChildLocks stays accurate through acquire/release
        TransactionContext t1 = transactions[1];
        LockContext table2 = dbLockContext.childContext("table2");

        assertEquals(0, dbLockContext.getNumChildren(t1));

        dbLockContext.acquire(t1, LockType.IX);
        tableLockContext.acquire(t1, LockType.X);
        assertEquals(1, dbLockContext.getNumChildren(t1));

        table2.acquire(t1, LockType.X);
        assertEquals(2, dbLockContext.getNumChildren(t1));

        tableLockContext.release(t1);
        assertEquals(1, dbLockContext.getNumChildren(t1));

        table2.release(t1);
        assertEquals(0, dbLockContext.getNumChildren(t1));
    }

    @Test
    @Category(PublicTests.class)
    public void testConcurrentPageConflict() {
        // T1 and T2 both hold IX on db and table; T1 holds X on page; T2 blocks acquiring X on page
        DeterministicRunner runner = new DeterministicRunner(2);
        TransactionContext t1 = transactions[1];
        TransactionContext t2 = transactions[2];

        runner.run(0, () -> {
            dbLockContext.acquire(t1, LockType.IX);
            tableLockContext.acquire(t1, LockType.IX);
            pageLockContext.acquire(t1, LockType.X);
        });
        runner.run(1, () -> {
            dbLockContext.acquire(t2, LockType.IX);
            tableLockContext.acquire(t2, LockType.IX);
        });
        runner.run(1, () -> pageLockContext.acquire(t2, LockType.X));

        // T2 blocked — X(t1) conflicts with X(t2) on page
        assertFalse(TestLockManager.holds(lockManager, t2, pageLockContext.getResourceName(), LockType.X));
        assertTrue(t2.getBlocked());

        runner.run(0, () -> pageLockContext.release(t1));

        assertTrue(TestLockManager.holds(lockManager, t2, pageLockContext.getResourceName(), LockType.X));
        assertFalse(t2.getBlocked());

        runner.join(1);
    }

    @Test
    @Category(PublicTests.class)
    public void testGetNumChildren() {
        LockContext tableContext = dbLockContext.childContext("table2");
        TransactionContext t1 = transactions[1];
        dbLockContext.acquire(t1, LockType.IX);
        tableContext.acquire(t1, LockType.IS);
        assertEquals(1, dbLockContext.getNumChildren(t1));
        tableContext.promote(t1, LockType.IX);
        assertEquals(1, dbLockContext.getNumChildren(t1));
        tableContext.release(t1);
        assertEquals(0, dbLockContext.getNumChildren(t1));
        tableContext.acquire(t1, LockType.IS);
        dbLockContext.escalate(t1);
        assertEquals(0, dbLockContext.getNumChildren(t1));
    }

}
