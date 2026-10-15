/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.dt.bsl.model.BslFactory;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.Procedure;
import com._1c.g5.v8.dt.bsl.model.SimpleStatement;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.StringLiteral;

import ru.aiedt.mcp.server.support.BmReferencesHelper;
import ru.aiedt.mcp.server.support.BslCallGraphHelper;
import ru.aiedt.mcp.server.support.ToolCallScope;
import ru.aiedt.mcp.server.support.WatchForCancel;

/**
 * A method a written-by-name call names is an edge of the module level and is not reported dead.
 * <p>
 * The scan itself needs a live project, so these tests drive the two places the decision is made:
 * the collection of name-written calls while modules are walked, and the drop of the candidates
 * they keep alive. The graph side is proven through a named call source, the way the module-level
 * walk is proven elsewhere.
 * </p>
 */
public class ANameCalledMethodIsNotReportedDeadTest
{
    private static final String ROOT = "CommonModule.Root.Module"; //$NON-NLS-1$
    private static final String CALLEE = "CommonModule.Callee.Module"; //$NON-NLS-1$

    private static final Map<String, String> MODULES = Map.of("подготовка", "Подготовка"); //$NON-NLS-1$ //$NON-NLS-2$

    /** The calls a module's text writes by name become edges out of that module. */
    @Test
    public void aNameCallIsAnEdgeOutOfItsModule()
    {
        BslCallGraphHelper.NameCall call =
            new BslCallGraphHelper.NameCall(CALLEE, "РассчитатьВсе", 12); //$NON-NLS-1$

        BmReferencesHelper.BfsResult walk = walk(rootModule(), calling(Map.of(), List.of(call)));

        assertEquals(1, walk.edges.size());
        assertEquals(ROOT, walk.edges.get(0).fromFqn);
        assertEquals(CALLEE, walk.edges.get(0).toFqn);
        assertEquals("calls", walk.edges.get(0).featureName); //$NON-NLS-1$
        assertTrue("the callee is a node the walk reaches", walk.nodes.containsKey(CALLEE)); //$NON-NLS-1$
    }

    /** A call naming a method of the walked module itself is an edge of the module onto itself. */
    @Test
    public void aNameCallIntoTheWalkedModuleItselfIsASelfEdge()
    {
        BslCallGraphHelper.NameCall self =
            new BslCallGraphHelper.NameCall(null, "ПослеЗагрузки", 3); //$NON-NLS-1$

        BmReferencesHelper.BfsResult walk = walk(rootModule(), calling(Map.of(), List.of(self)));

        assertEquals(1, walk.edges.size());
        assertEquals(ROOT, walk.edges.get(0).fromFqn);
        assertEquals(ROOT, walk.edges.get(0).toFqn);
        assertEquals("calls", walk.edges.get(0).featureName); //$NON-NLS-1$
        assertEquals(DependencyGraphTool.RESOLUTION_NAME, walk.edges.get(0).resolution);
        assertEquals(List.of(ROOT), new ArrayList<>(walk.nodes.keySet()));
    }

    /** A call a module's text writes on its own module keeps that method out of the dead list. */
    @Test
    public void aSelfModuleNameCallProtectsItsMethod()
    {
        Module module = moduleAt("CommonModules/Подготовка/Module.bsl", "ПроверитьОжидание"); //$NON-NLS-1$ //$NON-NLS-2$
        idleHandlerOn(module, "ПроверитьОжидание"); //$NON-NLS-1$
        Set<String> nameCalled = new HashSet<>();

        FindDeadCodeTool.collectNameCalled(module, MODULES, nameCalled);

        assertTrue(nameCalled.contains(FindDeadCodeTool.nameCallKey(
            "CommonModule.Подготовка.Module", "ПроверитьОжидание"))); //$NON-NLS-1$ //$NON-NLS-2$
        FindDeadCodeTool.Outcome outcome = FindDeadCodeTool.candidatesOf(
            List.of(new FindDeadCodeTool.WalkedModule("CommonModules/Подготовка/Module.bsl", module)), //$NON-NLS-1$
            nameCalled, 100, List.of(), method -> 0);
        assertTrue(outcome.candidates.isEmpty());
    }

