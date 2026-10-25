/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ToolCallScope;
import ru.aiedt.mcp.server.support.UiSync;
import ru.aiedt.mcp.server.toolkit.IToolRoad;
import ru.aiedt.mcp.server.toolkit.ToolRoadOutcome;

/**
 * Imports an ordinary form out of the project's infobase into the project: the plugin's
 * {@code config_io} exports the form into a temporary directory of this bundle's own, the exported
 * {@code Ext/Form.bin} - a container byte for byte equal to a {@code Form.oform} - is re-read, and
 * the container the project gets is the exported one with the module entry of the chosen source:
 * the project's current container ({@code module=project}) or the export itself
 * ({@code module=infobase}).
 *
 * <p>The import goes into a form the project already has. The owner's model has to list the form
 * - a container no model object points at is not written, {@code create_form} is the operation
 * named to the caller for that - and {@code module=project} needs the project's own container,
 * because an empty module is not invented in place of one.</p>
 *
 * <p>{@code dryRun=true} stops after those two look-ups: a preflight of addresses, listing and
 * module source, answered with the plan and the explicit note that the form has not been
 * exported and the container and handler checks of the real import have not run. No export is
 * started, no temporary directory is created, nothing is written.</p>
 *
 * <p>The export runs through the plugin's tool road, never through the tool object: the road is
 * where the gates, the heavy-call limiter and the cancellation live. A slow export answers
 * {@code Pending}; the import resumes it until it finishes, no longer than the call's own
 * timeout or {@link #EXPORT_WAIT_SECONDS}. A cancellation of the import's own call - checked
 * before the call, after every resume and before the write - and the deadline both stop the run
 * by its run key and answer at once: the road drops the run as it cancels it, so there is
 * nothing left to wait on, while the Designer the run started goes on writing into the parent
 * and moves its service directory into place only after it exits. Nothing is written before
 * every handler the infobase layout binds is declared in the chosen module - the refusal names
 * the ones that are not.</p>
 *
 * <p>The export's output path points into a directory of its own under a parent this bundle
 * names {@code oform-import-<uuid>}: the export's service directory is created beside the output
 * path, so inside the parent, and removing the one parent takes everything. On the ordinary
 * paths - success, a refusal before the export, a refusal of the export - the parent is removed
 * before the answer; one that could not be removed is named in the answer as {@code leftBehind}.
 * After a cancellation or at the deadline the parent is named the same way, the answer says it
 * is being removed in the background, and a background task keeps removing it - the Designer can
 * put it back once, after it exits - no longer than
 * {@link #BACKGROUND_REMOVAL_CEILING_MILLIS}; a parent still there past that is written to the
 * bundle's log with its path.</p>
 */
public final class OrdinaryFormImport
{
    /** The tool the export runs through. */
    private static final String CONFIG_IO = "config_io"; //$NON-NLS-1$

    /** The export operation of that tool. */
    private static final String EXPORT_OPERATION = "export_infobase_objects"; //$NON-NLS-1$

    /** How long one resume waits before the loop looks again. */
    private static final long RESUME_WAIT_MILLIS = 2000L;

    /**
     * The bound of the whole wait for one export when the call carries no negotiated timeout of
     * its own: one designer start plus one form export stays inside it, and a run still going
     * past it is cancelled and refused instead of being waited on forever.
     */
    private static final long EXPORT_WAIT_SECONDS = 600L;

    /**
     * The ceiling of the background removal of a cancelled export's parent: the Designer's own
     * budget is 600 seconds and the exporter gives a process that outlives its abandonment a
     * further grace of 30 seconds, so this covers both plus a margin. A parent still there past
     * it is written to the bundle's log with its path.
     */
    private static final long BACKGROUND_REMOVAL_CEILING_MILLIS = 660_000L;

    /** The pause between one removal attempt of the background removal and the next. */
    private static final long BACKGROUND_ATTEMPT_PAUSE_MILLIS = 500L;

    /** What the production background removal runs after every removal attempt: nothing. */
    private static final Runnable NOTHING_BETWEEN_PASSES = () -> {
    };

    /**
     * How long the parent has to stay gone before the background removal stops: the export's
     * place step can put the directory back once, right after the Designer exits, and a removal
     * that stops at the first disappearance races exactly that.
     */
    private static final long BACKGROUND_QUIET_MILLIS = 2000L;

    /** What a refusal after a cancellation or at the deadline says about the parent directory. */
    private static final String BACKGROUND_REMOVAL_NOTE =
        ". The export's temporary directory is named as leftBehind and is being removed in the " //$NON-NLS-1$
            + "background, after the Designer has left it."; //$NON-NLS-1$

    /** The parent of everything one import places on the machine, under its temporary root. */
    private static final String TEMP_PREFIX = "oform-import-"; //$NON-NLS-1$

    /** The directory, under the parent, that the export's output path points at. */
    private static final String EXPORT_DIRECTORY = "export"; //$NON-NLS-1$

    /** The file the export places a form's container under. */
    private static final String EXPORT_SUFFIX = "/ext/form.bin"; //$NON-NLS-1$

