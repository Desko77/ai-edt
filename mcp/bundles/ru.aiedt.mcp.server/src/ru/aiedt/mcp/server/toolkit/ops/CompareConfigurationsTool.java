/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmComparisonHelper;
import ru.aiedt.mcp.server.support.MetadataDiffEngine;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;
import ru.aiedt.mcp.server.support.UiSync;
import ru.aiedt.mcp.server.support.WatchForCancel;

/**
 * Compares two metadata configurations on different levels:
 * {@code object | attribute | form | module | template}.
 * <p>
 * <b>1.38 modes:</b> {@code projects} (two open EDT projects) or
 * {@code files} (two on-disk exports - single files or whole export
 * directories). VCS-aware modes (commits / branches / bm_vs_disk) are
 * deferred to 1.39: public {@code IBmModel.reload()} is not available.
 * <p>
 * Only the model levels (object, attribute of mode=projects) run under
 * {@code UiSync}; the file levels and the files mode read files and walk
 * directories on the calling thread.
 * <p>
 * The file levels answer with {@code failed} and {@code failedCount} beside the diff: what the
 * comparison could not read - a file that could not be opened, a directory whose listing failed
 * together with everything under it, a symbolic link, which is never followed. {@code
 * success:true} with {@code failedCount} above zero is an incomplete diff rather than a clean one.
 * The four lists name at most {@link #MAX_REPORTED_ENTRIES} entries between them, {@code truncated}
 * says when they were cut, and the {@code *Count} fields carry the totals the comparison reached.
 */
public class CompareConfigurationsTool implements IMcpTool
{
    public static final String NAME = "compare_configurations"; //$NON-NLS-1$

    /**
     * How many entries the four lists of a file-level answer may name between them.
     * <p>
     * An export of a large configuration differs in tens of thousands of files, and every one of
     * them named in the answer is bytes the client pays for before it can read the counts it
     * wanted. The cap is on what is named, not on what is counted: the {@code *Count} fields carry
     * the totals, {@code truncated} says the lists are shorter than them, and nothing was silently
     * dropped. Two thousand entries is roughly the point where a diff answer stops being read and
     * starts being skimmed.
     * </p>
     */
    private static final int MAX_REPORTED_ENTRIES = 2000;

    /**
     * The block a byte comparison reads at a time.
     * <p>
     * Two files are compared by streaming rather than by loading both: a template carrying images
     * runs to tens of megabytes, and holding two of them at once to answer one boolean is a way to
     * lose a session to an out-of-memory error. Sixty-four kilobytes is the usual read size and
     * still bounds what a comparison holds at any moment.
     * </p>
     */
    private static final int COMPARE_BLOCK_BYTES = 64 * 1024;

