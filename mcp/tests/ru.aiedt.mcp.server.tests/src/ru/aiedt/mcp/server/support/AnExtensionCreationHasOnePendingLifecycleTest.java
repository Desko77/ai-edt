/** AI-EDT - Licensed under AGPL-3.0-or-later */
package ru.aiedt.mcp.server.support;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class AnExtensionCreationHasOnePendingLifecycleTest {
    static class Fake implements ExtensionCreationLifecycle.Port {
        final CountDownLatch entered=new CountDownLatch(1), leave=new CountDownLatch(1);
        final AtomicInteger calls=new AtomicInteger();
        volatile boolean exists, preexisting; volatile RuntimeException failure;
        public String preflight(ExtensionCreationLifecycle.Scope s){return preexisting?"Project already exists":null;}
        public Map<String,Object> create(ExtensionCreationLifecycle.Scope s, ToolCallScope.Cancellation c) throws Exception {
            calls.incrementAndGet(); entered.countDown();
            if(!leave.await(3,TimeUnit.SECONDS))throw new IllegalStateException("fixture deadline");
            exists=true;if(failure!=null)throw failure; return Map.of();
        }
        public Map<String,Object> observe(ExtensionCreationLifecycle.Scope s){return Map.of("projectExists",exists,"projectState",exists?"available":"not_available","configurationAvailable",exists);}
    }
    Map<String,String> scope(String name){return new HashMap<>(Map.of("projectName",name,"baseProjectName","SyntheticBase","namePrefix","Test_"));}
    Map<String,String> poll(Map<String,Object> r){return new HashMap<>(Map.of("runKey",r.get("runKey").toString()));}
    ExtensionCreationLifecycle coordinator(Fake f){return new ExtensionCreationLifecycle(f, domain(),2);}
    PendingWorkRegistry domain(){try{return (PendingWorkRegistry)PendingWorkRegistry.class.getField("EXTENSION_CREATE").get(null);}catch(Exception e){throw new AssertionError(e);}}
    @Test public void timeoutDuplicateMismatchAndCollection()throws Exception{
        Fake f=new Fake();ExtensionCreationLifecycle c=coordinator(f);Map<String,Object> r=null;
        try{r=c.execute(scope("SyntheticOne"),1);assertEquals("Pending",r.get("status"));assertTrue(f.entered.await(1,TimeUnit.SECONDS));
            assertEquals(r.get("runKey"),c.execute(scope("SyntheticOne"),1).get("runKey"));assertEquals(1,f.calls.get());
            Map<String,String> p=poll(r);p.put("baseProjectName","Other");assertEquals(false,c.execute(p,1).get("success"));
            assertEquals(false,c.execute(Map.of("runKey","unknown"),1).get("success"));
            assertEquals(false,r.get("dialogObservationAvailable"));
            f.leave.countDown();assertEquals(true,c.execute(poll(r),1000).get("success"));assertEquals(0,c.trackedCount());
        }finally{f.leave.countDown();if(r!=null)c.execute(poll(r),1000);}
    }
    @Test public void postStartCancelRetainsWorkerAndReportsResidual()throws Exception{
        Fake f=new Fake();ExtensionCreationLifecycle c=coordinator(f);Map<String,Object> r=c.execute(scope("SyntheticCancel"),1);
        try{assertTrue(f.entered.await(1,TimeUnit.SECONDS));Map<String,String> p=poll(r);p.put("cancel","true");
            assertEquals("Pending",c.execute(p,1).get("status"));assertEquals(1,c.trackedCount());f.leave.countDown();
            Map<String,Object> done=c.execute(poll(r),1000);assertEquals(false,done.get("success"));assertEquals("residual",done.get("phase"));assertTrue(f.exists);assertEquals(0,c.trackedCount());
        }finally{f.leave.countDown();c.execute(poll(r),1000);}
    }
    @Test public void exceptionAndPreexistingNeverSucceed()throws Exception{
        Fake f=new Fake();f.failure=new IllegalStateException("synthetic failure");ExtensionCreationLifecycle c=coordinator(f);
        Map<String,Object> r=c.execute(scope("SyntheticFailure"),1);f.leave.countDown();Map<String,Object> done=c.execute(poll(r),1000);
        assertEquals(false,done.get("success"));assertEquals("SyntheticFailure",done.get("residualProjectName"));
        f.preexisting=true;assertEquals(false,c.execute(scope("Existing"),1).get("success"));assertEquals(1,f.calls.get());
    }
    @Test public void busyAdmissionStillAllowsDuplicate()throws Exception{
        Fake f=new Fake();ExtensionCreationLifecycle c=new ExtensionCreationLifecycle(f,domain(),1);Map<String,Object> r=c.execute(scope("BusyOne"),1);
        try{assertEquals(false,c.execute(scope("BusyTwo"),1).get("success"));assertEquals(r.get("runKey"),c.execute(scope("BusyOne"),1).get("runKey"));}
        finally{f.leave.countDown();c.execute(poll(r),1000);}
    }
    @Test public void localExpiryBeforeRegistryExpiryCannotCoalesceCompletedEntry()throws Exception{
        Fake f=new Fake();ExtensionCreationLifecycle c=coordinator(f);Map<String,Object> first=c.execute(scope("SkewedExpiry"),1);
        f.leave.countDown();PendingWorkRegistry.PendingEntry old=domain().get(first.get("runKey").toString());old.await(1000);
        java.lang.reflect.Field rf=ExtensionCreationLifecycle.class.getDeclaredField("runs");rf.setAccessible(true);
        Object run=((Map<?,?>)rf.get(c)).values().iterator().next();java.lang.reflect.Field ff=run.getClass().getDeclaredField("finishedAt");ff.setAccessible(true);ff.setLong(run,System.currentTimeMillis()-600000);
        old.completedAt=System.currentTimeMillis();
        Map<String,Object> next=c.execute(scope("SkewedExpiry"),1000);
        assertNotEquals("Pending",next.get("status"));
        assertEquals(true,next.get("success"));assertEquals(2,f.calls.get());assertEquals(0,c.trackedCount());
        domain().remove(old.runKey,old);
    }
    @Test public void freshSubmissionDoesNotReplayAnUncollectedSuccess()throws Exception{
        Fake f=new Fake();ExtensionCreationLifecycle c=coordinator(f);Map<String,Object> first=c.execute(scope("NoReplay"),1);
        f.leave.countDown();domain().get(first.get("runKey").toString()).await(1000);f.preexisting=true;
        assertEquals(false,c.execute(scope("NoReplay"),1000).get("success"));
        assertEquals(true,c.execute(poll(first),1000).get("success"));assertEquals(1,f.calls.get());
    }
    @Test public void expiredCompletedCacheDoesNotBlockNewAdmission()throws Exception{
        Fake f=new Fake();ExtensionCreationLifecycle c=new ExtensionCreationLifecycle(f,domain(),1);
        Map<String,Object> first=c.execute(scope("ExpiredOne"),1);f.leave.countDown();
        PendingWorkRegistry.PendingEntry entry=domain().get(first.get("runKey").toString());
        assertNotNull(entry);entry.await(1000);entry.completedAt=System.currentTimeMillis()-600000;
        java.lang.reflect.Field rf=ExtensionCreationLifecycle.class.getDeclaredField("runs");rf.setAccessible(true);
        Object run=((Map<?,?>)rf.get(c)).values().iterator().next();java.lang.reflect.Field ff=run.getClass().getDeclaredField("finishedAt");ff.setAccessible(true);ff.setLong(run,System.currentTimeMillis()-600000);
        domain().pruneExpired();
        Map<String,Object> next=c.execute(scope("ExpiredTwo"),1000);
        assertEquals(true,next.get("success"));assertEquals(2,f.calls.get());
    }
    @Test public void domainCancellationReachesNativeMonitor()throws Exception{
        Fake f=new Fake(){@Override public Map<String,Object> create(ExtensionCreationLifecycle.Scope s,ToolCallScope.Cancellation flag)throws Exception{
            calls.incrementAndGet();entered.countDown();assertTrue(leave.await(10,TimeUnit.SECONDS));assertTrue(flag.isCancelled());exists=true;return Map.of();}};
        ExtensionCreationLifecycle c=coordinator(f);Map<String,Object> r=c.execute(scope("ExternalCancel"),1);
        try{assertTrue(f.entered.await(1,TimeUnit.SECONDS));domain().cancelAndStop(r.get("runKey").toString());f.leave.countDown();
            Map<String,Object> done=c.execute(poll(r),1000);assertEquals(false,done.get("success"));assertEquals("residual",done.get("phase"));}
        finally{f.leave.countDown();c.execute(poll(r),1000);}
    }
    @Test public void externalCancellationBeforeWorkerClaimCompletesWithoutPortCalls()throws Exception{
        Fake f=new Fake();ExtensionCreationLifecycle c=coordinator(f);
        CountDownLatch gated=new CountDownLatch(1), release=new CountDownLatch(1);
        java.util.function.Consumer<PendingWorkRegistry.PendingEntry> old=PendingWorkRegistry.beforeWorkClaim;
        PendingWorkRegistry.beforeWorkClaim=e->{gated.countDown();try{assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException x){Thread.currentThread().interrupt();throw new AssertionError(x);}};
        Map<String,Object> first=null;
        try{
            first=c.execute(scope("CancelledQueued"),1);assertTrue(gated.await(1,TimeUnit.SECONDS));
            domain().cancelAndStop(first.get("runKey").toString());release.countDown();
            Map<String,Object> done=c.execute(poll(first),1000);
            assertNotEquals("Pending",done.get("status"));assertEquals(false,done.get("success"));
            assertEquals("cancelled",done.get("phase"));assertEquals(false,done.get("mutationStarted"));
            assertEquals(0,f.calls.get());assertEquals(0,c.trackedCount());
        }finally{release.countDown();PendingWorkRegistry.beforeWorkClaim=old;f.leave.countDown();if(first!=null)c.execute(poll(first),1000);}
    }
    @Test public void cancellationBeforeLaunchDoesNotCallPort()throws Exception{
        Fake f=new Fake();ExtensionCreationLifecycle c=coordinator(f);
        java.util.function.Consumer<PendingWorkRegistry.PendingEntry> old=PendingWorkRegistry.beforeWorkClaim;
        PendingWorkRegistry.beforeWorkClaim=e->{e.cancellation=new ToolCallScope.Cancellation();e.cancellation.cancel("test prestart");};
        try{Map<String,Object> r=c.execute(scope("BeforeLaunch"),1000);assertEquals(false,r.get("success"));assertEquals(false,r.get("mutationStarted"));assertEquals(0,f.calls.get());}
        finally{PendingWorkRegistry.beforeWorkClaim=old;f.leave.countDown();}
    }
}