    /** Where the module text comes from: the project's current container. */
    public static final String MODULE_PROJECT = "project"; //$NON-NLS-1$

    /** Where the module text comes from: the exported container itself. */
    public static final String MODULE_INFOBASE = "infobase"; //$NON-NLS-1$

    /** Reads the project's current container and writes the assembled one. */
    public interface ContainerSpace
    {
        /**
         * @param containerPath the {@code src}-relative path of the {@code Form.oform}
         * @return its bytes, or {@code null} when the project has no such container
         */
        byte[] currentContainer(String containerPath);

        /**
         * Writes the container only if it still matches the initial read.
         *
         * @param containerPath the {@code src}-relative path of the {@code Form.oform}
         * @param observed the bytes read before the export, or null when absent
         * @param bytes the assembled container
         * @throws IOException when the container changed or the workspace refuses the write
         */
        void write(String containerPath, byte[] observed, byte[] bytes) throws IOException;
    }

    /** Marks the form's owner after the write, the way every write of this bundle does. */
    public interface DeliveryMarker
    {
        /**
         * @param written whether the container was written
         * @return the delivery statement
         * @throws Exception when the owner was not marked
         */
        String mark(boolean written) throws Exception;
    }

    /** Says whether the project's model lists the form this import writes into. */
    public interface FormListing
    {
        /**
         * @return why the owner's model does not list the form, or {@code null} when it does
         */
        String notListed();
    }

    /** The resolved address of a form: its FQN and the {@code src}-relative path of its container. */
    static final class Address
    {
        /** The FQN, {@code Type.Object.Form.Name} or {@code CommonForm.Name}. */
        final String fqn;

        /** The {@code src}-relative path of the {@code Form.oform}. */
        final String container;

        /** Why the address is not one this operation works with, or {@code null}. */
        final String error;

        Address(String fqn, String container, String error)
        {
            this.fqn = fqn;
            this.container = container;
            this.error = error;
        }
    }

    /** One import, with everything the export and the write need. */
    static final class Plan
    {
        final String projectName;

        final String fqn;

        final String container;

        final String module;

        final String applicationId;

        final boolean dryRun;

        Plan(String projectName, String fqn, String container, String module, String applicationId, boolean dryRun)
        {
            this.projectName = projectName;
            this.fqn = fqn;
            this.container = container;
            this.module = module;
            this.applicationId = applicationId;
            this.dryRun = dryRun;
        }
    }

    private OrdinaryFormImport()
    {
        // static utility
    }

