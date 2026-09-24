/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.support.SubsystemMembership;
import ru.aiedt.mcp.server.support.ToolCallScope;
import ru.aiedt.mcp.server.support.WalkNarrowing;

/**
 * A project with findings on both sides of every narrowing, so an empty answer cannot pass for a
 * narrow one: the inside finding has to be present and the outside finding absent.
 * <p>
 * The scans are the package-private methods the tools run after they have accepted a walk. Calling
 * {@code execute} for an accepted walk would wait on an SWT loop this runtime does not pump.
 * Refusals return before that wait, so those rows go through {@code execute}.
 * </p>
 */
public class NarrowingWalkTest
{
    private static final String PROJECT = "aiedt-narrowing-walk"; //$NON-NLS-1$

    private static final String INSIDE = "src/CommonModules/NarrowInside/Module.bsl"; //$NON-NLS-1$

    private static final String NESTED = "src/CommonModules/NarrowNested/Module.bsl"; //$NON-NLS-1$

    private static final String OUTSIDE = "src/CommonModules/NarrowOutside/Module.bsl"; //$NON-NLS-1$

    private static final String MODULE = "CommonModule.NarrowInside"; //$NON-NLS-1$

    private static final String NESTED_MODULE = "CommonModule.NarrowNested"; //$NON-NLS-1$