    /**
     * The largest file a line-by-line preview is built from.
     * <p>
     * The preview decodes both files as text and splits them into lines, so unlike the byte
     * comparison it cannot stream. Above this the answer says the preview was left out instead of
     * building it, which is the difference between a slower answer and none.
     * </p>
     */
    private static final long MAX_BYTES_TO_PREVIEW = 4L * 1024 * 1024;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `insights` `operation=compare_configurations`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Diff two metadata configurations at object, attribute, module, or template level. " //$NON-NLS-1$
            + "Modes: projects (compare two open EDT projects) or files (compare two on-disk exports). " //$NON-NLS-1$
            + "The answer carries `failed` and `failedCount` beside the diff: files that could not " //$NON-NLS-1$
            + "be read, directories whose listing failed, and symbolic links, which are not followed. " //$NON-NLS-1$
            + "`success:true` with `failedCount` above zero is an incomplete diff, not a clean one."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "mode=projects: first project name. mode=files: path to the first export " //$NON-NLS-1$
                    + "(a file, or the directory of an export)", true) //$NON-NLS-1$
            .stringProperty("mode", "projects | files (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("target", //$NON-NLS-1$
                "For projects: name of second project. For files: path to the second export " //$NON-NLS-1$
                    + "(a file, or the directory of an export)", true) //$NON-NLS-1$
            .stringProperty("level", "object | attribute | module | template (default object; " //$NON-NLS-1$ //$NON-NLS-2$
                + "attribute requires mode=projects)") //$NON-NLS-1$
            .stringProperty("scope", "project | objectFqn (default project). objectFqn narrows " //$NON-NLS-1$ //$NON-NLS-2$
                + "the comparison to one object.") //$NON-NLS-1$
            .stringProperty("objectFqn", "Object FQN when scope=objectFqn, or with " //$NON-NLS-1$ //$NON-NLS-2$
                + "level=attribute in mode=projects") //$NON-NLS-1$
            .stringProperty("format", "json | markdown (default json)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("showRenames", "Detect renames via structural similarity (default true)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String mode = JsonUtils.extractStringArgument(params, "mode"); //$NON-NLS-1$
        if (mode == null || mode.isEmpty())
        {
            return ToolResult.error("mode is required (projects | files)").toJson(); //$NON-NLS-1$
        }
        // VCS-aware modes deferred to 1.39
        if ("commits".equalsIgnoreCase(mode) || "branches".equalsIgnoreCase(mode) //$NON-NLS-1$ //$NON-NLS-2$
            || "bm_vs_disk".equalsIgnoreCase(mode)) //$NON-NLS-1$
        {
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("requestedMode", mode); //$NON-NLS-1$
            tag.put("hint", //$NON-NLS-1$
                "VCS-aware compare modes (commits / branches / bm_vs_disk) require git " //$NON-NLS-1$
                    + "shadow-clone + BmVsDiskDiffer; planned for 1.39 Phase F."); //$NON-NLS-1$
            return ToolResult.error("VCS-aware compare mode '" + mode + "' deferred to 1.39") //$NON-NLS-1$ //$NON-NLS-2$
                .put("vcsCompareDeferredTo139", tag) //$NON-NLS-1$
                .toJson();
        }
        if (!"projects".equalsIgnoreCase(mode) && !"files".equalsIgnoreCase(mode)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error("mode must be projects | files (1.38)").toJson(); //$NON-NLS-1$
        }
        boolean projects = "projects".equalsIgnoreCase(mode); //$NON-NLS-1$

        String level = orDefault(JsonUtils.extractStringArgument(params, "level"), "object"); //$NON-NLS-1$ //$NON-NLS-2$
        String format = orDefault(JsonUtils.extractStringArgument(params, "format"), "json"); //$NON-NLS-1$ //$NON-NLS-2$
        boolean showRenames = JsonUtils.extractBooleanArgument(params, "showRenames", true); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String target = JsonUtils.extractStringArgument(params, "target"); //$NON-NLS-1$
        if (projectName == null || target == null)
        {
            return ToolResult.error("projectName and target are required").toJson(); //$NON-NLS-1$
        }
        if (!isKnownLevel(level))
        {
            return ToolResult.error(
                "Unsupported level: " + level + " (object|attribute|module|template)").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String scope = present(JsonUtils.extractStringArgument(params, "scope")); //$NON-NLS-1$
        String objectFqn = present(JsonUtils.extractStringArgument(params, "objectFqn")); //$NON-NLS-1$
        String narrowingRefusal = scopeRefusal(projects, level, scope, objectFqn);
        if (narrowingRefusal != null)
        {
            return ToolResult.error(narrowingRefusal).toJson();
        }
        boolean narrowToFqn = "objectFqn".equalsIgnoreCase(scope); //$NON-NLS-1$
        // Normalized where the narrowing is decided, so every answer this call gives carries the
        // same name for the object it kept to. The catalogue takes the type in any recognized
        // spelling - plural, or Russian - while the diff names entries after the metadata model's
        // collections, and the object's directory is asked for by the English singular.
        String narrowFqn = narrowToFqn ? MetadataTypeCatalog.normalizeFqn(objectFqn) : null;

        try
        {
            // Only the metadata-model levels read the live model, and only those run under
            // UiSync. The file levels walk workspace files and the files mode reads plain
            // directories: holding the UI thread for them is what a long diff answered with
            // UiBusyException while the session was busy with something else.
            if (projects)
            {
                if ("module".equalsIgnoreCase(level) || "template".equalsIgnoreCase(level)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    return compareProjectSides(projectName, target, level, format, narrowFqn);
                }
                return UiSync.call(() -> compareProjects(projectName, target, level, format,
                    showRenames, params, narrowFqn));
            }
            return compareFiles(projectName, target, level, format, narrowFqn);
        }
        catch (Exception e)
        {
            Activator.logError("compare_configurations error", e); //$NON-NLS-1$
            return ToolResult.error(TextSuggest.safeMessage(e)).toJson();
        }
    }

    /**
     * Whether a level word is one this comparison takes.
     *
     * @param level the level argument.
     * @return whether it names a level the tool compares at
     */
    private static boolean isKnownLevel(String level)
    {
        return "object".equalsIgnoreCase(level) || "attribute".equalsIgnoreCase(level) //$NON-NLS-1$ //$NON-NLS-2$
            || "module".equalsIgnoreCase(level) || "template".equalsIgnoreCase(level); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The refusal for narrowing arguments that cannot act on this call, or {@code null} when they
     * can.
     * <p>
     * {@code scope} names the sides the comparison covers - the whole of both, or one object when
     * {@code objectFqn} names it. An argument that cannot act is refused by name rather than
     * accepted and dropped: the call would answer as though it had been applied, and the caller
     * has no way to learn otherwise.
     * </p>
     *
     * @param projects whether the mode is projects.
     * @param level the level argument.
     * @param scope the scope argument, trimmed; {@code null} when absent.
     * @param objectFqn the objectFqn argument, trimmed; {@code null} when absent.
     * @return the refusal, or {@code null} when the arguments act or are absent
     */
    private static String scopeRefusal(boolean projects, String level, String scope, String objectFqn)
    {
        if (scope != null && !"project".equalsIgnoreCase(scope) //$NON-NLS-1$
            && !"objectFqn".equalsIgnoreCase(scope)) //$NON-NLS-1$
        {
            return TextSuggest.invalidValue("scope", scope, List.of("project", "objectFqn")); //$NON-NLS-1$
        }
        if (!projects && "attribute".equalsIgnoreCase(level)) //$NON-NLS-1$
        {
            return "level=attribute needs the metadata model; call it with mode=projects."; //$NON-NLS-1$
        }
        if (scope == null || !"objectFqn".equalsIgnoreCase(scope)) //$NON-NLS-1$
        {
            // Without scope=objectFqn the objectFqn acts only as the subject of an
            // attribute-level comparison in projects mode; anywhere else it changes nothing.
            if (objectFqn != null && !(projects && "attribute".equalsIgnoreCase(level))) //$NON-NLS-1$
            {
                return "objectFqn is read with scope=objectFqn, or with level=attribute in " //$NON-NLS-1$
                    + "mode=projects; with neither it changes nothing in this comparison."; //$NON-NLS-1$
            }
            return null;
        }
        if (objectFqn == null)
        {
            return "scope=objectFqn requires objectFqn."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * A trimmed argument value.
     *
     * @param value the raw argument.
     * @return the trimmed value, or {@code null} when empty or absent
     */
    private static String present(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Compares the two open projects at the metadata-model levels, under UiSync in the caller.
     *
     * @param projectName the first project.
     * @param targetProjectName the second project.
     * @param level object or attribute.
     * @param format json or markdown.
     * @param showRenames whether rename detection runs at object level.
     * @param params the whole call, for the attribute level's objectFqn.
     * @param narrowFqn the object the comparison is narrowed to by {@code scope=objectFqn}, or
     *                  {@code null} for the whole of both sides.
     * @return the comparison answer.
     */
    private String compareProjects(String projectName, String targetProjectName, String level,
        String format, boolean showRenames, Map<String, String> params, String narrowFqn)
    {
        IProject p1 = ProjectResolver.resolve(projectName);
        IProject p2 = ProjectResolver.resolve(targetProjectName);
        if (p1 == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        if (p2 == null)
        {
            return ProjectResolver.notFound(targetProjectName).toJson();
        }
        IConfigurationProvider provider = Activator.getDefault().getConfigurationProvider();
        if (provider == null)
        {
            return ToolResult.error("configuration provider is not published as a service").toJson(); //$NON-NLS-1$
        }
        Configuration c1 = provider.getConfiguration(p1);
        Configuration c2 = provider.getConfiguration(p2);
        if (c1 == null || c2 == null)
        {
            return ToolResult.error("Could not load both configurations").toJson(); //$NON-NLS-1$
        }
        if ("object".equalsIgnoreCase(level)) //$NON-NLS-1$
        {
            MetadataDiffEngine.DiffResult diff = MetadataDiffEngine.diffObjects(c1, c2,
                showRenames);
            if (narrowFqn != null)
            {
                diff.retainOnly(narrowFqn);
            }
            Map<String, Object> diffMap = diff.toMap();
            if (narrowFqn != null)
            {
                diffMap.put("narrowedTo", narrowFqn); //$NON-NLS-1$
            }
            return formatResult(level, format, diffMap);
        }
        // attribute: the objectFqn argument names the one object this level reads, whether it
        // arrived with scope=objectFqn or on its own.
        String objectFqn = present(JsonUtils.extractStringArgument(params, "objectFqn")); //$NON-NLS-1$
        if (objectFqn == null)
        {
            return ToolResult.error("level=attribute requires objectFqn").toJson(); //$NON-NLS-1$
        }
        String[] parts = MetadataTypeCatalog.normalizeFqn(objectFqn).split("\\.", 2); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return ToolResult.error("objectFqn must be 'Type.Name'").toJson(); //$NON-NLS-1$
        }
        MdObject a = MetadataTypeCatalog.findObject(c1, parts[0], parts[1]);
        MdObject b = MetadataTypeCatalog.findObject(c2, parts[0], parts[1]);
        if (a == null || b == null)
        {
            return ToolResult.error("Object not found in one of the projects: " + objectFqn) //$NON-NLS-1$
                .toJson();
        }
        MetadataDiffEngine.DiffResult diff = MetadataDiffEngine.diffAttributes(a, b);
        Map<String, Object> diffMap = diff.toMap();
        diffMap.put("objectFqn", objectFqn); //$NON-NLS-1$
        return formatResult(level, format, diffMap);
    }

    /**
     * Compares the two open projects at the file levels - modules and templates - outside the UI
     * thread: the walk reads workspace files and asks nothing of the model.
     *
     * @param projectName the first project.
     * @param targetProjectName the second project.
     * @param level module or template.
     * @param format json or markdown.
     * @param narrowFqn the object whose files the walk is narrowed to by
     *                  {@code scope=objectFqn}, or {@code null} for all files.
     * @return the comparison answer.
     */
    private String compareProjectSides(String projectName, String targetProjectName, String level,
        String format, String narrowFqn)
    {
        IProject p1 = ProjectResolver.resolve(projectName);
        IProject p2 = ProjectResolver.resolve(targetProjectName);
        if (p1 == null)
        {
            return ProjectResolver.notFound(projectName).toJson();
        }
        if (p2 == null)
        {
            return ProjectResolver.notFound(targetProjectName).toJson();
        }
        String prefix = objectDirectoryPrefix(narrowFqn);
        if (narrowFqn != null && prefix == null)
        {
            return ToolResult.error("objectFqn '" + narrowFqn //$NON-NLS-1$
                + "' names no metadata object directory; name it like Catalog.Products").toJson(); //$NON-NLS-1$
        }
        WatchForCancel watch = WatchForCancel.begin();
        Walked<IFile> a = collectWorkspaceFiles(p1, level, prefix, watch);
        Walked<IFile> b = collectWorkspaceFiles(p2, level, prefix, watch);
        return formatFileDiff(a, b, level, format, narrowFqn, watch);
    }

    /**
     * Collects the files one level asks about in a workspace project.
     * <p>
     * The walk names what it kept and what it could not look at, so a traversal that failed part
     * way through is answered as the partial reading it is rather than as the whole project. It
     * stops when the operator cancels, which a visitor can only say by leaving through an
     * exception - the visitor contract has no other way to end a walk early.
     * </p>
     *
     * @param project the project.
     * @param level module or template.
     * @param prefix the directory prefix the walk is narrowed to, or {@code null} for all files.
     * @param watch the cancel watch of the call.
     * @return project-relative path to file, beside what the walk could not read
     */
    private Walked<IFile> collectWorkspaceFiles(IProject project, String level, String prefix,
        WatchForCancel watch)
    {
        Walked<IFile> walked = new Walked<>();
        try
        {
            project.accept(resource -> {
                if (watch.stopHere())
                {
                    walked.stopped = true;
                    throw new WalkStopped();
                }
                if (resource instanceof IFile)
                {
                    String key = resource.getProjectRelativePath().toString();
                    String slashed = key.replace('\\', '/');
                    if (fileKindKept(level, slashed) && underPrefix(slashed, prefix))
                    {
                        walked.files.put(key, (IFile) resource);
                    }
                }
                return true;
            });
        }
        catch (WalkStopped stoppedByOperator)
        {
            // The note the answer carries already says the walk was cut short.
        }
        catch (Exception e)
        {
            // A traversal that failed leaves a set of files that is not the project's. Naming the
            // failure is what keeps the caller from reading the rest as the whole answer.
            walked.notRead.add(new NotRead("", //$NON-NLS-1$
                project.getName() + " (walk stopped: " + TextSuggest.safeMessage(e) + ")")); //$NON-NLS-1$ //$NON-NLS-2$
            Activator.logWarning("compare_configurations: could not walk " + project.getName() //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
        }
        return walked;
    }

    /**
     * Compares two walks file by file and formats the answer for the level that produced them.
     *
     * @param a the first side.
     * @param b the second side.
     * @param level module or template.
     * @param format json or markdown.
     * @param narrowFqn the object the walk was narrowed to, for the answer; {@code null} when not
     *                  narrowed.
     * @param watch the cancel watch of the call.
     * @return the comparison answer.
     */
    private String formatFileDiff(Walked<IFile> a, Walked<IFile> b, String level, String format,
        String narrowFqn, WatchForCancel watch)
    {
        DiffLists lists = new DiffLists();
        boolean modules = "module".equalsIgnoreCase(level); //$NON-NLS-1$
        classify(a, b, lists, watch, modules
            ? (key, first, second) -> modulePair(key, first, second, watch)
            : (key, first, second) -> templatePair(key, first, second, watch));
        Map<String, Object> diff = new LinkedHashMap<>();
        lists.into(diff);
        if (narrowFqn != null)
        {
            diff.put("narrowedTo", narrowFqn); //$NON-NLS-1$
        }
        return formatResult(level, format, diff, watch.note(modules ? "modules" : "templates")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Reads one pair of workspace files as text and says what the comparison makes of them.
     *
     * @param key the project-relative path, for the answer.
     * @param first the file on the first side.
     * @param second the file on the second side.
     * @param watch the cancel watch of the call.
     * @return the verdict, with the line counts and the preview when the two differ
     */
    private static Pair modulePair(String key, IFile first, IFile second, WatchForCancel watch)
    {
        long sizeA = lengthOf(first);
        long sizeB = lengthOf(second);
        String omitted = previewOmitted(sizeA, sizeB, "modules"); //$NON-NLS-1$
        if (omitted != null)
        {
            Pair byBytes = templatePair(key, first, second, watch);
            if (byBytes.changed() == null)
            {
                return byBytes;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("file", key); //$NON-NLS-1$
            entry.put("aBytes", sizeA); //$NON-NLS-1$
            entry.put("bBytes", sizeB); //$NON-NLS-1$
            entry.put("previewOmitted", omitted); //$NON-NLS-1$
            return Pair.changed(entry);
        }
        String contentA = readText(first);
        String contentB = readText(second);
        if (contentA == null || contentB == null)
        {
            // A module that cannot be read is neither equal nor different, and leaving it out
            // says neither: it is named, so the caller knows a comparison is missing rather
            // than clean.
            return Pair.unread("module could not be read"); //$NON-NLS-1$
        }
        if (contentA.equals(contentB))
        {
            return Pair.same();
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("file", key); //$NON-NLS-1$
        entry.put("aLines", contentA.split("\\r?\\n").length); //$NON-NLS-1$ //$NON-NLS-2$
        entry.put("bLines", contentB.split("\\r?\\n").length); //$NON-NLS-1$ //$NON-NLS-2$
        entry.put("preview", buildLineDiffPreview(contentA, contentB)); //$NON-NLS-1$
        return Pair.changed(entry);
    }

    /**
     * Compares one pair of workspace files byte by byte, read as streams.
     *
     * @param key the project-relative path, for the answer.
     * @param first the file on the first side.
     * @param second the file on the second side.
     * @param watch the cancel watch of the call.
     * @return the verdict; a template that could not be read is named as unread rather than
     *         reported equal or different
     */
    private static Pair templatePair(String key, IFile first, IFile second, WatchForCancel watch)
    {
        try (InputStream a = first.getContents(); InputStream b = second.getContents())
        {
            return verdict(key, sameStreams(a, b, watch));
        }
        catch (Exception unreadable)
        {
            return Pair.unread(TextSuggest.safeMessage(unreadable));
        }
    }

    /**
     * The export directory of an object, as a prefix both a plain export and an EDT-style
     * {@code src/} tree are keyed by.
     *
     * @param objectFqn the object; {@code null} narrows nothing.
     * @return the directory relative to the export root, or {@code null} when the name opens with
     *         no metadata type this EDT knows.
     */
    private static String objectDirectoryPrefix(String objectFqn)
    {
        return objectFqn == null ? null : BmComparisonHelper.objectDirectoryOf(objectFqn);
    }

    /**
     * Whether a file key sits under the directory prefix an object names. A key under
     * {@code src/} is tried without it, so a plain export and an EDT-style tree narrow alike.
     *
     * @param key the file path, with forward separators.
     * @param prefix the directory prefix, or {@code null} to keep everything.
     * @return whether the file belongs to the narrowed walk
     */
    private static boolean underPrefix(String key, String prefix)
    {
        if (prefix == null)
        {
            return true;
        }
        String candidate = key.startsWith("src/") ? key.substring(4) : key; //$NON-NLS-1$
        return candidate.startsWith(prefix + "/"); //$NON-NLS-1$
    }

    /**
     * Reads a workspace file as text.
     *
     * @param file the file.
     * @return the content with the line endings normalized to {@code \n}, or {@code null} when the
     *         file could not be read
     */
    private static String readText(IFile file)
    {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(file.getContents(), StandardCharsets.UTF_8)))
        {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Returns first ~10 differing lines between two BSL texts as a structural
     * preview (not full unified diff).
     */
    private static List<String> buildLineDiffPreview(String a, String b)
    {
        String[] al = a.split("\\r?\\n"); //$NON-NLS-1$
        String[] bl = b.split("\\r?\\n"); //$NON-NLS-1$
        List<String> preview = new ArrayList<>();
        int max = Math.min(al.length, bl.length);
        for (int i = 0; i < max && preview.size() < 10; i++)
        {
            if (!al[i].equals(bl[i]))
            {
                preview.add("- " + al[i]); //$NON-NLS-1$
                preview.add("+ " + bl[i]); //$NON-NLS-1$
            }
        }
        if (al.length != bl.length && preview.size() < 10)
        {
            preview.add("# line count differs: A=" + al.length + " B=" + bl.length); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return preview;
    }

    /**
     * Compares two on-disk exports in the files mode: two files byte by byte, or two export
     * directories file by file. An export path is a directory as often as a file - a
     * Designer-XML export arrives as a tree - and reading a directory as a file answered with a
     * read error for a call the schema accepts.
     *
     * @param firstPath the first export, file or directory.
     * @param secondPath the second export, file or directory.
     * @param level the file kinds the comparison narrows to.
     * @param format json or markdown.
     * @param objectFqn the object the comparison is narrowed to by {@code scope=objectFqn}, or
     *                  {@code null} for the whole of both sides.
     * @return the comparison answer.
     */
    private String compareFiles(String firstPath, String secondPath, String level, String format,
        String objectFqn)
    {
        Path p1 = Paths.get(firstPath);
        Path p2 = Paths.get(secondPath);
        // Before anything is read through them: a symbolic link points outside the export that
        // names it, and following one compares a file neither side owns.
        String linkRefusal = symbolicLinkRefusal(p1, p2);
        if (linkRefusal != null)
        {
            return ToolResult.error(linkRefusal).toJson();
        }
        if (!Files.exists(p1))
        {
            return ToolResult.error("First export not found: " + p1).toJson(); //$NON-NLS-1$
        }
        if (!Files.exists(p2))
        {
            return ToolResult.error("Second export not found: " + p2).toJson(); //$NON-NLS-1$
        }
        boolean firstIsDirectory = Files.isDirectory(p1);
        boolean secondIsDirectory = Files.isDirectory(p2);
        if (firstIsDirectory || secondIsDirectory)
        {
            if (firstIsDirectory != secondIsDirectory)
            {
                return ToolResult.error("mode=files compares two files or two export " //$NON-NLS-1$
                    + "directories; one of these is a file and the other is a directory") //$NON-NLS-1$
                        .toJson();
            }
            if (objectFqn != null)
            {
                String prefix = objectDirectoryPrefix(objectFqn);
                if (prefix == null)
                {
                    return ToolResult.error("objectFqn '" + objectFqn //$NON-NLS-1$
                        + "' names no metadata object directory; name it like Catalog.Products") //$NON-NLS-1$
                            .toJson();
                }
                return compareExportDirectories(p1, p2, level, format, prefix, objectFqn);
            }
            return compareExportDirectories(p1, p2, level, format, null, null);
        }
        if (objectFqn != null)
        {
            return ToolResult.error("scope=objectFqn narrows a comparison of two export " //$NON-NLS-1$
                + "directories; two files are already as narrow as this comparison gets") //$NON-NLS-1$
                    .toJson();
        }
        String levelRefusal = fileLevelRefusal(level, p1, p2);
        if (levelRefusal != null)
        {
            return ToolResult.error(levelRefusal).toJson();
        }
        try
        {
            // Sizes first: two files of different length differ, and that answer costs no read.
            long firstSize = Files.size(p1);
            long secondSize = Files.size(p2);
            WatchForCancel watch = WatchForCancel.begin();
            Boolean same = sameBytes(p1, p2, watch);
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("firstSize", firstSize); //$NON-NLS-1$
            diff.put("secondSize", secondSize); //$NON-NLS-1$
            if (same == null)
            {
                return formatResult(level, format, diff, watch.note("files")); //$NON-NLS-1$
            }
            boolean identical = same;
            diff.put("identical", identical); //$NON-NLS-1$
            // For text level, also produce a preview diff
            if (!identical && isTextLike(firstPath))
            {
                addPreview(diff, p1, firstSize, p2, secondSize);
            }
            return formatResult(level, format, diff);
        }
        catch (Exception e)
        {
            return ToolResult.error("Failed to compare files: " + TextSuggest.safeMessage(e)).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Adds the line-diff preview of two text files that are known to differ.
     * <p>
     * The preview decodes both files whole, so it is left out above {@link #MAX_BYTES_TO_PREVIEW}
     * and the answer says so: a caller reading a missing {@code preview} would otherwise take a
     * byte-level difference for one with nothing to show.
     * </p>
     *
     * @param diff the answer being built.
     * @param first the first file.
     * @param firstSize the first file's length, already read.
     * @param second the second file.
     * @param secondSize the second file's length, already read.
     * @throws IOException when either file cannot be read
     */
    private static void addPreview(Map<String, Object> diff, Path first, long firstSize, Path second,
        long secondSize) throws IOException
    {
        String omitted = previewOmitted(firstSize, secondSize, "files"); //$NON-NLS-1$
        if (omitted != null)
        {
            diff.put("previewOmitted", omitted); //$NON-NLS-1$
            return;
        }
        String aStr = new String(Files.readAllBytes(first), StandardCharsets.UTF_8);
        String bStr = new String(Files.readAllBytes(second), StandardCharsets.UTF_8);
        if (!aStr.equals(bStr))
        {
            diff.put("preview", buildLineDiffPreview(aStr, bStr)); //$NON-NLS-1$
        }
    }

    /**
     * The refusal for an export path that is a symbolic link or a directory junction, or
     * {@code null} when neither is.
     *
     * @param first the first export.
     * @param second the second export.
     * @return the refusal, naming the side that is a link
     */
    static String symbolicLinkRefusal(Path first, Path second)
    {
        if (isLink(first))
        {
            return "The first export is a symbolic link or a junction: " + first //$NON-NLS-1$
                + ". This comparison does not follow links - name the directory itself."; //$NON-NLS-1$
        }
        if (isLink(second))
        {
            return "The second export is a symbolic link or a junction: " + second //$NON-NLS-1$
                + ". This comparison does not follow links - name the directory itself."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Whether a path is a symbolic link or another reparse point, such as a Windows directory
     * junction, that {@link Files#isSymbolicLink} does not report.
     *
     * @param path the path.
     * @return {@code true} for a link; {@code false} for a plain file or directory, or a path that
     *         does not exist
     */
    static boolean isLink(Path path)
    {
        if (Files.isSymbolicLink(path))
        {
            return true;
        }
        try
        {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther();
        }
        catch (IOException | RuntimeException unreadable)
        {
            return false;
        }
    }

    /**
     * Says why a line preview is left out, when either side is above {@link #MAX_BYTES_TO_PREVIEW}.
     *
     * @param firstSize the first side's length in bytes; negative when unknown.
     * @param secondSize the second side's length in bytes; negative when unknown.
     * @param what what is compared, in the plural - "files", "modules".
     * @return the note, or {@code null} when the preview is built
     */
    static String previewOmitted(long firstSize, long secondSize, String what)
    {
        if (firstSize <= MAX_BYTES_TO_PREVIEW && secondSize <= MAX_BYTES_TO_PREVIEW)
        {
            return null;
        }
        return "the " + what + " are " + firstSize + " and " + secondSize //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " bytes, above the " + MAX_BYTES_TO_PREVIEW //$NON-NLS-1$
            + "-byte limit of the line preview, which reads them whole"; //$NON-NLS-1$
    }

    /**
     * The length of a workspace file on disk.
     *
     * @param file the file.
     * @return the length in bytes, or {@code -1} when the file has no local location
     */
    private static long lengthOf(IFile file)
    {
        org.eclipse.core.runtime.IPath location = file.getLocation();
        return location == null ? -1 : location.toFile().length();
    }

    /**
     * Turns the result of a byte comparison into a verdict.
     *
     * @param key the path, for the answer.
     * @param same the result; {@code null} when the operator cancelled during the read
     * @return the verdict
     */
    private static Pair verdict(String key, Boolean same)
    {
        if (same == null)
        {
            return Pair.unread("the comparison was cancelled while this file was read"); //$NON-NLS-1$
        }
        return same ? Pair.same() : Pair.changed(key);
    }

    /**
     * The refusal for a level that cannot describe the two single files named, or {@code null}
     * when it can.
     * <p>
     * A level says what kind of file the comparison is about, and two files are already as narrow
     * as the comparison gets: a {@code .mdo} named with {@code level=module} used to be read,
     * compared and answered as an unchanged or changed module, which is a statement about a module
     * file that was never read. The level is applied to both sides, and a file that is not of the
     * level is refused by name.
     * </p>
     *
     * @param level the level argument.
     * @param first the first file.
     * @param second the second file.
     * @return the refusal, or {@code null} when the level describes both files
     */
    private static String fileLevelRefusal(String level, Path first, Path second)
    {
        boolean modules = "module".equalsIgnoreCase(level); //$NON-NLS-1$
        boolean templates = "template".equalsIgnoreCase(level); //$NON-NLS-1$
        for (Path side : List.of(first, second))
        {
            String name = side.getFileName() == null ? side.toString() : side.getFileName().toString();
            if (modules && !name.endsWith(".bsl")) //$NON-NLS-1$
            {
                return "level=module compares module files; " + name + " is not one. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Call it without level to compare these two files."; //$NON-NLS-1$
            }
            if (templates && !isTemplateFile(name))
            {
                return "level=template compares template files; " + name + " is not one. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Call it without level to compare these two files."; //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * Whether a file name is one the template level reads.
     *
     * @param name the file name.
     * @return whether it carries a template extension
     */
    private static boolean isTemplateFile(String name)
    {
        return name.endsWith(".mxl") || name.endsWith(".dcs") || name.endsWith(".epf"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Whether a file name is one the line preview is built for.
     *
     * @param name the file name.
     * @return whether the file is text a line diff can be shown for
     */
    private static boolean isTextLike(String name)
    {
        return name.endsWith(".bsl") || name.endsWith(".xml") //$NON-NLS-1$ //$NON-NLS-2$
            || name.endsWith(".mdo") || name.endsWith(".form"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Compares two export directories file by file, keyed by each file's path relative to its
     * root.
     *
     * @param first the first export directory.
     * @param second the second export directory.
     * @param level the file kinds the walk keeps: module keeps {@code .bsl}, template keeps the
     *              template extensions, object keeps everything.
     * @param format json or markdown.
     * @param prefix the directory prefix the walk is narrowed to, or {@code null} for all files.
     * @param narrowFqn the object the prefix came from, for the answer; {@code null} when not
     *                  narrowed.
     * @return the comparison answer.
     */
    private String compareExportDirectories(Path first, Path second, String level, String format,
        String prefix, String narrowFqn)
    {
        WatchForCancel watch = WatchForCancel.begin();
        Walked<Path> a = walkExport(first, level, prefix, watch);
        Walked<Path> b = walkExport(second, level, prefix, watch);
        DiffLists lists = new DiffLists();
        classify(a, b, lists, watch, (key, one, other) -> pathsPair(key, one, other, watch));
        Map<String, Object> diff = new LinkedHashMap<>();
        lists.into(diff);
        if (narrowFqn != null)
        {
            diff.put("narrowedTo", narrowFqn); //$NON-NLS-1$
        }
        return formatResult(level, format, diff, watch.note("files")); //$NON-NLS-1$
    }

    /**
     * Compares one pair of files on disk, in blocks.
     *
     * @param key the path relative to the export root, for the answer.
     * @param first the file on the first side.
     * @param second the file on the second side.
     * @param watch the cancel watch of the call.
     * @return the verdict; a file that could not be read is named as unread rather than reported
     *         equal or different
     */
    private static Pair pathsPair(String key, Path first, Path second, WatchForCancel watch)
    {
        try
        {
            return verdict(key, sameBytes(first, second, watch));
        }
        catch (IOException | RuntimeException unreadable)
        {
            return Pair.unread(TextSuggest.safeMessage(unreadable));
        }
    }

    /**
     * Walks one export tree and keeps the files the level and the narrowing ask about.
     * <p>
     * Written as an explicit walk of one directory at a time rather than one {@code Files.walk}:
     * a listing that fails half way through has to be answerable for the directory it failed on,
     * which a stream that throws out of the middle cannot say. Symbolic links are named and not
     * followed, and the walk stops when the operator cancels.
     * </p>
     *
     * @param root the export directory.
     * @param level the file kinds the walk keeps.
     * @param prefix the directory prefix the walk is narrowed to, or {@code null} for all files.
     * @param watch the cancel watch of the call.
     * @return path relative to the root, with forward separators, mapped to the file, beside what
     *         the walk could not read
     */
    private static Walked<Path> walkExport(Path root, String level, String prefix,
        WatchForCancel watch)
    {
        Walked<Path> walked = new Walked<>();
        walkInto(walked, root, "", level, prefix, watch); //$NON-NLS-1$
        return walked;
    }

    /**
     * Lists one directory of an export and walks its subdirectories.
     *
     * @param walked the walk being built.
     * @param directory the directory to list.
     * @param key the directory's path relative to the root, empty for the root itself.
     * @param level the file kinds the walk keeps.
     * @param prefix the directory prefix the walk is narrowed to, or {@code null} for all files.
     * @param watch the cancel watch of the call.
     */
    private static void walkInto(Walked<Path> walked, Path directory, String key, String level,
        String prefix, WatchForCancel watch)
    {
        if (watch.stopHere())
        {
            walked.stopped = true;
            return;
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory))
        {
            for (Path entry : stream)
            {
                entries.add(entry);
            }
        }
        catch (IOException | RuntimeException unreadable)
        {
            walked.notRead.add(new NotRead(key, notReadEntry(key, unreadable)));
            Activator.logWarning("compare_configurations: could not read the directory " + directory //$NON-NLS-1$
                + ": " + unreadable.getMessage()); //$NON-NLS-1$
            return;
        }
        // Listed in the order the file system returned and then sorted: the answer is built from
        // the union of both sides, and a stable order keeps two runs of the same comparison
        // comparable by eye.
        entries.sort(Comparator.comparing(entry -> entry.getFileName().toString()));
        for (Path entry : entries)
        {
            if (watch.stopHere())
            {
                walked.stopped = true;
                return;
            }
            String childKey = key.isEmpty() ? entry.getFileName().toString()
                : key + "/" + entry.getFileName(); //$NON-NLS-1$
            if (Files.isSymbolicLink(entry))
            {
                // A link is a path, not the file it points at. Reading through one compares a
                // file the export does not own, and the content behind it answers a question
                // about another tree.
                walked.notRead.add(new NotRead(childKey, childKey + " (symbolic link, not followed)")); //$NON-NLS-1$
                continue;
            }
            if (Files.isDirectory(entry))
            {
                if (worthWalking(childKey, prefix))
                {
                    walkInto(walked, entry, childKey, level, prefix, watch);
                }
                continue;
            }
            if (Files.isRegularFile(entry) && fileKindKept(level, childKey)
                && underPrefix(childKey, prefix))
            {
                walked.files.put(childKey, entry);
            }
        }
    }

    /**
     * Whether a directory can hold anything the narrowing asks about.
     *
     * @param directoryKey the directory's path relative to the export root.
     * @param prefix the directory prefix the walk is narrowed to, or {@code null} for all files.
     * @return whether to walk into the directory
     */
    private static boolean worthWalking(String directoryKey, String prefix)
    {
        if (prefix == null)
        {
            return true;
        }
        String candidate = directoryKey.startsWith("src/") ? directoryKey.substring(4) : directoryKey; //$NON-NLS-1$
        return prefix.equals(candidate) || candidate.startsWith(prefix + "/") //$NON-NLS-1$
            || prefix.startsWith(candidate + "/"); //$NON-NLS-1$
    }

    /**
     * The entry an answer carries for a directory whose listing failed.
     *
     * @param key the directory's path relative to the export root, empty for the root itself.
     * @param reason what the listing failed with.
     * @return the entry, naming the directory and the reason
     */
    private static String notReadEntry(String key, Throwable reason)
    {
        return (key.isEmpty() ? "." : key) + " (directory not read: " //$NON-NLS-1$ //$NON-NLS-2$
            + TextSuggest.safeMessage(reason) + ")"; //$NON-NLS-1$
    }

    /**
     * Whether a file of the walked export belongs to the requested level.
     *
     * @param level the level argument.
     * @param key the file path with forward separators.
     * @return whether the walk keeps the file
     */
    private static boolean fileKindKept(String level, String key)
    {
        if ("module".equalsIgnoreCase(level)) //$NON-NLS-1$
        {
            return key.endsWith(".bsl"); //$NON-NLS-1$
        }
        if ("template".equalsIgnoreCase(level)) //$NON-NLS-1$
        {
            return isTemplateFile(key);
        }
        return true;
    }

    /**
     * Whether two files hold the same bytes.
     *
     * @param first the first file.
     * @param second the second file.
     * @param watch the cancel watch of the call.
     * @return whether the two are the same byte for byte; {@code null} when the operator cancelled
     *         during the read
     * @throws IOException when either file cannot be read
     */
    private static Boolean sameBytes(Path first, Path second, WatchForCancel watch) throws IOException
    {
        if (Files.size(first) != Files.size(second))
        {
            return Boolean.FALSE;
        }
        try (InputStream a = Files.newInputStream(first); InputStream b = Files.newInputStream(second))
        {
            return sameStreams(a, b, watch);
        }
    }

    /**
     * Whether two streams hold the same bytes, read a block at a time.
     *
     * @param first the first stream.
     * @param second the second stream.
     * @param watch the cancel watch of the call, asked before each block.
     * @return whether the two are the same byte for byte; {@code null} when the operator cancelled
     *         before the streams were read to the end
     * @throws IOException when either stream fails
     */
    static Boolean sameStreams(InputStream first, InputStream second, WatchForCancel watch)
        throws IOException
    {
        byte[] left = new byte[COMPARE_BLOCK_BYTES];
        byte[] right = new byte[COMPARE_BLOCK_BYTES];
        while (true)
        {
            if (watch.raised())
            {
                return null;
            }
            int filledLeft = readFully(first, left);
            int filledRight = readFully(second, right);
            if (filledLeft != filledRight)
            {
                // One stream ended first. The block each one filled decides the rest.
                return false;
            }
            if (filledLeft == 0)
            {
                return true;
            }
            // Compared over the bytes read, not the whole block: a stream that returned less than
            // a block has left the rest of the array as the previous block's bytes, and the two
            // streams do not have to fill by the same amounts.
            if (!java.util.Arrays.equals(left, 0, filledLeft, right, 0, filledRight))
            {
                return false;
            }
        }
    }

    /**
     * Fills a block from a stream.
     *
     * @param stream the stream.
     * @param block the block to fill.
     * @return how many bytes were read; fewer than the block's length only at the end of the
     *         stream
     * @throws IOException when the stream fails
     */
    private static int readFully(InputStream stream, byte[] block) throws IOException
    {
        int filled = 0;
        while (filled < block.length)
        {
            int read = stream.read(block, filled, block.length - filled);
            if (read < 0)
            {
                break;
            }
            filled += read;
        }
        return filled;
    }

    /**
     * Classifies the union of two walks into the lists an answer carries.
     *
     * @param a the first side.
     * @param b the second side.
     * @param lists what to fill.
     * @param watch the cancel watch of the call.
     * @param comparison how one pair present on both sides is compared.
     * @param <T> what the walk kept: a workspace file, or a path on disk.
     */
    private static <T> void classify(Walked<T> a, Walked<T> b, DiffLists lists, WatchForCancel watch,
        Pairwise<T> comparison)
    {
        for (NotRead skipped : a.notRead)
        {
            lists.failed(skipped.entry());
        }
        for (NotRead skipped : b.notRead)
        {
            lists.failed(skipped.entry());
        }
        Set<String> all = new TreeSet<>();
        all.addAll(a.files.keySet());
        all.addAll(b.files.keySet());
        for (String key : all)
        {
            if (watch.stopHere())
            {
                return;
            }
            T first = a.files.get(key);
            T second = b.files.get(key);
            if (first == null)
            {
                // Only the second side has it. Whether the first has it too is not known when the
                // first side did not look there, and a file the other side could not look for is
                // not one it does not have.
                if (!a.notReadCovers(key))
                {
                    lists.added(key);
                }
                continue;
            }
            if (second == null)
            {
                if (!b.notReadCovers(key))
                {
                    lists.removed(key);
                }
                continue;
            }
            Pair pair = comparison.compare(key, first, second);
            if (pair.unread() != null)
            {
                lists.failed(key + " (not read: " + pair.unread() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            lists.compared++;
            if (pair.changed() != null)
            {
                lists.modified(pair.changed());
            }
        }
    }

    /**
     * How one pair of files present on both sides is compared.
     *
     * @param <T> what the walk kept.
     */
    private interface Pairwise<T>
    {
        /**
         * Compares one pair.
         *
         * @param key the path the pair is keyed by, for the answer.
         * @param first the first side's file.
         * @param second the second side's file.
         * @return the verdict.
         */
        Pair compare(String key, T first, T second);
    }

    /**
     * What comparing one pair of files came to.
     *
     * @param unread why the pair could not be compared; {@code null} when it could.
     * @param changed what the answer lists for a pair that differs: a path at the file levels, a
     *                map with the line counts and the preview at the module level; {@code null}
     *                when the two are the same.
     */
    private record Pair(String unread, Object changed)
    {
        /** Two files the comparison read and found the same. */
        static Pair same()
        {
            return new Pair(null, null);
        }

        /**
         * Two files the comparison could not read.
         *
         * @param reason what stopped the read, for the answer.
         * @return the verdict.
         */
        static Pair unread(String reason)
        {
            return new Pair(reason, null);
        }

        /**
         * Two files that differ.
         *
         * @param listed what the answer names for the pair.
         * @return the verdict.
         */
        static Pair changed(Object listed)
        {
            return new Pair(null, listed);
        }
    }

    /**
     * A path one side's walk did not read, and the entry that says so.
     *
     * @param key the path relative to the walk's root, empty for the root itself.
     * @param entry the text the answer carries.
     */
    private record NotRead(String key, String entry)
    {
    }

    /**
     * What one side's walk found, and what it could not look at.
     *
     * @param <T> what the walk kept: a workspace file, or a path on disk.
     */
    private static final class Walked<T>
    {
        private final Map<String, T> files = new LinkedHashMap<>();

        private final List<NotRead> notRead = new ArrayList<>();

        /** Whether the walk was cut short by the operator rather than finished. */
        private boolean stopped;

        /**
         * Whether this walk did not look at a path: the path itself was not read, or it lies under
         * a directory that was not.
         *
         * @param key the path an entry is being judged for.
         * @return whether the path is covered by something this walk did not read
         */
        private boolean notReadCovers(String key)
        {
            for (NotRead skipped : notRead)
            {
                if (skipped.key().isEmpty() || key.equals(skipped.key())
                    || key.startsWith(skipped.key() + "/")) //$NON-NLS-1$
                {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Leaves a workspace walk the operator has cancelled.
     * <p>
     * The visitor contract of {@code IResource.accept} has no way to end a walk early: it hands
     * back a boolean, and a {@code false} means the subtree is skipped rather than the walk ended.
     * Leaving through an exception is the only way out, and this one is caught where the walk was
     * started, so it never reaches a caller.
     * </p>
     */
    private static final class WalkStopped extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
    }

    /**
     * The four lists a file-level answer carries, with a ceiling on what they name.
     * <p>
     * The counts are of everything the comparison found; the lists stop at
     * {@link #MAX_REPORTED_ENTRIES} entries between them and {@code truncated} says they were cut.
     * Counting what is not listed is the point: an answer that listed a thousand entries and
     * counted a thousand of them would read as a comparison that covered exactly that much.
     * </p>
     */
    private static final class DiffLists
    {
        private final List<String> added = new ArrayList<>();

        private final List<String> removed = new ArrayList<>();

        private final List<Object> modified = new ArrayList<>();

        private final List<String> failed = new ArrayList<>();

        private int addedCount;

        private int removedCount;

        private int modifiedCount;

        private int failedCount;

        private int compared;

        private boolean truncated;

        /**
         * Names one path as added.
         *
         * @param key the path.
         */
        private void added(String key)
        {
            addedCount++;
            if (room())
            {
                added.add(key);
            }
        }

        /**
         * Names one path as removed.
         *
         * @param key the path.
         */
        private void removed(String key)
        {
            removedCount++;
            if (room())
            {
                removed.add(key);
            }
        }

        /**
         * Names one pair as different.
         *
         * @param entry what the answer lists for the pair.
         */
        private void modified(Object entry)
        {
            modifiedCount++;
            if (room())
            {
                modified.add(entry);
            }
        }

        /**
         * Names one path the comparison could not read.
         *
         * @param entry the text the answer carries, with the reason.
         */
        private void failed(String entry)
        {
            failedCount++;
            if (room())
            {
                failed.add(entry);
            }
        }

        /**
         * Whether the lists still have room for an entry, recording that they did not when they
         * have not.
         *
         * @return whether the entry may be listed
         */
        private boolean room()
        {
            int named = added.size() + removed.size() + modified.size() + failed.size();
            if (named >= MAX_REPORTED_ENTRIES)
            {
                truncated = true;
                return false;
            }
            return true;
        }

        /**
         * Puts the lists, their counts and what the comparison covered into an answer.
         *
         * @param diff the answer being built.
         */
        private void into(Map<String, Object> diff)
        {
            diff.put("added", added); //$NON-NLS-1$
            diff.put("removed", removed); //$NON-NLS-1$
            diff.put("modified", modified); //$NON-NLS-1$
            diff.put("failed", failed); //$NON-NLS-1$
            diff.put("addedCount", addedCount); //$NON-NLS-1$
            diff.put("removedCount", removedCount); //$NON-NLS-1$
            diff.put("modifiedCount", modifiedCount); //$NON-NLS-1$
            diff.put("failedCount", failedCount); //$NON-NLS-1$
            diff.put("comparedCount", compared); //$NON-NLS-1$
            diff.put("truncated", truncated); //$NON-NLS-1$
        }
    }

    /**
     * Formats an answer that no watch cut short.
     *
     * @param level the level compared.
     * @param format json or markdown.
     * @param diff the body of the answer.
     * @return the answer.
     */
    private String formatResult(String level, String format, Map<String, Object> diff)
    {
        return formatResult(level, format, diff, null);
    }

    /**
     * Formats a comparison answer, with the note a cut-short scan carries.
     *
     * @param level the level compared.
     * @param format json or markdown.
     * @param diff the body of the answer.
     * @param cancelled what the answer says about a comparison the operator stopped, or
     *                  {@code null} when it ran to the end.
     * @return the answer.
     */
    private String formatResult(String level, String format, Map<String, Object> diff,
        String cancelled)
    {
        if ("markdown".equalsIgnoreCase(format)) //$NON-NLS-1$
        {
            ToolResult md = ToolResult.success()
                .put("level", level) //$NON-NLS-1$
                .put("format", "markdown") //$NON-NLS-1$ //$NON-NLS-2$
                .put("text", renderMarkdown(level, diff)); //$NON-NLS-1$
            if (cancelled != null)
            {
                md.put("cancelled", cancelled); //$NON-NLS-1$
            }
            return md.toJson();
        }
        ToolResult tr = ToolResult.success().put("level", level); //$NON-NLS-1$
        for (Map.Entry<String, Object> entry : diff.entrySet())
        {
            tr.put(entry.getKey(), entry.getValue());
        }
        if (cancelled != null)
        {
            tr.put("cancelled", cancelled); //$NON-NLS-1$
        }
        return tr.toJson();
    }

    private static String renderMarkdown(String level, Map<String, Object> diff)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# Compare configurations - level=").append(level).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (Map.Entry<String, Object> entry : diff.entrySet())
        {
            Object value = entry.getValue();
            if (value instanceof java.util.List)
            {
                java.util.List<?> list = (java.util.List<?>) value;
                sb.append("## ").append(entry.getKey()).append(" (").append(list.size()).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                for (Object item : list)
                {
                    sb.append("- ").append(item).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                sb.append("\n"); //$NON-NLS-1$
            }
            else
            {
                sb.append("**").append(entry.getKey()).append(":** ").append(value).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        return sb.toString();
    }

    private static String orDefault(String value, String fallback)
    {
        return value != null && !value.isEmpty() ? value : fallback;
    }
}
