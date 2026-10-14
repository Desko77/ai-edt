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

    /** A call naming a method of the walked module itself is not an edge between two modules. */
    @Test
    public void aNameCallIntoTheWalkedModuleItselfIsNotAnEdge()
    {
        BslCallGraphHelper.NameCall self =
            new BslCallGraphHelper.NameCall(null, "ПослеЗагрузки", 3); //$NON-NLS-1$

        BmReferencesHelper.BfsResult walk = walk(rootModule(), calling(Map.of(), List.of(self)));

        assertTrue(walk.edges.isEmpty());
        assertEquals(List.of(ROOT), new ArrayList<>(walk.nodes.keySet()));
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
        List<Object[]> rows = List.of(
            new Object[]{"CommonModules/Подготовка/Module.bsl", "РассчитатьВсе", //$NON-NLS-1$ //$NON-NLS-2$
                "CommonModule.Подготовка.Module", true}, //$NON-NLS-1$
            new Object[]{"CommonModules/Подготовка/Module.bsl", "НичемНеВызван", //$NON-NLS-1$ //$NON-NLS-2$
                "CommonModule.Подготовка.Module", false}, //$NON-NLS-1$
            new Object[]{"CommonModules/БезАдреса/Module.bsl", "РассчитатьВсе", null, false}); //$NON-NLS-1$ //$NON-NLS-2$

        Set<String> nameCalled = Set.of(
            FindDeadCodeTool.nameCallKey("CommonModule.Подготовка.Module", "РАССЧИТАТЬВСЕ")); //$NON-NLS-1$ //$NON-NLS-2$

        List<FindDeadCodeTool.Candidate> candidates = new ArrayList<>();
        for (Object[] row : rows)
        {
            candidates.add(new FindDeadCodeTool.Candidate((String)row[0], (String)row[1],
                5, (String)row[2]));
        }

        List<FindDeadCodeTool.Candidate> kept =
            FindDeadCodeTool.withoutNameCalled(candidates, nameCalled);

        // The third row has no address to be collected under, so nothing protects it.
        assertEquals(2, kept.size());
        assertFalse(kept.stream().anyMatch(c -> "РассчитатьВсе".equals(c.methodName) //$NON-NLS-1$
            && c.moduleFqn != null));
        assertTrue(kept.stream().anyMatch(c -> "НичемНеВызван".equals(c.methodName))); //$NON-NLS-1$
        assertTrue(kept.stream().anyMatch(c -> c.moduleFqn == null));
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
}