    /** The walk collects the name-written calls of the modules it walks, keyed by method. */
    @Test
    public void theWalkedModuleGivesUpItsNameWrittenCalls()
    {
        Module caller = moduleCallingInBackground("Подготовка.РассчитатьВсе"); //$NON-NLS-1$
        Set<String> nameCalled = new HashSet<>();

        FindDeadCodeTool.collectNameCalled(caller, MODULES, nameCalled);

        assertEquals(Set.of(FindDeadCodeTool.nameCallKey("CommonModule.Подготовка.Module", //$NON-NLS-1$
            "РассчитатьВсе")), nameCalled); //$NON-NLS-1$
    }

    /** A candidate a name-written call names is dropped; one nothing names is kept. */
    @Test
    public void aCandidateANamedCallKeepsAliveIsDropped()
    {
        Module module = moduleAt("CommonModules/Подготовка/Module.bsl", //$NON-NLS-1$
            "РассчитатьВсе", "НичемНеВызван"); //$NON-NLS-1$ //$NON-NLS-2$
        Module unaddressed = moduleDeclaring("РассчитатьВсе"); //$NON-NLS-1$
        List<FindDeadCodeTool.WalkedModule> walked = List.of(
            new FindDeadCodeTool.WalkedModule("CommonModules/Подготовка/Module.bsl", module), //$NON-NLS-1$
            new FindDeadCodeTool.WalkedModule("CommonModules/БезАдреса/Module.bsl", unaddressed)); //$NON-NLS-1$

        Set<String> nameCalled = Set.of(
            FindDeadCodeTool.nameCallKey("CommonModule.Подготовка.Module", "РАССЧИТАТЬВСЕ")); //$NON-NLS-1$ //$NON-NLS-2$

        FindDeadCodeTool.Outcome outcome = FindDeadCodeTool.candidatesOf(walked, nameCalled, 100,
            List.of(), method -> 0);

        // A module whose address names no FQN cannot be collected under a key, so nothing
        // protects its export.
        assertEquals(2, outcome.candidates.size());
        assertFalse(outcome.candidates.stream().anyMatch(c -> "РассчитатьВсе".equals(c.methodName) //$NON-NLS-1$
            && "CommonModule.Подготовка.Module".equals(c.moduleFqn))); //$NON-NLS-1$
        assertTrue(outcome.candidates.stream().anyMatch(c -> "НичемНеВызван".equals(c.methodName))); //$NON-NLS-1$
        assertTrue(outcome.candidates.stream().anyMatch(c -> c.moduleFqn == null));
    }

    /**
     * A method a job names does not occupy the candidate limit: the limit still holds when the
     * scan reaches the next method nothing calls.
     */
    @Test
    public void aMethodAJobNamesDoesNotOccupyTheCandidateLimit()
    {
        com._1c.g5.v8.dt.metadata.mdclass.Configuration configuration =
            com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createConfiguration();
        com._1c.g5.v8.dt.metadata.mdclass.CommonModule module =
            com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCommonModule();
        module.setName("Подготовка"); //$NON-NLS-1$
        configuration.getCommonModules().add(module);
        com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob job =
            com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createScheduledJob();
        job.setMethodName("Подготовка.РассчитатьВсе"); //$NON-NLS-1$
        configuration.getScheduledJobs().add(job);

        Set<String> nameCalled = new HashSet<>();
        FindDeadCodeTool.collectJobAndSubscriptionCalled(configuration, nameCalled);
        Module подготовка = moduleAt("CommonModules/Подготовка/Module.bsl", //$NON-NLS-1$
            "РассчитатьВсе", "НичемНеВызван"); //$NON-NLS-1$ //$NON-NLS-2$

        FindDeadCodeTool.Outcome outcome = FindDeadCodeTool.candidatesOf(
            List.of(new FindDeadCodeTool.WalkedModule("CommonModules/Подготовка/Module.bsl", //$NON-NLS-1$
                подготовка)),
            nameCalled, 1, List.of(), method -> 0);

        assertEquals(1, outcome.candidates.size());
        assertEquals("НичемНеВызван", outcome.candidates.get(0).methodName); //$NON-NLS-1$
    }