    /**
     * Imports the form, from the tool's arguments.
     *
     * @param project the project
     * @param params the tool's arguments
     * @return the answer
     */
    public static Map<String, Object> run(IProject project, Map<String, String> params)
    {
        Map<String, Object> answer = base(project == null ? null : project.getName());
        Address address = addressOf(text(params, "formFqn"), text(params, "modulePath")); //$NON-NLS-1$ //$NON-NLS-2$
        if (address.error != null)
        {
            return refused(answer, address.error);
        }
        String module = text(params, "module"); //$NON-NLS-1$
        if (module == null)
        {
            module = MODULE_PROJECT;
        }
        if (!MODULE_PROJECT.equals(module) && !MODULE_INFOBASE.equals(module))
        {
            return refused(answer, "module takes " + MODULE_PROJECT + " or " + MODULE_INFOBASE + "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        IToolRoad road = ToolRoadHolder.road();
        if (road == null)
        {
            return refused(answer, "The plugin's tool road service is not available, so no tool of the "
                + "plugin can be called; the plugin may be older than the seam this bundle uses.");
        }
        Plan plan = new Plan(project.getName(), address.fqn, address.container, module,
            text(params, "applicationId"), "true".equalsIgnoreCase(text(params, "dryRun"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return importForm(plan, road, Paths.get(System.getProperty("java.io.tmpdir")), //$NON-NLS-1$
            new ProjectListing(project, address.fqn), new ProjectSpace(project),
            new ProjectMarker(project, address.container));
    }

    /**
     * Runs one import through the seams. A test substitutes the road, the temporary root and the
     * project seams and passes no project.
     *
     * @param plan the import
     * @param road the plugin's tool road
     * @param tempRoot the directory the temporary export directory is created under
     * @param listing the owner's model listing of the form
     * @param space the project's containers
     * @param marker the baseline mark
     * @return the answer
     */
    static Map<String, Object> importForm(Plan plan, IToolRoad road, Path tempRoot, FormListing listing,
        ContainerSpace space, DeliveryMarker marker)
    {
        Map<String, Object> answer = base(plan.projectName);
        answer.put("formFqn", plan.fqn); //$NON-NLS-1$
        answer.put("container", plan.container); //$NON-NLS-1$
        answer.put("module", plan.module); //$NON-NLS-1$
        answer.put("dryRun", Boolean.valueOf(plan.dryRun)); //$NON-NLS-1$
        String notListed = listing.notListed();
        if (notListed != null)
        {
            return refused(answer, notListed + " import_from_infobase writes into a form the project " //$NON-NLS-1$
                + "already has; create_form is the operation that creates the form."); //$NON-NLS-1$
        }
        byte[] current = space.currentContainer(plan.container);
        if (!MODULE_INFOBASE.equals(plan.module) && current == null)
        {
            return refused(answer, "The project holds no " + plan.container //$NON-NLS-1$
                + ", so the project's module cannot be the source; repeat with module=" + MODULE_INFOBASE //$NON-NLS-1$
                + " to take the form's own module out of the infobase. An empty module is not written " //$NON-NLS-1$
                + "in its place."); //$NON-NLS-1$
        }
        if (plan.dryRun)
        {
            answer.put("success", Boolean.TRUE); //$NON-NLS-1$
            answer.put("written", Boolean.FALSE); //$NON-NLS-1$
            answer.put("moduleSource", MODULE_INFOBASE.equals(plan.module) ? MODULE_INFOBASE : MODULE_PROJECT); //$NON-NLS-1$
            answer.put("checked", List.of("the form is listed by its owner's model", //$NON-NLS-1$
                "with module=project, the project holds the form's current container")); //$NON-NLS-1$
            answer.put("note", "dryRun: a preflight only. The form has not been exported from the " //$NON-NLS-1$
                + "infobase, nothing was written and no temporary directory was created. The checks the " //$NON-NLS-1$
                + "real import makes on the exported container - that it reads as a container and that " //$NON-NLS-1$
                + "the module of the chosen source declares every handler the infobase layout binds - " //$NON-NLS-1$
                + "have not run."); //$NON-NLS-1$
            return answer;
        }
        Path parent = null;
        boolean backgroundRemoval = false;
        try
        {
            parent = Files.createDirectories(tempRoot.resolve(TEMP_PREFIX + UUID.randomUUID()));
            Path output = Files.createDirectories(parent.resolve(EXPORT_DIRECTORY));
            ExportEnd end = export(plan, output, parent, road);
            backgroundRemoval = end.backgroundRemoval;
            ToolRoadOutcome outcome = end.outcome;
            if (outcome == null)
            {
                return refused(answer, "The tool road answered nothing."); //$NON-NLS-1$
            }
            if (outcome.refused())
            {
                return refused(answer, "The export was refused: " + outcome.refusal()); //$NON-NLS-1$
            }
            if (outcome.failed())
            {
                return refused(answer, "The export failed: " + outcome.failure()); //$NON-NLS-1$
            }
            ExportOutcome exported = ExportOutcome.parse(outcome.text());
            if (!exported.ok)
            {
                answer.put("exportFiles", exported.files); //$NON-NLS-1$
                return refused(answer, "The infobase did not give the form: " //$NON-NLS-1$
                    + (exported.error == null ? "the export answered ok=false without saying why" : exported.error)); //$NON-NLS-1$
            }
            answer.put("exportFiles", exported.files); //$NON-NLS-1$
            Path bin = formBinOf(output, exported.files);
            if (bin == null)
            {
                return refused(answer, "The export placed no Ext/Form.bin for " + plan.fqn + " under " //$NON-NLS-1$ //$NON-NLS-2$
                    + output + "."); //$NON-NLS-1$
            }
            OrdinaryFormFile fromInfobase;
            try
            {
                fromInfobase = OrdinaryFormFile.read(bin);
            }
            catch (IOException | RuntimeException e)
            {
                return refused(answer, "The exported Form.bin does not read as a container: " + e.getMessage()); //$NON-NLS-1$
            }
            String layout = fromInfobase.formText();
            if (layout == null)
            {
                return refused(answer, "The exported Form.bin of " + plan.fqn + " holds no layout entry."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            OrdinaryFormFile currentFile = null;
            if (current != null)
            {
                try
                {
                    currentFile = OrdinaryFormFile.of(null, V8Container.read(current));
                }
                catch (IllegalArgumentException e)
                {
                    return refused(answer, "The project's current container of " + plan.fqn //$NON-NLS-1$
                        + " does not read: " + e.getMessage()); //$NON-NLS-1$
                }
            }
            String moduleText = moduleTextOf(plan, fromInfobase, currentFile);
            answer.put("moduleSource", MODULE_INFOBASE.equals(plan.module) ? MODULE_INFOBASE : MODULE_PROJECT); //$NON-NLS-1$
            List<String> taken = new ArrayList<>();
            for (String name : fromInfobase.entryNames())
            {
                if (!OrdinaryFormFile.MODULE_ENTRY.equals(name) || MODULE_INFOBASE.equals(plan.module))
                {
                    taken.add(name);
                }
            }
            answer.put("fromInfobase", taken); //$NON-NLS-1$
            List<String> bound = new ArrayList<>(HandlerBindings.boundProcedures(layout));
            answer.put("handlersChecked", bound); //$NON-NLS-1$
            List<String> missing = missingHandlers(layout, moduleText);
            if (!missing.isEmpty())
            {
                answer.put("missingHandlers", missing); //$NON-NLS-1$
                return refused(answer, "The layout of " + plan.fqn + " binds " + missing.size() //$NON-NLS-1$ //$NON-NLS-2$
                    + " handler(s) the module from " //$NON-NLS-1$
                    + (MODULE_INFOBASE.equals(plan.module) ? MODULE_INFOBASE : MODULE_PROJECT)
                    + " does not declare: " + String.join(", ", missing) //$NON-NLS-1$ //$NON-NLS-2$
                    + ". Nothing was written."); //$NON-NLS-1$
            }
            byte[] bytes;
            try
            {
                bytes = assembled(Files.readAllBytes(bin), MODULE_INFOBASE.equals(plan.module) ? null : moduleText);
            }
            catch (IOException e)
            {
                return refused(answer, "The container was not assembled: " + e.getMessage()); //$NON-NLS-1$
            }
            requireUnchanged(current, space.currentContainer(plan.container));
            boolean written = !Arrays.equals(current, bytes);
            if (written)
            {
                String cancelled = cancellationOf(ToolCallScope.current());
                if (cancelled != null)
                {
                    return refused(answer, "The import was " + cancelled //$NON-NLS-1$
                        + " before the container was written; nothing was written."); //$NON-NLS-1$
                }
                try
                {
                    space.write(plan.container, current, bytes);
                }
                catch (IOException e)
                {
                    return refused(answer, "The container was not written: " + e.getMessage()); //$NON-NLS-1$
                }
            }
            answer.put("success", Boolean.TRUE); //$NON-NLS-1$
            answer.put("written", Boolean.valueOf(written)); //$NON-NLS-1$
            try
            {
                answer.put("delivery", marker.mark(written)); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                answer.put("delivery", e.getMessage() == null ? "the owner was not marked" : e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return answer;
        }
        catch (IOException e)
        {
            return refused(answer, e.getMessage());
        }
        finally
        {
            if (parent != null)
            {
                if (backgroundRemoval)
                {
                    answer.put("leftBehind", parent.toString()); //$NON-NLS-1$
                }
                else
                {
                    String leftBehind = removeTree(parent);
                    if (leftBehind != null)
                    {
                        answer.put("leftBehind", leftBehind); //$NON-NLS-1$
                    }
                }
            }
        }
    }

    /**
     * Refuses a changed container, including changes to entries not interpreted by this bundle.
     *
     * @param observed the bytes read before exporting, or null when absent
     * @param current the bytes now present, or null when absent
     * @throws IOException when the bytes or the presence changed
     */
    private static void requireUnchanged(byte[] observed, byte[] current) throws IOException
    {
        if (!Arrays.equals(observed, current))
        {
            throw new IOException("the container changed after it was read. Read the form again and repeat the import. " //$NON-NLS-1$
                + "Nothing was written."); //$NON-NLS-1$
        }
    }

    /**
     * The container the import writes: every entry of the exported container, the module entry
     * replaced by the chosen module's text. A module text of {@code null} keeps the export's own;
     * a module text the export already holds changes nothing, so the container keeps the export's
     * original bytes.
     *
     * @param exportedBytes the exported {@code Form.bin}
     * @param moduleText the module text of the chosen source, or {@code null} to keep the export's
     * @return the container bytes
     * @throws IOException when the bytes do not read as a container, or the container cannot be
     *         written back to bytes
     */
    static byte[] assembled(byte[] exportedBytes, String moduleText) throws IOException
    {
        OrdinaryFormFile file;
        try
        {
            file = OrdinaryFormFile.of(null, V8Container.read(exportedBytes));
        }
        catch (IllegalArgumentException e)
        {
            throw new IOException(e.getMessage(), e);
        }
        if (moduleText != null && !moduleText.equals(file.moduleText()))
        {
            file.setModuleText(moduleText);
        }
        try
        {
            return file.toBytes();
        }
        catch (IllegalStateException e)
        {
            // The repackaging failure is an internal exception of the container writer; the
            // callers of this method declare IOException.
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * The handlers a layout binds that a module does not declare.
     *
     * @param layoutText the layout text; {@code null} for a form without one
     * @param moduleText the module text; {@code null} or empty for a form without one
     * @return the names, case-insensitive, in order of first appearance; empty when the module
     *         declares them all
     */
    static List<String> missingHandlers(String layoutText, String moduleText)
    {
        Set<String> bound = HandlerBindings.boundProcedures(layoutText);
        Set<String> declared = HandlerBindings.declaredProcedures(
            OrdinaryFormModule.splitLines(moduleText == null ? "" : moduleText)); //$NON-NLS-1$
        List<String> missing = new ArrayList<>();
        for (String name : bound)
        {
            if (!declared.contains(name))
            {
                missing.add(name);
            }
        }
        return missing;
    }

    /**
     * The address of the form: its FQN and the {@code src}-relative path of its container, from
     * either of the two arguments every operation of this tool names a form by.
     *
     * @param formFqn {@code Type.Object.Form.Name} or {@code CommonForm.Name}
     * @param modulePath the form's module address
     * @return the address, with {@code error} set when the arguments name no form of this kind
     */
    static Address addressOf(String formFqn, String modulePath)
    {
        if (formFqn != null && !formFqn.isEmpty())
        {
            String[] parts = formFqn.split("\\."); //$NON-NLS-1$
            if (parts.length == 2 && "CommonForm".equals(parts[0])) //$NON-NLS-1$
            {
                return new Address(formFqn, "CommonForms/" + parts[1] + "/" + OrdinaryFormLocator.FILE_NAME, null); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (parts.length == 4 && "Form".equals(parts[2])) //$NON-NLS-1$
            {
                String directory = MetadataTypeCatalog.getDirectoryName(parts[0]);
                if (directory == null)
                {
                    return new Address(null, null, parts[0] + " is not a metadata type that owns forms."); //$NON-NLS-1$
                }
                return new Address(formFqn,
                    directory + "/" + parts[1] + "/Forms/" + parts[3] + "/" + OrdinaryFormLocator.FILE_NAME, null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            return new Address(null, null,
                "formFqn names the form as Type.Object.Form.Name (Catalog.Banks.Form.ItemForm) or CommonForm.Name."); //$NON-NLS-1$
        }
        if (modulePath != null && !modulePath.isEmpty())
        {
            String normalized = modulePath.replace('\\', '/');
            while (normalized.startsWith("/")) //$NON-NLS-1$
            {
                normalized = normalized.substring(1);
            }
            if (normalized.regionMatches(true, 0, "src/", 0, 4)) //$NON-NLS-1$
            {
                normalized = normalized.substring(4);
            }
            String[] parts = normalized.split("/"); //$NON-NLS-1$
            if (parts.length == 5 && "Forms".equals(parts[2]) && isModuleFile(parts[4])) //$NON-NLS-1$
            {
                String type = MetadataTypeCatalog.getTypeByDirectoryName(parts[0]);
                if (type == null)
                {
                    return new Address(null, null, parts[0] + " is not a directory a form is stored at."); //$NON-NLS-1$
                }
                return new Address(type + "." + parts[1] + ".Form." + parts[3], //$NON-NLS-1$ //$NON-NLS-2$
                    parts[0] + "/" + parts[1] + "/Forms/" + parts[3] + "/" + OrdinaryFormLocator.FILE_NAME, null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            if (parts.length == 3 && "CommonForms".equals(parts[0]) && isModuleFile(parts[2])) //$NON-NLS-1$
            {
                return new Address("CommonForm." + parts[1], //$NON-NLS-1$
                    "CommonForms/" + parts[1] + "/" + OrdinaryFormLocator.FILE_NAME, null); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return new Address(null, null,
                "modulePath names the form's module as <dir>/<object>/Forms/<form>/Module.bsl " //$NON-NLS-1$
                    + "or CommonForms/<form>/Module.bsl."); //$NON-NLS-1$
        }
        return new Address(null, null,
            "import_from_infobase needs formFqn (Type.Object.Form.Name or CommonForm.Name) or modulePath."); //$NON-NLS-1$
    }

    /**
     * Calls the export through the road and waits for it: the caller's cancellation is checked
     * before the call and after every resume, a {@code Pending} outcome is resumed until the run
     * finishes, and no longer than the call's own timeout or {@link #EXPORT_WAIT_SECONDS}. On a
     * cancellation or at the deadline the run is cancelled by its run key and the answer is a
     * refusal at once - the road drops the run as it cancels it, so there is nothing left to
     * wait on, and the Designer the run started is left to the background removal of the
     * parent.
     *
     * @param plan the import
     * @param output the directory the export places its files into
     * @param parent the parent of everything this import places on the machine
     * @param road the tool road
     * @return what the wait ended with
     */
    private static ExportEnd export(Plan plan, Path output, Path parent, IToolRoad road)
    {
        ToolCallScope scope = ToolCallScope.current();
        String cancelled = cancellationOf(scope);
        if (cancelled != null)
        {
            return new ExportEnd(ToolRoadOutcome.refused(cancelled), false);
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("operation", EXPORT_OPERATION); //$NON-NLS-1$
        arguments.put("projectName", plan.projectName); //$NON-NLS-1$
        arguments.put("objects", List.of(plan.fqn)); //$NON-NLS-1$
        if (plan.applicationId != null)
        {
            arguments.put("applicationId", plan.applicationId); //$NON-NLS-1$
        }
        arguments.put("outputPath", output.toString()); //$NON-NLS-1$
        long waitSeconds = waitSeconds(scope);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
        ToolRoadOutcome outcome = road.call(CONFIG_IO, arguments, ToolRoadHolder.ORIGIN);
        while (outcome != null && !outcome.finished())
        {
            if (outcome.refused() || outcome.failed())
            {
                return new ExportEnd(outcome, false);
            }
            String stop = cancellationOf(scope);
            if (stop == null && System.nanoTime() - deadline >= 0)
            {
                stop = "the export did not finish within " + waitSeconds + " seconds and was cancelled"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (stop != null)
            {
                road.cancel(outcome.runKey());
                removeInTheBackground(parent);
                return new ExportEnd(ToolRoadOutcome.refused(stop + BACKGROUND_REMOVAL_NOTE), true);
            }
            outcome = road.resume(outcome.runKey(), RESUME_WAIT_MILLIS);
        }
        return new ExportEnd(outcome, false);
    }

    /**
     * @param scope the call's scope, or {@code null} outside a tool call
     * @return the refusal wording when the caller has cancelled, or {@code null} when it has not
     */
    private static String cancellationOf(ToolCallScope scope)
    {
        if (scope == null || !scope.cancellation().isCancelled())
        {
            return null;
        }
        String reason = scope.cancellation().reason();
        return "cancelled" + (reason == null ? "" : ": " + reason); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @param scope the call's scope, or {@code null} outside a tool call
     * @return the call's own negotiated timeout, or {@link #EXPORT_WAIT_SECONDS} when it has none
     */
    private static long waitSeconds(ToolCallScope scope)
    {
        if (scope != null && scope.timeoutSeconds() != ToolCallScope.UNSET)
        {
            return scope.timeoutSeconds();
        }
        return EXPORT_WAIT_SECONDS;
    }

    /** What waiting for the export ended with. */
    private static final class ExportEnd
    {
        /** The outcome of the run: finished, or the refusal a cancellation or the deadline built. */
        final ToolRoadOutcome outcome;

        /**
         * Whether the parent directory's removal was handed to the background task: the answer
         * names the parent as {@code leftBehind} instead of removing it before it is built.
         */
        final boolean backgroundRemoval;

        ExportEnd(ToolRoadOutcome outcome, boolean backgroundRemoval)
        {
            this.outcome = outcome;
            this.backgroundRemoval = backgroundRemoval;
        }
    }

    /**
     * Hands the parent directory's removal to a background task: a daemon thread, the choice the
     * plugin's own exporter makes for the work that outlives its caller (its {@code runUnderBudget}
     * worker and its {@code abandon} watcher). A cancelled run's Designer goes on writing into
     * the parent and moves its service directory into place only after it exits, so the removal
     * is repeated, with a pause, until the parent has been gone for a quiet while, and no longer
     * than {@link #BACKGROUND_REMOVAL_CEILING_MILLIS}; a parent still there past that is written
     * to the bundle's log with its path.
     *
     * @param directory the parent directory of the cancelled export
     */
    private static void removeInTheBackground(Path directory)
    {
        Thread cleaner = new Thread(() -> removeUntilItStaysGone(directory, BACKGROUND_REMOVAL_CEILING_MILLIS,
            BACKGROUND_QUIET_MILLIS, BACKGROUND_ATTEMPT_PAUSE_MILLIS, NOTHING_BETWEEN_PASSES),
            "oform-import-cleanup"); //$NON-NLS-1$
        cleaner.setDaemon(true);
        cleaner.start();
    }

    /**
     * The body of the background removal: one removal attempt per pause, until the directory has
     * been gone for the quiet interval given or the ceiling is past. The intervals and the pass
     * action are the seam a test drives the loop through; the production caller passes its own
     * constants and an action that does nothing.
     *
     * @param directory the directory to remove
     * @param ceilingMillis how long the removal may go on
     * @param quietMillis how long the directory has to stay gone before the removal stops
     * @param pauseMillis the pause between one removal attempt and the next
     * @param afterEachPass what runs after every removal attempt
     */
    static void removeUntilItStaysGone(Path directory, long ceilingMillis, long quietMillis, long pauseMillis,
        Runnable afterEachPass)
    {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ceilingMillis);
        long goneSince = 0L;
        while (System.nanoTime() - end < 0)
        {
            removeTree(directory);
            afterEachPass.run();
            if (Files.exists(directory))
            {
                goneSince = 0L;
            }
            else if (goneSince == 0L)
            {
                goneSince = System.nanoTime();
            }
            else if (System.nanoTime() - goneSince >= TimeUnit.MILLISECONDS.toNanos(quietMillis))
            {
                return;
            }
            if (!pause(pauseMillis))
            {
                break;
            }
        }
        if (Files.exists(directory))
        {
            Activator.logWarning("ordinary_forms: the temporary directory of a cancelled import " //$NON-NLS-1$
                + "was not removed: " + directory); //$NON-NLS-1$
        }
    }

    /**
     * @param millis how long to pause
     * @return whether the pause was spent, {@code false} when the thread was interrupted
     */
    private static boolean pause(long millis)
    {
        try
        {
            Thread.sleep(millis);
            return true;
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The module text of the chosen source.
     *
     * @param plan the import
     * @param fromInfobase the exported container
     * @param currentFile the project's current container, or {@code null} when the project has none
     * @return the module text; empty for a source that holds none
     */
    private static String moduleTextOf(Plan plan, OrdinaryFormFile fromInfobase, OrdinaryFormFile currentFile)
    {
        if (MODULE_INFOBASE.equals(plan.module))
        {
            return fromInfobase.moduleText() == null ? "" : fromInfobase.moduleText(); //$NON-NLS-1$
        }
        if (currentFile == null || currentFile.moduleText() == null)
        {
            return ""; //$NON-NLS-1$
        }
        return currentFile.moduleText();
    }

    /**
     * Where the export placed the form's container.
     *
     * @param output the export's output directory
     * @param files the files the export named, or {@code null}
     * @return the {@code Ext/Form.bin}, or {@code null} when no file of that shape is there
     * @throws IOException when the tree cannot be walked, or more than one such file is there
     */
    private static Path formBinOf(Path output, List<String> files) throws IOException
    {
        List<Path> named = new ArrayList<>();
        if (files != null)
        {
            for (String file : files)
            {
                if (file.replace('\\', '/').toLowerCase(Locale.ROOT).endsWith(EXPORT_SUFFIX))
                {
                    Path candidate = output.resolve(file);
                    if (Files.isRegularFile(candidate))
                    {
                        named.add(candidate);
                    }
                }
            }
        }
        if (named.size() == 1)
        {
            return named.get(0);
        }
        if (named.size() > 1)
        {
            throw new IOException("the export placed " + named.size() + " files under an Ext/Form.bin name"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        List<Path> walked = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(output))
        {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().replace('\\', '/').toLowerCase(Locale.ROOT).endsWith(EXPORT_SUFFIX))
                .forEach(walked::add);
        }
        if (walked.size() > 1)
        {
            throw new IOException("the export placed " + walked.size() + " files under an Ext/Form.bin name"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return walked.isEmpty() ? null : walked.get(0);
    }

    /** What the export answered, read out of its JSON. */
    private static final class ExportOutcome
    {
        boolean ok;

        String error;

        List<String> files;

        static ExportOutcome parse(String text)
        {
            ExportOutcome out = new ExportOutcome();
            if (text == null || text.trim().isEmpty())
            {
                out.error = "the export answered nothing"; //$NON-NLS-1$
                return out;
            }
            try
            {
                JsonElement parsed = JsonParser.parseString(text);
                if (!parsed.isJsonObject())
                {
                    out.error = "the export answered no JSON object"; //$NON-NLS-1$
                    return out;
                }
                if (parsed.getAsJsonObject().get("ok") != null) //$NON-NLS-1$
                {
                    out.ok = parsed.getAsJsonObject().get("ok").getAsBoolean(); //$NON-NLS-1$
                }
                if (parsed.getAsJsonObject().get("error") != null) //$NON-NLS-1$
                {
                    out.error = parsed.getAsJsonObject().get("error").getAsString(); //$NON-NLS-1$
                }
                if (parsed.getAsJsonObject().get("files") != null //$NON-NLS-1$
                    && parsed.getAsJsonObject().get("files").isJsonArray()) //$NON-NLS-1$
                {
                    out.files = new ArrayList<>();
                    JsonArray array = parsed.getAsJsonObject().get("files").getAsJsonArray(); //$NON-NLS-1$
                    for (JsonElement element : array)
                    {
                        if (element.isJsonPrimitive())
                        {
                            out.files.add(element.getAsString());
                        }
                    }
                }
                return out;
            }
            catch (RuntimeException e)
            {
                out.error = "the export answer does not parse: " + e.getMessage(); //$NON-NLS-1$
                return out;
            }
        }
    }

    private static boolean isModuleFile(String name)
    {
        return "Module.bsl".equalsIgnoreCase(name); //$NON-NLS-1$
    }

    /**
     * Removes the directory tree. A file an export still holds open keeps its directory alive on
     * a machine that refuses such a delete.
     *
     * @param directory the directory to remove
     * @return the directory that could not be removed, or {@code null} when it is gone
     */
    private static String removeTree(Path directory)
    {
        try (Stream<Path> walk = Files.walk(directory))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        catch (IOException e)
        {
            // named in the answer below; there is nothing else to do with a tree that cannot
            // even be walked
        }
        return Files.exists(directory) ? directory.toString() : null;
    }

    private static Map<String, Object> base(String projectName)
    {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("operation", "import_from_infobase"); //$NON-NLS-1$ //$NON-NLS-2$
        if (projectName != null)
        {
            answer.put("projectName", projectName); //$NON-NLS-1$
        }
        return answer;
    }

    private static Map<String, Object> refused(Map<String, Object> answer, String why)
    {
        answer.put("success", Boolean.FALSE); //$NON-NLS-1$
        answer.put("error", why); //$NON-NLS-1$
        return answer;
    }

    private static String text(Map<String, String> params, String key)
    {
        if (params == null)
        {
            return null;
        }
        String value = params.get(key);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /** The project's containers, read and written through the workspace. */
    static final class ProjectSpace implements ContainerSpace
    {
        private final IProject project;

        ProjectSpace(IProject project)
        {
            this.project = project;
        }

        @Override
        public byte[] currentContainer(String containerPath)
        {
            IFile file = file(containerPath);
            try (InputStream stream = file.getContents(true))
            {
                return stream.readAllBytes();
            }
            catch (CoreException | IOException e)
            {
                java.io.File onDisk = file.getLocation() == null ? null : file.getLocation().toFile();
                return onDisk != null && onDisk.isFile() ? read(onDisk) : null;
            }
        }

        /**
         * Writes the imported container after checking its editor buffer and the observed bytes.
         *
         * @param containerPath the container path relative to src
         * @param observed the bytes read before exporting, or null when absent
         * @param bytes the imported container bytes
         * @throws IOException when an editor is dirty, the bytes changed or the workspace rejects the write
         */
        @Override
        public void write(String containerPath, byte[] observed, byte[] bytes) throws IOException
        {
            IFile file = file(containerPath);
            OrdinaryFormModule.refuseWhileAnEditorHoldsUnsavedChanges(file);
            try
            {
                file.refreshLocal(IResource.DEPTH_ZERO, null);
                byte[] current = null;
                if (file.exists())
                {
                    try (InputStream stream = file.getContents(true))
                    {
                        current = stream.readAllBytes();
                    }
                }
                requireUnchanged(observed, current);
                createFolders(file.getParent());
                try (InputStream stream = new ByteArrayInputStream(bytes))
                {
                    if (file.exists())
                    {
                        file.setContents(stream, IResource.FORCE | IResource.KEEP_HISTORY, null);
                    }
                    else
                    {
                        file.create(stream, IResource.FORCE, null);
                    }
                }
            }
            catch (CoreException e)
            {
                throw new IOException(containerPath + ": " + e.getMessage(), e); //$NON-NLS-1$
            }
        }

        private IFile file(String containerPath)
        {
            return project.getFile(new org.eclipse.core.runtime.Path("src/" + containerPath)); //$NON-NLS-1$
        }

        private static byte[] read(java.io.File file)
        {
            try
            {
                return Files.readAllBytes(file.toPath());
            }
            catch (IOException e)
            {
                return null;
            }
        }

        private static void createFolders(org.eclipse.core.resources.IContainer container) throws CoreException
        {
            if (!(container instanceof IFolder folder) || folder.exists())
            {
                return;
            }
            createFolders(container.getParent());
            folder.create(IResource.FORCE, true, null);
        }
    }

    /**
     * The listing read off the EDT model the way {@code create_form} reads the owner: the
     * configuration, the owner by name, the owner's forms, the form by name.
     */
    static final class ProjectListing implements FormListing
    {
        private final IProject project;

        private final String formFqn;

        ProjectListing(IProject project, String formFqn)
        {
            this.project = project;
            this.formFqn = formFqn;
        }

        @Override
        public String notListed()
        {
            try
            {
                return UiSync.call(() -> {
                    Activator activator = Activator.getDefault();
                    IConfigurationProvider provider = activator == null ? null
                        : activator.getConfigurationProvider();
                    Configuration configuration = provider == null ? null : provider.getConfiguration(project);
                    if (configuration == null)
                    {
                        return "No configuration is loaded."; //$NON-NLS-1$
                    }
                    String[] parts = formFqn.split("\\."); //$NON-NLS-1$
                    if (parts.length == 2)
                    {
                        // CommonForm.Name: the form is a top-level object of the configuration
                        if (MetadataTypeCatalog.findObject(configuration, parts[0], parts[1]) == null)
                        {
                            return "The configuration lists no common form " + parts[1] + "."; //$NON-NLS-1$ //$NON-NLS-2$
                        }
                        return null;
                    }
                    MdObject owner = MetadataTypeCatalog.findObject(configuration, parts[0], parts[1]);
                    if (owner == null)
                    {
                        return "Owner not found: " + parts[0] + "." + parts[1] + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    }
                    EList<MdObject> forms = formsOf(owner);
                    if (forms == null)
                    {
                        return owner.eClass().getName() + " has no forms collection."; //$NON-NLS-1$
                    }
                    if (BmObjectHelper.findByName(forms, parts[3]) == null)
                    {
                        return "The model of " + parts[0] + "." + parts[1] + " lists no form " + parts[3] + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    }
                    return null;
                });
            }
            catch (RuntimeException e)
            {
                return e.getMessage() == null ? "The owner's model was not read." : e.getMessage(); //$NON-NLS-1$
            }
        }

        @SuppressWarnings("unchecked")
        private static EList<MdObject> formsOf(MdObject owner)
        {
            EStructuralFeature feature = owner.eClass().getEStructuralFeature("forms"); //$NON-NLS-1$
            Object value = feature == null ? null : owner.eGet(feature);
            return value instanceof EList ? (EList<MdObject>)value : null;
        }
    }

    /** The baseline mark after the container is in the project. */
    static final class ProjectMarker implements DeliveryMarker
    {
        private final IProject project;

        private final String containerPath;

        ProjectMarker(IProject project, String containerPath)
        {
            this.project = project;
            this.containerPath = containerPath;
        }

        @Override
        public String mark(boolean written) throws Exception
        {
            OrdinaryFormModule module = OrdinaryFormModule.locate(project,
                OrdinaryFormModule.moduleAddressOf(containerPath));
            if (module == null)
            {
                throw new IOException("the container is not reachable through the workspace"); //$NON-NLS-1$
            }
            return module.afterWrite(written);
        }
    }
}