    private static final List<String> QUERY_SCOPES = List.of("project", "module", "method"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final List<String> METRICS_SCOPES = List.of("project", "subsystem"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final List<String> SENSITIVE_SCOPES = List.of("project", "subsystem", "module"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final Set<String> NO_WHERE = Set.of("NO_WHERE_ON_LARGE_TABLE"); //$NON-NLS-1$

    private static final Set<String> QUERY_AND_LOOP = Set.of(
        "NO_WHERE_ON_LARGE_TABLE", "QUERY_IN_LOOP"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final String TAIL_MODULE = "CommonModule.NarrowTail"; //$NON-NLS-1$

    private static IProject project;

    @BeforeClass
    public static void projectWithFindingsInsideAndOutside() throws Exception
    {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT);
        if (project.exists())
        {
            project.delete(true, true, null);
        }
        project.create(null);
        project.open(null);
        write(INSIDE, module("NarrowIn", "Catalog.InsideTable", "AAAAAAAAAAAAAAAAAAAAAAAA", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "NarrowSibling", "Catalog.SiblingTable", "DebtInside")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        write(NESTED, module("NarrowNested", "Catalog.NestedTable", "BBBBBBBBBBBBBBBBBBBBBBBB", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            null, null, "DebtNested")); //$NON-NLS-1$
        write(OUTSIDE, module("NarrowOut", "Catalog.OutsideTable", "CCCCCCCCCCCCCCCCCCCCCCCC", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            null, null, "DebtOutside")); //$NON-NLS-1$
        write("src/Subsystems/NarrowParent/NarrowParent.mdo", //$NON-NLS-1$
            subsystem("NarrowParent", "CommonModule.NarrowInside", "NarrowChild")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        write("src/Subsystems/NarrowParent/Subsystems/NarrowChild/NarrowChild.mdo", //$NON-NLS-1$
            subsystem("NarrowChild", "CommonModule.NarrowNested", null)); //$NON-NLS-1$ //$NON-NLS-2$
        write("src/CommonModules/NarrowTail/Module.bsl", tailModule()); //$NON-NLS-1$
        write("backup/CommonModules/NarrowInside/Module.bsl", //$NON-NLS-1$
            module("BackupIn", "Catalog.BackupTable", "BACKUPBACKUPBACKUPBACKUP", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                null, null, "DebtBackup")); //$NON-NLS-1$
    }

    @AfterClass
    public static void deleteProject() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(true, true, null);
        }
    }

    @Test
    public void queryProjectWalkKeepsInsideAndOutside() throws Exception
    {
        String json = queries();
        found(json, "Catalog.InsideTable", "Catalog.SiblingTable", "Catalog.NestedTable", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.OutsideTable"); //$NON-NLS-1$
    }

    @Test
    public void queryModuleByModuleFqnKeepsInsideDropsOutside() throws Exception
    {
        String json = queries("moduleFqn", MODULE); //$NON-NLS-1$
        found(json, "Catalog.InsideTable", "Catalog.SiblingTable"); //$NON-NLS-1$ //$NON-NLS-2$
        missing(json, "Catalog.NestedTable", "Catalog.OutsideTable"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void queryModuleByScopeKeepsInsideDropsOutside() throws Exception
    {
        String json = queries("scope", "module", "moduleFqn", MODULE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        found(json, "Catalog.InsideTable", "Catalog.SiblingTable"); //$NON-NLS-1$ //$NON-NLS-2$
        missing(json, "Catalog.OutsideTable"); //$NON-NLS-1$
    }

    @Test
    public void queryMethodBySelectorsKeepsInsideMethodDropsSibling() throws Exception
    {
        String json = queries("moduleFqn", MODULE, "methodName", "NarrowIn"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        found(json, "Catalog.InsideTable"); //$NON-NLS-1$
        missing(json, "Catalog.SiblingTable", "Catalog.OutsideTable"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void queryMethodByScopeKeepsInsideMethodDropsSibling() throws Exception
    {
        String json = queries("scope", "method", "moduleFqn", MODULE, "methodName", "NarrowIn"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        found(json, "Catalog.InsideTable"); //$NON-NLS-1$
        missing(json, "Catalog.SiblingTable", "Catalog.NestedTable"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void queryMethodDropsModuleCodeAfterTheClosingLine() throws Exception
    {
        String moduleWide = tailQueries("scope", "module", "moduleFqn", TAIL_MODULE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        found(moduleWide, "Catalog.TailBody", "Catalog.TailAfter", "QUERY_IN_LOOP", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Catalog.TailLastBody", "Catalog.TailAfterLast"); //$NON-NLS-1$ //$NON-NLS-2$
        String json = tailQueries("scope", "method", "moduleFqn", TAIL_MODULE, "methodName", "TailHead"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        found(json, "Catalog.TailBody"); //$NON-NLS-1$
        missing(json, "Catalog.TailAfter", "QUERY_IN_LOOP", "Catalog.TailLastBody", "Catalog.TailAfterLast"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void queryLastMethodDropsModuleCodeAfterIt() throws Exception
    {
        String json = tailQueries("scope", "method", "moduleFqn", TAIL_MODULE, "methodName", "TailLast"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        found(json, "Catalog.TailLastBody"); //$NON-NLS-1$
        missing(json, "Catalog.TailAfterLast", "QUERY_IN_LOOP", "Catalog.TailAfter", "Catalog.TailBody"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void queryUnknownModuleRefusesByName() throws Exception
    {
        String json = queries("moduleFqn", "CommonModule.NoSuchModule"); //$NON-NLS-1$ //$NON-NLS-2$
        refusal(json, "NoSuchModule"); //$NON-NLS-1$
    }

    @Test
    public void queryUnknownMethodRefusesByName() throws Exception
    {
        String json = queries("moduleFqn", MODULE, "methodName", "NoSuchMethod"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        refusal(json, "NoSuchMethod"); //$NON-NLS-1$
        missing(json, "Catalog.SiblingTable"); //$NON-NLS-1$
    }

    @Test
    public void rlsProjectWalkKeepsInsideAndOutside() throws Exception
    {
        String json = rls();
        found(json, "\"method\":\"NarrowIn\"", "\"method\":\"NarrowSibling\"", //$NON-NLS-1$ //$NON-NLS-2$
            "\"method\":\"NarrowNested\"", "\"method\":\"NarrowOut\""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void rlsModuleByModuleFqnKeepsInsideDropsOutside() throws Exception
    {
        String json = rls("moduleFqn", MODULE); //$NON-NLS-1$
        found(json, "\"method\":\"NarrowIn\"", "\"method\":\"NarrowSibling\""); //$NON-NLS-1$ //$NON-NLS-2$
        missing(json, "\"method\":\"NarrowNested\"", "\"method\":\"NarrowOut\""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void rlsModuleByScopeKeepsInsideDropsOutside() throws Exception
    {
        String json = rls("scope", "module", "moduleFqn", MODULE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        found(json, "\"method\":\"NarrowSibling\""); //$NON-NLS-1$
        missing(json, OUTSIDE); //$NON-NLS-1$
    }

    @Test
    public void rlsMethodBySelectorsKeepsInsideMethodDropsSibling() throws Exception
    {
        String json = rls("moduleFqn", MODULE, "methodName", "NarrowIn"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        found(json, "\"method\":\"NarrowIn\""); //$NON-NLS-1$
        missing(json, "\"method\":\"NarrowSibling\"", "\"method\":\"NarrowOut\""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void rlsMethodByScopeKeepsInsideMethodDropsSibling() throws Exception
    {
        String json = rls("scope", "method", "moduleFqn", MODULE, "methodName", "NarrowIn"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        found(json, "\"method\":\"NarrowIn\""); //$NON-NLS-1$
        missing(json, "\"method\":\"NarrowSibling\""); //$NON-NLS-1$
    }

    @Test
    public void rlsModuleCancelledBeforeTheScanDoesNotReadTheModule() throws Exception
    {
        String json = cancelled(() -> rls("scope", "module", "moduleFqn", MODULE)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        stoppedBeforeReading(json, "\"method\":\"NarrowIn\""); //$NON-NLS-1$
    }

    @Test
    public void rlsMethodCancelledBeforeTheScanDoesNotReadTheModule() throws Exception
    {
        String json = cancelled(() -> rls("scope", "method", "moduleFqn", MODULE, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "methodName", "NarrowIn")); //$NON-NLS-1$ //$NON-NLS-2$
        stoppedBeforeReading(json, "\"method\":\"NarrowIn\""); //$NON-NLS-1$
    }

    @Test
    public void rlsUnknownModuleRefusesByName() throws Exception
    {
        refusal(rls("moduleFqn", "CommonModule.NoSuchModule"), "NoSuchModule"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void rlsUnknownMethodRefusesByName() throws Exception
    {
        String json = rls("moduleFqn", MODULE, "methodName", "NoSuchMethod"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        refusal(json, "NoSuchMethod"); //$NON-NLS-1$
        missing(json, "\"method\":\"NarrowSibling\""); //$NON-NLS-1$
    }

    @Test
    public void metricsProjectListsDebtInsideAndOutside() throws Exception
    {
        String json = metrics();
        found(json, "DebtInside", "DebtNested", "DebtOutside"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void metricsSubsystemByNameKeepsCompositionAndDropsOutside() throws Exception
    {
        String json = metrics("subsystemName", "NarrowParent"); //$NON-NLS-1$ //$NON-NLS-2$
        found(json, "DebtInside", "DebtNested"); //$NON-NLS-1$ //$NON-NLS-2$
        missing(json, "DebtOutside"); //$NON-NLS-1$
        composition(json, 2);
    }

    @Test
    public void metricsSubsystemByScopeKeepsCompositionAndDropsOutside() throws Exception
    {
        String json = metrics("scope", "subsystem", "subsystemName", "NarrowParent"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        found(json, "DebtInside", "DebtNested"); //$NON-NLS-1$ //$NON-NLS-2$
        missing(json, "DebtOutside"); //$NON-NLS-1$
        composition(json, 2);
    }

    @Test
    public void metricsNestedSubsystemDropsParentOnlyObjects() throws Exception
    {
        String json = metrics("subsystemName", "NarrowChild"); //$NON-NLS-1$ //$NON-NLS-2$
        found(json, "DebtNested"); //$NON-NLS-1$
        missing(json, "DebtInside", "DebtOutside"); //$NON-NLS-1$ //$NON-NLS-2$
        composition(json, 1);
    }

    @Test
    public void metricsUnknownSubsystemRefusesByName() throws Exception
    {
        refusal(metrics("subsystemName", "NoSuchSubsystem"), "NoSuchSubsystem"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void sensitiveProjectKeepsInsideAndOutside() throws Exception
    {
        String json = sensitive();
        found(json, INSIDE, NESTED, OUTSIDE);
    }

    @Test
    public void sensitiveModuleByModuleFqnKeepsInsideDropsOutside() throws Exception
    {
        String json = sensitive("moduleFqn", MODULE); //$NON-NLS-1$
        found(json, INSIDE);
        missing(json, NESTED, OUTSIDE);
    }

    @Test
    public void sensitiveModuleByScopeKeepsInsideDropsOutside() throws Exception
    {
        String json = sensitive("scope", "module", "moduleFqn", MODULE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        found(json, INSIDE);
        missing(json, OUTSIDE);
    }

    @Test
    public void sensitiveSubsystemByNameIncludesNestedAndDropsOutside() throws Exception
    {
        String json = sensitive("subsystemName", "NarrowParent"); //$NON-NLS-1$ //$NON-NLS-2$
        found(json, INSIDE, NESTED);
        missing(json, OUTSIDE);
        composition(json, 2);
    }

    @Test
    public void sensitiveSubsystemByScopeIncludesNestedAndDropsOutside() throws Exception
    {
        String json = sensitive("scope", "subsystem", "subsystemName", "NarrowParent"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        found(json, INSIDE, NESTED);
        missing(json, OUTSIDE);
        composition(json, 2);
    }

    @Test
    public void sensitiveChildSubsystemDropsParent() throws Exception
    {
        String json = sensitive("subsystemName", "NarrowChild"); //$NON-NLS-1$ //$NON-NLS-2$
        found(json, NESTED);
        missing(json, INSIDE, OUTSIDE);
        composition(json, 1);
    }

    @Test
    public void sensitiveSubsystemDropsACopyOutsideTheSourceRoot() throws Exception
    {
        // The copy is a real finding when the walk is the whole project, so its absence from
        // the subsystem walk is the filter and not a token the scanner does not recognise.
        found(sensitive(), "backup/CommonModules/NarrowInside"); //$NON-NLS-1$
        String json = sensitive("scope", "subsystem", "subsystemName", "NarrowParent"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        found(json, INSIDE);
        missing(json, "backup/CommonModules/NarrowInside"); //$NON-NLS-1$
    }

    @Test
    public void sensitiveModuleCancelledBeforeTheScanDoesNotReadTheModule() throws Exception
    {
        String json = cancelled(() -> sensitive("scope", "module", "moduleFqn", MODULE)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        stoppedBeforeReading(json, INSIDE);
    }

    @Test
    public void sensitiveUnknownSubsystemRefusesByName() throws Exception
    {
        refusal(sensitive("subsystemName", "NoSuchSubsystem"), "NoSuchSubsystem"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void sensitiveUnknownModuleRefusesByName() throws Exception
    {
        refusal(sensitive("moduleFqn", "CommonModule.NoSuchModule"), "NoSuchModule"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void sensitiveNestedModuleIsNotTheParentModule() throws Exception
    {
        String json = sensitive("moduleFqn", NESTED_MODULE); //$NON-NLS-1$
        found(json, NESTED);
        missing(json, INSIDE, OUTSIDE);
    }

    @Test
    public void toolsAnswerEveryRefusedScopeRowBeforeWalking()
    {
        refuse("query", "method without module", "methodName 'Orphan'", "methodName", "Orphan"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        refuse("query", "project with module", "moduleFqn '" + MODULE + "'", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "scope", "project", "moduleFqn", MODULE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        refuse("query", "project with method", "methodName 'NarrowIn'", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "scope", "project", "methodName", "NarrowIn"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        refuse("query", "module without moduleFqn", "scope=module requires moduleFqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "scope", "module"); //$NON-NLS-1$ //$NON-NLS-2$
        refuse("query", "module with method", "methodName 'NarrowIn'", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "scope", "module", "moduleFqn", MODULE, "methodName", "NarrowIn"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        refuse("query", "method without module", "scope=method requires moduleFqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "scope", "method", "methodName", "NarrowIn"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        refuse("query", "method without method", "scope=method requires methodName", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "scope", "method", "moduleFqn", MODULE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        refuse("query", "subsystem unsupported", "subsystem", "scope", "subsystem"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        refuse("query", "unknown scope", "widget", "scope", "widget"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        refuse("rls", "method without module", "methodName 'Orphan'", "methodName", "Orphan"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        refuse("rls", "project with module", "does not accept", "scope", "project", "moduleFqn", MODULE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        refuse("rls", "module without moduleFqn", "scope=module requires moduleFqn", "scope", "module"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        refuse("rls", "method without method", "scope=method requires methodName", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "scope", "method", "moduleFqn", MODULE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        refuse("rls", "unknown scope", "Valid:", "scope", "widget"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        refuse("metrics", "project with subsystem", "subsystemName 'NarrowParent'", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "scope", "project", "subsystemName", "NarrowParent"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        refuse("metrics", "subsystem without name", "scope=subsystem requires subsystemName", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "scope", "subsystem"); //$NON-NLS-1$ //$NON-NLS-2$
        refuse("metrics", "module unsupported", "module", "scope", "module"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        refuse("metrics", "method unsupported", "method", "scope", "method"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        refuse("metrics", "unknown scope", "widget", "scope", "widget"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        refuse("sensitive", "module and subsystem", "conflicts", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "moduleFqn", MODULE, "subsystemName", "NarrowParent"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        refuse("sensitive", "project with module", "moduleFqn '" + MODULE + "'", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "scope", "project", "moduleFqn", MODULE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        refuse("sensitive", "project with subsystem", "subsystemName 'NarrowParent'", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "scope", "project", "subsystemName", "NarrowParent"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        refuse("sensitive", "module without moduleFqn", "scope=module requires moduleFqn", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "scope", "module"); //$NON-NLS-1$ //$NON-NLS-2$
        refuse("sensitive", "module with subsystem", "subsystemName 'NarrowParent'", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "scope", "module", "subsystemName", "NarrowParent"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        refuse("sensitive", "subsystem without name", "scope=subsystem requires subsystemName", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "scope", "subsystem"); //$NON-NLS-1$ //$NON-NLS-2$
        refuse("sensitive", "subsystem with module", "moduleFqn '" + MODULE + "'", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "scope", "subsystem", "moduleFqn", MODULE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        refuse("sensitive", "method unsupported", "method", "scope", "method"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        refuse("sensitive", "unknown scope", "widget", "scope", "widget"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    private static String tailQueries(String... pairs) throws Exception
    {
        WalkNarrowing.Decision decision = WalkNarrowing.decide(args(pairs), QUERY_SCOPES,
            WalkNarrowing.Selectors.MODULE_AND_METHOD);
        assertFalse(String.valueOf(decision.refusal()), decision.refused());
        return new DetectQueryAntiPatternsTool().runScan(project, decision, "all", "json", //$NON-NLS-1$ //$NON-NLS-2$
            QUERY_AND_LOOP);
    }

    private static String cancelled(Callable<String> scan) throws Exception
    {
        ToolCallScope.Cancellation flag = new ToolCallScope.Cancellation();
        flag.cancel("stop"); //$NON-NLS-1$
        ToolCallScope.enter(ToolCallScope.forCancellation(flag));
        try
        {
            return scan.call();
        }
        finally
        {
            ToolCallScope.exit();
        }
    }

    private static void stoppedBeforeReading(String json, String marker)
    {
        assertTrue(json, json.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(json, json.contains("cancelled by the operator")); //$NON-NLS-1$
        assertFalse(marker + " was read after the cancel: " + json, json.contains(marker)); //$NON-NLS-1$
    }

    private static String queries(String... pairs) throws Exception
    {
        WalkNarrowing.Decision decision = WalkNarrowing.decide(args(pairs), QUERY_SCOPES,
            WalkNarrowing.Selectors.MODULE_AND_METHOD);
        assertFalse(String.valueOf(decision.refusal()), decision.refused());
        return new DetectQueryAntiPatternsTool().runScan(project, decision, "all", "json", NO_WHERE); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String rls(String... pairs) throws Exception
    {
        Map<String, String> params = args(pairs);
        WalkNarrowing.Decision decision = WalkNarrowing.decide(params, QUERY_SCOPES,
            WalkNarrowing.Selectors.MODULE_AND_METHOD);
        assertFalse(String.valueOf(decision.refusal()), decision.refused());
        return new FindRlsViolationsTool().scan(project, params, decision);
    }

    private static String metrics(String... pairs) throws Exception
    {
        WalkNarrowing.Decision decision = WalkNarrowing.decide(args(pairs), METRICS_SCOPES,
            WalkNarrowing.Selectors.SUBSYSTEM_ONLY);
        assertFalse(String.valueOf(decision.refusal()), decision.refused());
        return new ProjectMetricsTool().collect(project, decision, true, 60, "json"); //$NON-NLS-1$
    }

    private static String sensitive(String... pairs) throws Exception
    {
        Map<String, String> params = args(pairs);
        params.put("checks", "HARDCODED_SECRET"); //$NON-NLS-1$ //$NON-NLS-2$
        WalkNarrowing.Decision decision = WalkNarrowing.decide(params, SENSITIVE_SCOPES,
            WalkNarrowing.Selectors.MODULE_AND_SUBSYSTEM);
        assertFalse(String.valueOf(decision.refusal()), decision.refused());
        return new SensitiveDataScanTool().runScan(project, params, decision);
    }

    private static void refuse(String tool, String row, String fragment, String... pairs)
    {
        Map<String, String> params = args(pairs);
        params.put("projectName", PROJECT); //$NON-NLS-1$
        String json;
        if ("query".equals(tool)) //$NON-NLS-1$
        {
            json = new DetectQueryAntiPatternsTool().execute(params);
        }
        else if ("rls".equals(tool)) //$NON-NLS-1$
        {
            json = new FindRlsViolationsTool().execute(params);
        }
        else if ("metrics".equals(tool)) //$NON-NLS-1$
        {
            json = new ProjectMetricsTool().execute(params);
        }
        else
        {
            json = new SensitiveDataScanTool().execute(params);
        }
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(row + " " + json, body.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        String error = body.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(row + " " + error, error.contains(fragment)); //$NON-NLS-1$
        missing(json, "Catalog.OutsideTable", "DebtOutside", OUTSIDE); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void found(String json, String... markers)
    {
        assertTrue(json, json.contains("\"success\":true")); //$NON-NLS-1$
        for (String marker : markers)
        {
            assertTrue(marker + " missing from " + json, json.contains(marker)); //$NON-NLS-1$
        }
    }

    private static void missing(String json, String... markers)
    {
        for (String marker : markers)
        {
            assertFalse(marker + " leaked into " + json, json.contains(marker)); //$NON-NLS-1$
        }
    }

    private static void refusal(String json, String named)
    {
        assertTrue(json, json.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(json, json.contains(named));
        missing(json, "Catalog.OutsideTable", "DebtOutside", OUTSIDE); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void composition(String json, int size)
    {
        assertTrue(json, json.contains(SubsystemMembership.NESTED_INCLUDED));
        assertTrue(json, json.contains("\"compositionSize\":" + size)); //$NON-NLS-1$
        assertTrue(json, json.contains("\"nestedSubsystemsIncluded\":true")); //$NON-NLS-1$
    }

    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            params.put(pairs[i], pairs[i + 1]);
        }
        return params;
    }

    private static String tailModule()
    {
        return "Процедура TailHead()\n" //$NON-NLS-1$
            + "\tЗапрос = Новый Запрос;\n" //$NON-NLS-1$
            + "\tЗапрос.Текст = \"ВЫБРАТЬ Код ИЗ Catalog.TailBody\";\n" //$NON-NLS-1$
            + "КонецПроцедуры\n" //$NON-NLS-1$
            + "Запрос = Новый Запрос;\n" //$NON-NLS-1$
            + "Запрос.Текст = \"ВЫБРАТЬ Код ИЗ Catalog.TailAfter\";\n" //$NON-NLS-1$
            + "Для Каждого Строка Из Таблица Цикл\n" //$NON-NLS-1$
            + "\tЗапрос.Выполнить();\n" //$NON-NLS-1$
            + "КонецЦикла;\n" //$NON-NLS-1$
            + "Процедура TailLast()\n" //$NON-NLS-1$
            + "\tЗапрос = Новый Запрос;\n" //$NON-NLS-1$
            + "\tЗапрос.Текст = \"ВЫБРАТЬ Код ИЗ Catalog.TailLastBody\";\n" //$NON-NLS-1$
            + "КонецПроцедуры\n" //$NON-NLS-1$
            + "Запрос = Новый Запрос;\n" //$NON-NLS-1$
            + "Запрос.Текст = \"ВЫБРАТЬ Код ИЗ Catalog.TailAfterLast\";\n" //$NON-NLS-1$
            + "Для Каждого Строка Из Другая Цикл\n" //$NON-NLS-1$
            + "\tЗапрос.Выполнить();\n" //$NON-NLS-1$
            + "КонецЦикла;\n"; //$NON-NLS-1$
    }

    private static String module(String method, String table, String token, String sibling,
        String siblingTable, String debt)
    {
        StringBuilder text = new StringBuilder();
        text.append("Процедура ").append(method).append("()\n"); //$NON-NLS-1$ //$NON-NLS-2$
        text.append("\tЗапрос = Новый Запрос;\n"); //$NON-NLS-1$
        text.append("\tЗапрос.Текст = \"ВЫБРАТЬ Код ИЗ ").append(table).append("\";\n"); //$NON-NLS-1$ //$NON-NLS-2$
        text.append("\tУстановитьПривилегированныйРежим(Истина);\n"); //$NON-NLS-1$
        text.append("\tТокен = \"Bearer ").append(token).append("\";\n"); //$NON-NLS-1$ //$NON-NLS-2$
        text.append("КонецПроцедуры\n"); //$NON-NLS-1$
        if (sibling != null)
        {
            text.append("Процедура ").append(sibling).append("()\n"); //$NON-NLS-1$ //$NON-NLS-2$
            text.append("\tЗапрос = Новый Запрос;\n"); //$NON-NLS-1$
            text.append("\tЗапрос.Текст = \"ВЫБРАТЬ Код ИЗ ").append(siblingTable).append("\";\n"); //$NON-NLS-1$ //$NON-NLS-2$
            text.append("\tУстановитьПривилегированныйРежим(Истина);\n"); //$NON-NLS-1$
            text.append("КонецПроцедуры\n"); //$NON-NLS-1$
        }
        text.append("Процедура ").append(debt).append("(А, Б, В, Г, Д, Е, Ж, З)\n"); //$NON-NLS-1$ //$NON-NLS-2$
        text.append("КонецПроцедуры\n"); //$NON-NLS-1$
        return text.toString();
    }

    private static String subsystem(String name, String content, String child)
    {
        StringBuilder text = new StringBuilder();
        text.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"); //$NON-NLS-1$
        text.append("<mdclass:Subsystem xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\">\n"); //$NON-NLS-1$
        text.append("  <name>").append(name).append("</name>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        text.append("  <content>").append(content).append("</content>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (child != null)
        {
            text.append("  <subsystems>").append(child).append("</subsystems>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        text.append("</mdclass:Subsystem>\n"); //$NON-NLS-1$
        return text.toString();
    }

    private static void write(String path, String content) throws Exception
    {
        IFile file = project.getFile(path);
        ensure(file.getParent());
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        file.create(new ByteArrayInputStream(bytes), true, null);
    }

    private static void ensure(IContainer container) throws Exception
    {
        if (container.exists())
        {
            return;
        }
        if (container.getParent() != null)
        {
            ensure(container.getParent());
        }
        if (container instanceof IFolder)
        {
            ((IFolder) container).create(true, true, null);
        }
    }
}