    /**
     * The cap notice marks a stop the name-call filter did not cause: a limit the kept candidates
     * never reach is no stop, whatever the exports before the filter looked like.
     */
    @Test
    public void theCapNoticeMarksAStopTheFilterDidNotCause()
    {
        Module module = moduleAt("CommonModules/Подготовка/Module.bsl", //$NON-NLS-1$
            "РассчитатьВсе", "НичемНеВызван"); //$NON-NLS-1$ //$NON-NLS-2$
        Set<String> nameCalled = Set.of(
            FindDeadCodeTool.nameCallKey("CommonModule.Подготовка.Module", "РассчитатьВсе")); //$NON-NLS-1$ //$NON-NLS-2$

        FindDeadCodeTool.Outcome outcome = FindDeadCodeTool.candidatesOf(
            List.of(new FindDeadCodeTool.WalkedModule("CommonModules/Подготовка/Module.bsl", module)), //$NON-NLS-1$
            nameCalled, 2, List.of(), method -> 0);

        assertEquals(1, outcome.candidates.size());
        assertFalse(outcome.stoppedByLimit);
    }

    /**
     * A written-by-name call of a form module keeps its target alive although the scan takes no
     * candidates from form modules at the default settings.
     */
    @Test
    public void aNameCallOfAFormModuleProtectsItsTarget()
    {
        Module form = moduleWithStatement(notifyDescription("ПослеЗагрузки", "Подготовка")); //$NON-NLS-1$ //$NON-NLS-2$
        Module подготовка = moduleAt("CommonModules/Подготовка/Module.bsl", "ПослеЗагрузки"); //$NON-NLS-1$ //$NON-NLS-2$
        Set<String> nameCalled = new HashSet<>();

        FindDeadCodeTool.FirstPass first = FindDeadCodeTool.walkSources(
            List.of(fileAt("src/CommonForms/ФормаЗагрузки/Module.bsl"), //$NON-NLS-1$
                fileAt("src/CommonModules/Подготовка/Module.bsl")), //$NON-NLS-1$
            path -> "CommonForms/ФормаЗагрузки/Module.bsl".equals(path) ? form : подготовка, //$NON-NLS-1$
            false, null, null, MODULES, nameCalled, WatchForCancel.begin());

        FindDeadCodeTool.Outcome outcome = FindDeadCodeTool.candidatesOf(first.walked, nameCalled,
            100, List.of(), method -> 0);

        assertEquals(1, first.walked.size());
        assertTrue("the form module's call keeps the export alive", outcome.candidates.isEmpty()); //$NON-NLS-1$
    }

    /**
     * Form modules ahead of the common modules do not spend the candidate cap: the walk reads
     * them for their name-written calls alone, and a dead export behind them is still reported.
     */
    @Test
    public void formModulesAheadDoNotSpendTheCandidateCap()
    {
        List<IFile> files = new ArrayList<>();
        for (int i = 0; i < 5000; i++)
        {
            files.add(fileAt("src/CommonForms/Форма" + i + "/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        files.add(fileAt("src/CommonModules/Подготовка/Module.bsl")); //$NON-NLS-1$
        Module form = bareModule();
        Module подготовка = moduleAt("CommonModules/Подготовка/Module.bsl", "НичемНеВызван"); //$NON-NLS-1$ //$NON-NLS-2$
        Set<String> nameCalled = new HashSet<>();

        FindDeadCodeTool.FirstPass first = FindDeadCodeTool.walkSources(files,
            path -> path.startsWith("CommonForms/") ? form : подготовка, //$NON-NLS-1$
            false, null, null, MODULES, nameCalled, WatchForCancel.begin());
        FindDeadCodeTool.Outcome outcome = FindDeadCodeTool.candidatesOf(first.walked, nameCalled,
            100, List.of(), method -> 0);

        assertTrue("the common module behind five thousand form modules is still walked", //$NON-NLS-1$
            outcome.candidates.stream()
                .anyMatch(candidate -> "НичемНеВызван".equals(candidate.methodName))); //$NON-NLS-1$
    }

    /**
     * The collection reads are bounded on their own: past the name-call collection cap the walk
     * stops, instead of reading a form module for every file of an unbounded tree.
     */
    @Test
    public void theNameCallCollectionCapStopsTheWalk()
    {
        List<IFile> files = new ArrayList<>();
        for (int i = 0; i < 20001; i++)
        {
            files.add(fileAt("src/CommonForms/Форма" + i + "/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Module form = bareModule();

        FindDeadCodeTool.FirstPass first = FindDeadCodeTool.walkSources(files, path -> form,
            false, null, null, MODULES, new HashSet<>(), WatchForCancel.begin());

        assertEquals(20000, first.modulesExcludedForms);
        assertTrue("nothing the caps refused is a candidate", first.walked.isEmpty()); //$NON-NLS-1$
    }

    /**
     * A cancel that arrives while the walk's last module is loading is still reported: the cap
     * checks do not shortcut the question, and the walk asks once more after its last load.
     */
    @Test
    public void aCancelDuringTheLastLoadIsStillReported()
    {
        ToolCallScope.Cancellation flag = new ToolCallScope.Cancellation();
        ToolCallScope.enter(ToolCallScope.forCancellation(flag));
        try
        {
            WatchForCancel watch = WatchForCancel.begin();
            Module подготовка =
                moduleAt("CommonModules/Подготовка/Module.bsl", "НичемНеВызван"); //$NON-NLS-1$ //$NON-NLS-2$

            FindDeadCodeTool.FirstPass first = FindDeadCodeTool.walkSources(
                List.of(fileAt("src/CommonModules/Подготовка/Module.bsl")), //$NON-NLS-1$
                path -> {
                    flag.cancel("the operator asked to stop"); //$NON-NLS-1$
                    return подготовка;
                },
                false, null, null, MODULES, new HashSet<>(), watch);

            assertEquals(1, first.walked.size());
            assertTrue("a cancel that arrived during a load is not lost with the last question", //$NON-NLS-1$
                watch.stopped());
        }
        finally
        {
            ToolCallScope.exit();
        }
    }

    /**
     * An empty candidate list of a walk a cap stopped is named incomplete, not a finished scope.
     */
    @Test
    public void anEmptyListACapStoppedIsNamedIncomplete()
    {
        List<IFile> files = new ArrayList<>();
        for (int i = 0; i < 5001; i++)
        {
            files.add(fileAt("src/CommonModules/Модуль" + i + "/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        FindDeadCodeTool.FirstPass first = FindDeadCodeTool.walkSources(files, path -> bareModule(),
            false, null, null, MODULES, new HashSet<>(), WatchForCancel.begin());
        FindDeadCodeTool.Outcome outcome = FindDeadCodeTool.candidatesOf(first.walked,
            new HashSet<>(), 100, List.of(), method -> 0);

        String report = new FindDeadCodeTool().format("Проект", outcome, first, 100, Map.of(), //$NON-NLS-1$
            null);

        assertTrue(report, report.contains("Nothing had been found when the scan stopped.")); //$NON-NLS-1$
        assertFalse(report, report.contains("No dead export methods found in the scanned scope.")); //$NON-NLS-1$
        assertTrue(report, report.contains("candidate scan cap")); //$NON-NLS-1$
    }

    /**
     * A load that fails still counts toward its cap: a walk of a configuration whose model is not
     * built stops at the cap instead of trying every file of the tree.
     */
    @Test
    public void aLoadThatFailsStillCountsTowardTheCap()
    {
        List<IFile> files = new ArrayList<>();
        for (int i = 0; i < 6000; i++)
        {
            files.add(fileAt("src/CommonModules/Модуль" + i + "/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        FindDeadCodeTool.FirstPass first = FindDeadCodeTool.walkSources(files, path -> null,
            false, null, null, MODULES, new HashSet<>(), WatchForCancel.begin());

        assertEquals(5000, first.modulesScanned);
        assertEquals(5000, first.loadFailures);
        assertTrue(first.walked.isEmpty());
    }

    /**
     * The name-written calls of a module outside the family prefix and the path filter are still
     * collected: the filters choose which modules give up candidates, not whose calls the scan
     * reads.
     */
    @Test
    public void aModuleOutsideTheFiltersStillGivesUpItsNameCalls()
    {
        Module caller = moduleCallingInBackground("Подготовка.РассчитатьВсе"); //$NON-NLS-1$
        Module подготовка = moduleAt("CommonModules/Подготовка/Module.bsl", //$NON-NLS-1$
            "РассчитатьВсе", "НичемНеВызван"); //$NON-NLS-1$ //$NON-NLS-2$
        Set<String> nameCalled = new HashSet<>();

        FindDeadCodeTool.FirstPass first = FindDeadCodeTool.walkSources(
            List.of(fileAt("src/Documents/Заказ/Module.bsl"), //$NON-NLS-1$
                fileAt("src/CommonModules/Подготовка/Module.bsl")), //$NON-NLS-1$
            path -> "Documents/Заказ/Module.bsl".equals(path) ? caller : подготовка, //$NON-NLS-1$
            false, "CommonModules/", "Подготовка", MODULES, nameCalled, WatchForCancel.begin()); //$NON-NLS-1$ //$NON-NLS-2$
        FindDeadCodeTool.Outcome outcome = FindDeadCodeTool.candidatesOf(first.walked, nameCalled,
            100, List.of(), method -> 0);

        assertEquals("the module outside the filters was no candidate", 1, first.walked.size()); //$NON-NLS-1$
        assertTrue("its name-written call still protects the export it names", //$NON-NLS-1$
            nameCalled.contains(FindDeadCodeTool.nameCallKey(
                "CommonModule.Подготовка.Module", "РассчитатьВсе"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(outcome.candidates.stream()
            .allMatch(candidate -> "НичемНеВызван".equals(candidate.methodName))); //$NON-NLS-1$
    }

    /**
     * The candidate cap note follows the stop the decision made, not the size the list happens to
     * reach: a list the decision filled without stopping is a finished list.
     */
    @Test
    public void theCandidateCapNoteFollowsTheStopTheDecisionMade()
    {
        FindDeadCodeTool.Candidate candidate = new FindDeadCodeTool.Candidate(
            "CommonModules/Подготовка/Module.bsl", "НичемНеВызван", 1, null); //$NON-NLS-1$ //$NON-NLS-2$
        FindDeadCodeTool.FirstPass first = new FindDeadCodeTool.FirstPass(List.of(), 0, 0, 0, 0,
            false, false);

        String finished = new FindDeadCodeTool().format("Проект", //$NON-NLS-1$
            new FindDeadCodeTool.Outcome(List.of(candidate), false, 1, 0, 0, 0), first, 1,
            Map.of(), null);
        String stopped = new FindDeadCodeTool().format("Проект", //$NON-NLS-1$
            new FindDeadCodeTool.Outcome(List.of(candidate), true, 1, 0, 0, 0), first, 1,
            Map.of(), null);

        assertFalse("a list of the limit's size the decision did not stop is no capped list", //$NON-NLS-1$
            finished.contains("candidate list capped")); //$NON-NLS-1$
        assertTrue(stopped, stopped.contains("candidate list capped")); //$NON-NLS-1$
    }

    /** The methods a scheduled job and an event subscription name are collected and protect. */
    @Test
    public void theMethodsOfJobsAndSubscriptionsAreCollected()
    {
        com._1c.g5.v8.dt.metadata.mdclass.Configuration configuration =
            com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createConfiguration();
        com._1c.g5.v8.dt.metadata.mdclass.CommonModule module =
            com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createCommonModule();
        module.setName("Подготовка"); //$NON-NLS-1$
        configuration.getCommonModules().add(module);
        com._1c.g5.v8.dt.metadata.mdclass.ScheduledJob job =
            com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createScheduledJob();
        job.setMethodName("Подготовка.РассчитатьВсе"); //$NON-NLS-1$
        configuration.getScheduledJobs().add(job);
        com._1c.g5.v8.dt.metadata.mdclass.EventSubscription subscription =
            com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createEventSubscription();
        subscription.setHandler("CommonModule.Подготовка.ПослеЗаписи"); //$NON-NLS-1$
        configuration.getEventSubscriptions().add(subscription);

        Set<String> nameCalled = new HashSet<>();
        FindDeadCodeTool.collectJobAndSubscriptionCalled(configuration, nameCalled);

        assertEquals(Set.of(
            FindDeadCodeTool.nameCallKey("CommonModule.Подготовка.Module", "РассчитатьВсе"), //$NON-NLS-1$ //$NON-NLS-2$
            FindDeadCodeTool.nameCallKey("CommonModule.Подготовка.Module", "ПослеЗаписи")), //$NON-NLS-1$ //$NON-NLS-2$
            nameCalled);

        Module подготовка = moduleAt("CommonModules/Подготовка/Module.bsl", //$NON-NLS-1$
            "ПослеЗаписи", "НичемНеВызван"); //$NON-NLS-1$ //$NON-NLS-2$
        FindDeadCodeTool.Outcome outcome = FindDeadCodeTool.candidatesOf(
            List.of(new FindDeadCodeTool.WalkedModule("CommonModules/Подготовка/Module.bsl", //$NON-NLS-1$
                подготовка)),
            nameCalled, 100, List.of(), method -> 0);
        assertEquals(1, outcome.candidates.size());
        assertEquals("НичемНеВызван", outcome.candidates.get(0).methodName); //$NON-NLS-1$
    }

    /**
     * Walks the module level from a root over a named call source.
     *
     * @param root the module walked first
     * @param source the call source to walk
     * @return the walk
     */
    private static BmReferencesHelper.BfsResult walk(Module root,
        BslCallGraphHelper.ModuleCallSource source)
    {
        LinkedHashMap<String, Module> roots = new LinkedHashMap<>();
        roots.put(ROOT, root);
        DependencyGraphTool.ModuleLookup lookup = fqn -> DependencyGraphTool.ModuleResolution
            .absent();
        return DependencyGraphTool.buildModuleGraph(roots, lookup, new ArrayList<>(),
            BmReferencesHelper.Direction.OUT, 1, 10, 100, null, WatchForCancel.begin(), source);
    }

    /**
     * A call source over the given name-written calls; the caller and callee lists answer nothing.
     *
     * @param callers module to the FQNs that call it, kept for the shape of the interface
     * @param nameCalls the calls the source reads out of every module
     * @return the source
     */
    private static BslCallGraphHelper.ModuleCallSource calling(Map<Module, List<String>> callers,
        List<BslCallGraphHelper.NameCall> nameCalls)
    {
        return new BslCallGraphHelper.ModuleCallSource()
        {
            @Override
            public List<String> callersOf(Module module)
            {
                return callers.getOrDefault(module, List.of());
            }

            @Override
            public List<BslCallGraphHelper.NameCall> nameCallsOf(Module module)
            {
                return nameCalls;
            }
        };
    }

    /** A module the walk can hold, answering nothing itself. */
    private static Module rootModule()
    {
        return (Module)Proxy.newProxyInstance(
            ANameCalledMethodIsNotReportedDeadTest.class.getClassLoader(),
            new Class<?>[]{Module.class, IBmObject.class, EObject.class},
            (proxy, method, args) -> {
                Class<?> type = method.getReturnType();
                if (!type.isPrimitive() || type == void.class)
                {
                    return null;
                }
                if (type == boolean.class)
                {
                    return Boolean.FALSE;
                }
                if (type == long.class)
                {
                    return Long.valueOf(0);
                }
                return Integer.valueOf(0);
            });
    }

    /** A module whose single statement runs a procedure in the background by name. */
    private static Module moduleCallingInBackground(String moduleAndMethod)
    {
        BslFactory factory = BslFactory.eINSTANCE;
        Module module = factory.createModule();
        Procedure method = factory.createProcedure();
        module.getMethods().add(method);
        DynamicFeatureAccess access = factory.createDynamicFeatureAccess();
        StaticFeatureAccess owner = factory.createStaticFeatureAccess();
        owner.setName("ДлительныеОперации"); //$NON-NLS-1$
        access.setSource(owner);
        access.setName("ВыполнитьВФоне"); //$NON-NLS-1$
        Invocation call = factory.createInvocation();
        call.setMethodAccess(access);
        StringLiteral literal = factory.createStringLiteral();
        literal.getLines().add("\"" + moduleAndMethod + "\""); //$NON-NLS-1$ //$NON-NLS-2$
        call.getParams().add(literal);
        SimpleStatement statement = factory.createSimpleStatement();
        statement.setLeft(call);
        method.getStatements().add(statement);
        return module;
    }

    /**
     * A module that declares the given exports, attached to the address a live module is loaded
     * from, so the FQN of the address names it.
     *
     * @param modulePath the module path relative to {@code src/}
     * @param exportNames the names of the exports to declare
     * @return the module
     */
    private static Module moduleAt(String modulePath, String... exportNames)
    {
        BslFactory factory = BslFactory.eINSTANCE;
        Module module = factory.createModule();
        for (String name : exportNames)
        {
            Procedure method = factory.createProcedure();
            method.setName(name);
            method.setExport(true);
            module.getMethods().add(method);
        }
        org.eclipse.emf.ecore.resource.Resource resource = new org.eclipse.emf.ecore.resource.impl.ResourceSetImpl()
            .createResource(org.eclipse.emf.common.util.URI
                .createURI("platform:/resource/Proj/src/" + modulePath)); //$NON-NLS-1$
        resource.getContents().add(module);
        return module;
    }

    /** A module declaring one method of the given name, with no address attached. */
    private static Module moduleDeclaring(String methodName)
    {
        BslFactory factory = BslFactory.eINSTANCE;
        Module module = factory.createModule();
        com._1c.g5.v8.dt.bsl.model.Method method = factory.createProcedure();
        method.setName(methodName);
        method.setExport(true);
        module.getMethods().add(method);
        return module;
    }

    /** A module whose methods hold no statements, the way a form module with no handlers reads. */
    private static Module bareModule()
    {
        BslFactory factory = BslFactory.eINSTANCE;
        Module module = factory.createModule();
        module.getMethods().add(factory.createProcedure());
        return module;
    }

    /** A module holding one statement, the way a form module holds its handlers. */
    private static Module moduleWithStatement(com._1c.g5.v8.dt.bsl.model.Expression expression)
    {
        BslFactory factory = BslFactory.eINSTANCE;
        Module module = factory.createModule();
        Procedure method = factory.createProcedure();
        module.getMethods().add(method);
        SimpleStatement statement = factory.createSimpleStatement();
        statement.setLeft(expression);
        method.getStatements().add(statement);
        return module;
    }

    /**
     * Adds an idle-handler attachment naming a method of the module itself.
     *
     * @param module the module
     * @param methodName the method name the attachment spells
     */
    private static void idleHandlerOn(Module module, String methodName)
    {
        BslFactory factory = BslFactory.eINSTANCE;
        Invocation call = factory.createInvocation();
        call.setMethodAccess(factory.createStaticFeatureAccess());
        ((StaticFeatureAccess)call.getMethodAccess()).setName("ПодключитьОбработчикОжидания"); //$NON-NLS-1$
        StringLiteral literal = factory.createStringLiteral();
        literal.getLines().add("\"" + methodName + "\""); //$NON-NLS-1$ //$NON-NLS-2$
        call.getParams().add(literal);
        SimpleStatement statement = factory.createSimpleStatement();
        statement.setLeft(call);
        module.getMethods().get(0).getStatements().add(statement);
    }

    /**
     * A notify description constructor naming a method of the named source.
     *
     * @param methodName the method name the first parameter spells
     * @param sourceName the name the second parameter spells
     * @return the constructor expression
     */
    private static com._1c.g5.v8.dt.bsl.model.OperatorStyleCreator notifyDescription(String methodName,
        String sourceName)
    {
        BslFactory factory = BslFactory.eINSTANCE;
        com._1c.g5.v8.dt.bsl.model.OperatorStyleCreator creator =
            factory.createOperatorStyleCreator();
        com._1c.g5.v8.dt.mcore.Type type = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE.createType();
        type.setName("ОписаниеОповещения"); //$NON-NLS-1$
        creator.setType(type);
        StringLiteral literal = factory.createStringLiteral();
        literal.getLines().add("\"" + methodName + "\""); //$NON-NLS-1$ //$NON-NLS-2$
        creator.getParams().add(literal);
        StaticFeatureAccess source = factory.createStaticFeatureAccess();
        source.setName(sourceName);
        creator.getParams().add(source);
        return creator;
    }

    /**
     * A source file standing at the given project-relative path.
     *
     * @param path the path, starting with {@code src/}
     * @return the file
     */
    private static IFile fileAt(String path)
    {
        org.eclipse.core.runtime.IPath relative = proxy(org.eclipse.core.runtime.IPath.class,
            "toString", path); //$NON-NLS-1$
        return proxy(IFile.class, "getProjectRelativePath", relative); //$NON-NLS-1$
    }

    /**
     * A proxy that answers one method and a default for every other.
     *
     * @param type the interface to implement
     * @param methodName the one method to answer
     * @param answer what it answers
     * @return the proxy
     */
    private static <T> T proxy(Class<T> type, String methodName, Object answer)
    {
        return type.cast(Proxy.newProxyInstance(
            ANameCalledMethodIsNotReportedDeadTest.class.getClassLoader(),
            new Class<?>[]{type}, (p, method, args) -> methodName.equals(method.getName())
                ? answer : defaultOf(method.getReturnType())));
    }

    /**
     * The value a method of an uninteresting kind answers on the proxy.
     *
     * @param type the method's return type
     * @return null, or the zero value of a primitive
     */
    private static Object defaultOf(Class<?> type)
    {
        if (!type.isPrimitive() || type == void.class)
        {
            return null;
        }
        if (type == boolean.class)
        {
            return Boolean.FALSE;
        }
        if (type == long.class)
        {
            return Long.valueOf(0);
        }
        return Integer.valueOf(0);
    }
}
