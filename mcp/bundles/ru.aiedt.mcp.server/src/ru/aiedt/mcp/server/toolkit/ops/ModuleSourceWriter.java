/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Path;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.BmExportHelper;
import ru.aiedt.mcp.server.support.LineDelimiters;
import ru.aiedt.mcp.server.support.FileMarkers;
import ru.aiedt.mcp.server.support.YamlFrontMatter;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ProjectResolver;
import ru.aiedt.mcp.server.support.TextSuggest;

/**
 * Writes BSL source into an EDT module file.
 * <p>
 * Eight write modes cover the common edits an agent makes: wholesale replacement, append,
 * search-and-replace of a fragment, line-range replacement, single-method replacement, atomic
 * multi-method replacement, and insert before/after a given line. A structural balance check
 * ({@link BslSyntaxValidator}) runs before anything reaches disk, so a module with a missing
 * {@code EndIf} or a stray {@code EndProcedure} is blocked at the door. An optional EDT validation
 * pass folds the freshly-published markers into the response, so a single call answers both "did it
 * land" and "is it clean".
 * </p>
 * <p>
 * Two guards keep an agent from shooting itself in the foot. {@code expectedText} makes
 * {@code replaceLines} refuse the edit when the file shifted under it, instead of silently
 * overwriting the wrong lines; and a {@code >50% shrink} check (bypassed with
 * {@code confirmFullReplace}) catches a search-replace that matched the wrong region, or a
 * {@code replace} handed a tiny stub for a 400-line module. Both are non-destructive: the file on
 * disk is untouched when they fire.
 * </p>
 */
public class ModuleSourceWriter implements IMcpTool
{
    public static final String NAME = "write_module_source"; //$NON-NLS-1$

    // --- mode literals ---
    /** Wholesale replacement of the whole module. */
    public static final String MODE_REPLACE = "replace"; //$NON-NLS-1$
    /** Append at end of file (also creates a new file). */
    public static final String MODE_APPEND = "append"; //$NON-NLS-1$
    /** Find {@code oldSource}, replace with {@code source}. The default when mode is absent. */
    public static final String MODE_SEARCH_REPLACE = "searchReplace"; //$NON-NLS-1$
    /** Replace an inclusive 1-based line range. */
    public static final String MODE_REPLACE_LINES = "replaceLines"; //$NON-NLS-1$
    /** Replace one method (looked up by name) end-to-end. */
    public static final String MODE_REPLACE_METHOD = "replaceMethod"; //$NON-NLS-1$
    /** Atomic replace of several methods at once; all-or-nothing. */
    public static final String MODE_REPLACE_METHODS = "replaceMethods"; //$NON-NLS-1$
    /** Insert {@code source} before line N. */
    public static final String MODE_INSERT_BEFORE = "insertBefore"; //$NON-NLS-1$
    /** Insert {@code source} after line N. */
    public static final String MODE_INSERT_AFTER = "insertAfter"; //$NON-NLS-1$

    /** Hard cap on {@code source} length. */
    private static final int MAX_SOURCE_LENGTH = 500_000;

    /** The UTF-8 BOM, prepended on write when the file is BOM-bearing (or new). */
    private static final byte[] UTF8_BOM = { (byte)0xEF, (byte)0xBB, (byte)0xBF };

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Writes BSL source into a 1C metadata object module. " //$NON-NLS-1$
            + "Eight modes: searchReplace (locate oldSource in the file and swap in source; the default), " //$NON-NLS-1$
            + "replace (overwrite the whole module), append (add text at the end), " //$NON-NLS-1$
            + "replaceLines (overwrite a range of lines), replaceMethod (swap out one method found by name), " //$NON-NLS-1$
            + "replaceMethods (atomic multi-method swap via methods[]; every target must be found or nothing is written), " //$NON-NLS-1$
            + "insertBefore (place source ahead of line N), insertAfter (place source right after line N). " //$NON-NLS-1$
            + "Identify the target via modulePath, or via objectName plus moduleType. " //$NON-NLS-1$
            + "Before the write, a BSL syntax pass verifies that Procedure/EndProcedure, " //$NON-NLS-1$
            + "Function/EndFunction, If/EndIf and similar pairs stay balanced - " //$NON-NLS-1$
            + "any mismatch blocks the write. Pass skipSyntaxCheck=true to override it. " //$NON-NLS-1$
            + "Set dryRun=true to preview the outcome without writing anything."; //$NON-NLS-1$
    }

    @Override
    public IMcpTool.ResponseType getResponseType()
    {
        return IMcpTool.ResponseType.MARKDOWN;
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", "Name of the EDT project to work in", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path to the module, measured from the src/ folder - for instance 'Documents/MyDoc/ObjectModule.bsl' " //$NON-NLS-1$
                    + "or 'CommonModules/MyModule/Module.bsl'. A leading 'src/' is fine too and gets stripped " //$NON-NLS-1$
                    + "automatically, so 'src/CommonModules/MyModule/Module.bsl' also works. " //$NON-NLS-1$
                    + "Or skip this and supply objectName + moduleType instead.") //$NON-NLS-1$
            .stringProperty("objectName", //$NON-NLS-1$
                "The object's fully-qualified name, e.g. 'Document.MyDoc' or 'DataProcessor.MyProcessor'. " //$NON-NLS-1$
                    + "Metadata names in Russian work too (e.g. 'Документ.МойДок'). " //$NON-NLS-1$
                    + "An alternative to modulePath.") //$NON-NLS-1$
            .stringProperty("moduleType", //$NON-NLS-1$
                "Which kind of module (paired with objectName): ObjectModule (default), ManagerModule, " //$NON-NLS-1$
                    + "FormModule, CommandModule, RecordSetModule.") //$NON-NLS-1$
            .stringProperty("source", //$NON-NLS-1$
                "The BSL text to write (aliases accepted: content, sourceCode, code, text). Needed " //$NON-NLS-1$
                    + "for every mode EXCEPT replaceMethods (there, each method brings its own source " //$NON-NLS-1$
                    + "inside the methods[] array). For replace: the module's entire new content. For " //$NON-NLS-1$
                    + "searchReplace: what to put in place of oldSource. For append: the text being added. For " //$NON-NLS-1$
                    + "replaceMethod/replaceMethods: the full method, start to finish (Procedure/Function ... " //$NON-NLS-1$
                    + "EndProcedure/EndFunction). If source opens with a // header comment, " //$NON-NLS-1$
                    + "it takes the place of the method's existing header comment (never leaving a duplicate); when " //$NON-NLS-1$
                    + "there is no leading comment, whatever header was already there is left alone. To drop an " //$NON-NLS-1$
                    + "existing header comment without putting in a new one, use searchReplace instead.") //$NON-NLS-1$
            .stringProperty("oldSource", //$NON-NLS-1$
                "The fragment to find and swap out (required for searchReplace mode). It has to " //$NON-NLS-1$
                    + "match exactly one spot in the file - which is also proof you actually read the current " //$NON-NLS-1$
                    + "file contents.") //$NON-NLS-1$
            .stringProperty("mode", //$NON-NLS-1$
                "Picks the write mode: 'searchReplace' (find oldSource and swap in source; the default), " //$NON-NLS-1$
                    + "'replace' (overwrite the entire file), 'append' (add text at the end), 'replaceLines' " //$NON-NLS-1$
                    + "(overwrite a range of lines), 'replaceMethod' (swap out one named method end-to-end), " //$NON-NLS-1$
                    + "'replaceMethods' (atomic multi-method swap - see methods[]; every target " //$NON-NLS-1$
                    + "must be found, or nothing gets written), 'insertBefore' (drop source ahead of line N), " //$NON-NLS-1$
                    + "'insertAfter' (drop source right after line N).") //$NON-NLS-1$
            .stringProperty("formName", //$NON-NLS-1$
                "The form's name; required when moduleType=FormModule (e.g. 'ItemForm').") //$NON-NLS-1$
            .stringProperty("commandName", //$NON-NLS-1$
                "The command's name; required when moduleType=CommandModule (e.g. 'FillByTemplate').") //$NON-NLS-1$
            .integerProperty("lineFrom", //$NON-NLS-1$
                "Starting line number for replaceLines mode (1-based, inclusive)") //$NON-NLS-1$
            .integerProperty("lineTo", //$NON-NLS-1$
                "Ending line number for replaceLines mode (1-based, inclusive)") //$NON-NLS-1$
            .stringProperty("expectedText", //$NON-NLS-1$
                "An optional safety net for replaceLines: paste in what you believe currently " //$NON-NLS-1$
                    + "sits on lines lineFrom..lineTo. If it does not match (the module " //$NON-NLS-1$
                    + "changed since you last read it, so the line numbers are stale), the edit gets " //$NON-NLS-1$
                    + "REJECTED and you get an expected-vs-actual diff back instead of a silent overwrite of " //$NON-NLS-1$
                    + "the wrong lines. Strongly recommended - echo back the lines you plan to " //$NON-NLS-1$
                    + "replace.") //$NON-NLS-1$
            .stringProperty("methodName", //$NON-NLS-1$
                "The method's name for replaceMethod mode (looked up as a Procedure/Function by that name). The " //$NON-NLS-1$
                    + "span being replaced also picks up any preceding &-directives, plus the old // header " //$NON-NLS-1$
                    + "comment when the new source itself opens with one.") //$NON-NLS-1$
            .stringProperty("methods", //$NON-NLS-1$
                "In replaceMethods mode, supply a JSON array of {methodName, source} objects, for instance " //$NON-NLS-1$
                    + "[{\"methodName\":\"ПриСоздании\"," //$NON-NLS-1$
                    + "\"source\":\"Процедура " //$NON-NLS-1$
                    + "ПриСоздании()\\n...\\n" //$NON-NLS-1$
                    + "КонецПроцедуры\"}, " //$NON-NLS-1$
                    + "{\"methodName\":\"ПриЗаписи\"," //$NON-NLS-1$
                    + "\"source\":\"...\"}]. Every referenced method has to already exist, or the call writes nothing " //$NON-NLS-1$
                    + "(all-or-nothing). Per entry, 'name' works as an alias for methodName, and content/sourceCode/code/text " //$NON-NLS-1$
                    + "are all accepted as aliases for source.") //$NON-NLS-1$
            .integerProperty("line", //$NON-NLS-1$
                "1-based line number for insertBefore/insertAfter. insertBefore: source is placed " //$NON-NLS-1$
                    + "right before line N (the old line N moves down to N+sourceLines). insertAfter: " //$NON-NLS-1$
                    + "source is placed right after line N (the old line N+1 moves further down).") //$NON-NLS-1$
            .booleanProperty("dryRun", //$NON-NLS-1$
                "Shows what the write would produce without touching the file. Reports diff stats " //$NON-NLS-1$
                    + "(linesBefore, linesAfter, removedLines, addedLines)") //$NON-NLS-1$
            .booleanProperty("skipSyntaxCheck", //$NON-NLS-1$
                "Bypasses the BSL syntax check (default: false). At the default, it confirms balanced " //$NON-NLS-1$
                    + "Procedure/EndProcedure, Function/EndFunction, If/EndIf, While/EndDo, " //$NON-NLS-1$
                    + "For/EndDo, Try/EndTry pairs. Set true to push the write through regardless.") //$NON-NLS-1$
            .booleanProperty("validateAfterWrite", //$NON-NLS-1$
                "Runs EDT validation right after a successful write and folds the outcome into the response " //$NON-NLS-1$
                    + "(default: true). Adds a 'validation' section with errors / warnings / " //$NON-NLS-1$
                    + "codeStyle counts, plus a short hint. Pass false while writing in a batch and call " //$NON-NLS-1$
                    + "get_project_errors once at the very end instead.") //$NON-NLS-1$
            .booleanProperty("confirmFullReplace", //$NON-NLS-1$
                "Has to be true when a write would strip away more than 50% of an existing module's lines. " //$NON-NLS-1$
                    + "Prevents accidentally wiping out hundreds of lines when the goal was only to touch " //$NON-NLS-1$
                    + "one method or fragment. Removals above 30% still proceed but come back with a non-blocking " //$NON-NLS-1$
                    + "'protection' warning. Applies to every mode that can remove lines - searchReplace, " //$NON-NLS-1$
                    + "replaceLines, replaceMethod, replaceMethods and replace (a replace that strips more " //$NON-NLS-1$
                    + "than half of the existing module still has to be confirmed, so an accidental stub " //$NON-NLS-1$
                    + "replacement cannot silently wipe a large module); the additive modes (append, " //$NON-NLS-1$
                    + "insertBefore, insertAfter) only add lines and are never gated.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        if (modulePath == null || modulePath.isEmpty())
            return "write-module-source.md"; //$NON-NLS-1$
        String safeName = modulePath.replace("/", "-").replace("\\", "-").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return "write-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // --- step 1: extract parameters ---
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String objectName = JsonUtils.extractStringArgument(params, "objectName"); //$NON-NLS-1$
        String moduleType = JsonUtils.extractStringArgument(params, "moduleType"); //$NON-NLS-1$
        String source = extractSource(params);
        String oldSource = JsonUtils.extractStringArgument(params, "oldSource"); //$NON-NLS-1$
        String mode = JsonUtils.extractStringArgument(params, "mode"); //$NON-NLS-1$
        String formName = JsonUtils.extractStringArgument(params, "formName"); //$NON-NLS-1$
        String commandName = JsonUtils.extractStringArgument(params, "commandName"); //$NON-NLS-1$
        int lineFrom = JsonUtils.extractIntArgument(params, "lineFrom", -1); //$NON-NLS-1$
        int lineTo = JsonUtils.extractIntArgument(params, "lineTo", -1); //$NON-NLS-1$
        String expectedText = JsonUtils.extractStringArgument(params, "expectedText"); //$NON-NLS-1$
        String methodName = JsonUtils.extractStringArgument(params, "methodName"); //$NON-NLS-1$
        String methodsJson = JsonUtils.extractStringArgument(params, "methods"); //$NON-NLS-1$
        int line = JsonUtils.extractIntArgument(params, "line", -1); //$NON-NLS-1$
        boolean dryRun = JsonUtils.extractBooleanArgument(params, "dryRun", false); //$NON-NLS-1$
        boolean skipSyntaxCheck = JsonUtils.extractBooleanArgument(params, "skipSyntaxCheck", false); //$NON-NLS-1$
        boolean validateAfterWrite = JsonUtils.extractBooleanArgument(params, "validateAfterWrite", true); //$NON-NLS-1$
        boolean confirmFullReplace = JsonUtils.extractBooleanArgument(params, "confirmFullReplace", false); //$NON-NLS-1$

        // --- step 2: validate required parameters ---
        if (projectName == null || projectName.isEmpty())
            return "Error: projectName must be supplied"; //$NON-NLS-1$
        if (source == null && !MODE_REPLACE_METHODS.equals(mode))
            return "Error: source must be supplied - put the module text in the 'source' field " //$NON-NLS-1$
                + "(aliases also accepted: content, sourceCode, code, text). Keys received: [" //$NON-NLS-1$
                + String.join(", ", params.keySet()) + "]."; //$NON-NLS-1$ //$NON-NLS-2$
        if (source != null && source.length() > MAX_SOURCE_LENGTH)
            return "Error: source is longer than the maximum allowed (" + MAX_SOURCE_LENGTH //$NON-NLS-1$
                + " characters)"; //$NON-NLS-1$

        // --- step 3: default + validate mode ---
        if (mode == null || mode.isEmpty())
            mode = MODE_SEARCH_REPLACE;
        if (!isValidMode(mode))
        {
            return "Error: " + TextSuggest.invalidValue("mode", mode, //$NON-NLS-1$ //$NON-NLS-2$
                Arrays.asList(MODE_SEARCH_REPLACE, MODE_REPLACE, MODE_APPEND, MODE_REPLACE_LINES,
                    MODE_REPLACE_METHOD, MODE_REPLACE_METHODS, MODE_INSERT_BEFORE,
                    MODE_INSERT_AFTER));
        }
        if (MODE_SEARCH_REPLACE.equals(mode) && (oldSource == null || oldSource.isEmpty()))
            return "Error: oldSource must be supplied for searchReplace mode"; //$NON-NLS-1$

        // --- step 4: resolve modulePath ---
        if (modulePath == null || modulePath.isEmpty())
        {
            if (objectName == null || objectName.isEmpty())
            {
                return "Error: " + TextSuggest.missingParam("modulePath or objectName - one of the two", //$NON-NLS-1$ //$NON-NLS-2$
                    "modulePath='CommonModules/MyModule/Module.bsl' or objectName='Document.MyDoc'"); //$NON-NLS-1$
            }
            String resolved = resolveModulePath(objectName, moduleType, formName, commandName);
            if (resolved.startsWith("Error:")) //$NON-NLS-1$
                return resolved;
            modulePath = resolved;
        }
        // src/ prefix strip (agents often paste the workspace-relative path)
        if (modulePath.regionMatches(true, 0, "src/", 0, 4) //$NON-NLS-1$
            || modulePath.regionMatches(true, 0, "src\\", 0, 4)) //$NON-NLS-1$
        {
            modulePath = modulePath.substring(4);
        }
        if (modulePath.contains("..")) //$NON-NLS-1$
            return "Error: modulePath is not allowed to contain '..'"; //$NON-NLS-1$
        if (!modulePath.endsWith(".bsl")) //$NON-NLS-1$
        {
            // Saying what is not taken leaves the caller guessing the shape. An FQN is what they
            // reach for, so name the path that FQN corresponds to.
            return "Error: modulePath is a path to a .bsl file inside the project, such as " //$NON-NLS-1$
                + "CommonModules/<name>/Module.bsl or Catalogs/<name>/ObjectModule.bsl. " //$NON-NLS-1$
                + "A leading src/ is accepted and stripped. An FQN is not a path: got '" //$NON-NLS-1$
                + modulePath + "'."; //$NON-NLS-1$
        }

        // --- step 5: resolve project ---
        IProject project = ProjectResolver.resolve(projectName);
        if (project == null)
            return "Error: " + ProjectResolver.describeNotFound(projectName); //$NON-NLS-1$

        // --- step 6: resolve file + existence rules ---
        IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
        boolean fileExists = file.exists();
        if (!fileExists && !MODE_REPLACE.equals(mode) && !MODE_APPEND.equals(mode))
        {
            return "Error: no module file exists at src/" + modulePath + ". Only the 'replace' and 'append' " //$NON-NLS-1$ //$NON-NLS-2$
                + "modes may create a new module file (appending to a module that does not exist yet writes " //$NON-NLS-1$
                + "the source in as its entire content)."; //$NON-NLS-1$
        }

        // -- outer try: steps 7-17 --
        try
        {
            // --- step 7: normalise source line endings ---
            if (source != null)
                source = source.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$

            // --- step 8: read current content + BOM ---
            List<String> originalLines;
            boolean hasBom;
            if (fileExists)
            {
                originalLines = BslModuleAccess.readFileLines(file);
                hasBom = detectBom(file);
            }
            else
            {
                originalLines = new ArrayList<>();
                hasBom = true;
            }
            int totalOriginal = originalLines.size();

            // --- step 9: compute new content based on mode ---
            List<String> newLines;
            switch (mode)
            {
                case MODE_REPLACE:
                    newLines = splitSourceLines(source);
                    break;
                case MODE_APPEND:
                    newLines = new ArrayList<>(originalLines);
                    newLines.addAll(splitSourceLines(source));
                    break;
                case MODE_SEARCH_REPLACE:
                {
                    oldSource = oldSource.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
                    String currentContent = String.join("\n", originalLines); //$NON-NLS-1$
                    int idx = currentContent.indexOf(oldSource);
                    if (idx < 0)
                    {
                        return "Error: oldSource was not found in the file's current content. Either the file " //$NON-NLS-1$
                            + "changed since your last read, or the oldSource text is not an exact " //$NON-NLS-1$
                            + "match. Re-read the file with read_module_source and try again."; //$NON-NLS-1$
                    }
                    int secondIdx = currentContent.indexOf(oldSource, idx + 1);
                    if (secondIdx >= 0)
                    {
                        return "Error: oldSource matches more than one spot in the file (" //$NON-NLS-1$
                            + countOccurrences(currentContent, oldSource)
                            + " occurrences). Provide a longer, more specific oldSource fragment " //$NON-NLS-1$
                            + "that pins down a single location."; //$NON-NLS-1$
                    }
                    String newContent = currentContent.substring(0, idx) + source
                        + currentContent.substring(idx + oldSource.length());
                    newLines = splitSourceLines(newContent);
                    break;
                }
                case MODE_REPLACE_LINES:
                {
                    if (lineFrom < 1 || lineTo < lineFrom)
                    {
                        return "Error: bad line range: lineFrom=" + lineFrom + ", lineTo=" //$NON-NLS-1$ //$NON-NLS-2$
                            + lineTo + ". lineFrom has to be at least 1, and lineTo has to be at least lineFrom"; //$NON-NLS-1$
                    }
                    if (lineTo > totalOriginal)
                        return "Error: lineTo (" + lineTo + ") falls past the end of the file (" //$NON-NLS-1$ //$NON-NLS-2$
                            + totalOriginal + " lines)"; //$NON-NLS-1$
                    if (expectedText != null && !expectedText.isEmpty())
                    {
                        String actualBlock = String.join("\n", //$NON-NLS-1$
                            originalLines.subList(lineFrom - 1, lineTo));
                        String expectedBlock = normalizeForCompare(expectedText);
                        if (!actualBlock.equals(expectedBlock))
                            return lineDriftError(lineFrom, lineTo, expectedBlock, actualBlock);
                    }
                    newLines = new ArrayList<>();
                    newLines.addAll(originalLines.subList(0, lineFrom - 1));
                    newLines.addAll(splitSourceLines(source));
                    if (lineTo < totalOriginal)
                        newLines.addAll(originalLines.subList(lineTo, totalOriginal));
                    break;
                }
                case MODE_INSERT_BEFORE:
                {
                    if (line < 1)
                        return "Error: line must be supplied for insertBefore mode and be at least 1 " //$NON-NLS-1$
                            + "(1-based)"; //$NON-NLS-1$
                    if (line > totalOriginal)
                        return "Error: line (" + line + ") is past the end of the file (" + totalOriginal //$NON-NLS-1$ //$NON-NLS-2$
                            + " lines). Use 'append' mode instead to add at the end."; //$NON-NLS-1$
                    newLines = new ArrayList<>();
                    newLines.addAll(originalLines.subList(0, line - 1));
                    newLines.addAll(splitSourceLines(source));
                    newLines.addAll(originalLines.subList(line - 1, totalOriginal));
                    break;
                }
                case MODE_INSERT_AFTER:
                {
                    if (line < 1)
                        return "Error: line must be supplied for insertAfter mode and be at least 1 " //$NON-NLS-1$
                            + "(1-based)"; //$NON-NLS-1$
                    if (line > totalOriginal)
                        return "Error: line (" + line + ") is past the end of the file (" + totalOriginal //$NON-NLS-1$ //$NON-NLS-2$
                            + " lines). Use 'append' mode instead to add at the end."; //$NON-NLS-1$
                    newLines = new ArrayList<>();
                    newLines.addAll(originalLines.subList(0, line));
                    newLines.addAll(splitSourceLines(source));
                    if (line < totalOriginal)
                        newLines.addAll(originalLines.subList(line, totalOriginal));
                    break;
                }
                case MODE_REPLACE_METHOD:
                {
                    if (methodName == null || methodName.isEmpty())
                        return "Error: methodName must be supplied for replaceMethod mode"; //$NON-NLS-1$
                    int methodStart = -1;
                    for (int i = 0; i < totalOriginal; i++)
                    {
                        Matcher m = BslModuleAccess.METHOD_START_PATTERN.matcher(originalLines.get(i));
                        if (m.find() && m.group(1) != null
                            && m.group(1).equalsIgnoreCase(methodName))
                        {
                            methodStart = i;
                            break;
                        }
                    }
                    if (methodStart < 0)
                        return "Error: the module has no method named '" + methodName + "'"; //$NON-NLS-1$ //$NON-NLS-2$
                    // walk back over & compile pragmas only (&НаКлиенте / &НаСервере).
                    // A # line (region #Область/#КонецОбласти or preprocessor #Если/#Тогда)
                    // is structural, not method-attached - grabbing it breaks the region /
                    // preprocessor balance when the method is first in such a block (row 41).
                    int directiveStart = methodStart;
                    for (int k = methodStart - 1; k >= 0; k--)
                    {
                        String prevLine = originalLines.get(k).trim();
                        if (prevLine.startsWith("&")) //$NON-NLS-1$
                            directiveStart = k;
                        else if (prevLine.isEmpty())
                            continue;
                        else
                            break;
                    }
                    methodStart = directiveStart;
                    if (firstNonBlankIsComment(source))
                        methodStart = includeLeadingCommentBlock(originalLines, methodStart);
                    int methodEnd = -1;
                    for (int i = methodStart + 1; i < totalOriginal; i++)
                    {
                        if (BslModuleAccess.METHOD_END_PATTERN.matcher(originalLines.get(i)).find())
                        {
                            methodEnd = i;
                            break;
                        }
                    }
                    if (methodEnd < 0)
                        return "Error: unable to find the EndProcedure/EndFunction that closes method '" //$NON-NLS-1$
                            + methodName + "'"; //$NON-NLS-1$
                    newLines = new ArrayList<>();
                    newLines.addAll(originalLines.subList(0, methodStart));
                    newLines.addAll(splitSourceLines(source));
                    if (methodEnd + 1 < totalOriginal)
                        newLines.addAll(originalLines.subList(methodEnd + 1, totalOriginal));
                    break;
                }
                case MODE_REPLACE_METHODS:
                {
                    ReplaceMethodsResult result = applyReplaceMethods(originalLines, methodsJson);
                    if (result.error != null)
                        return result.error;
                    newLines = result.newLines;
                    break;
                }
                default:
                    return "Error: unsupported mode: " + mode; //$NON-NLS-1$
            }

            // --- step 10: protection: >30% warn, >50% hard-stop ---
            String protectionWarning = null;
            if (totalOriginal > 10)
            {
                int removed = totalOriginal - newLines.size();
                if (removed > 0)
                {
                    int removalPercent = (int)Math.round(100.0 * removed / totalOriginal);
                    if (removalPercent > 50 && !confirmFullReplace)
                    {
                        return "Error: this change would strip out " + removalPercent //$NON-NLS-1$
                            + "% of the module (" + removed + " of " + totalOriginal //$NON-NLS-1$ //$NON-NLS-2$
                            + " lines). Pass confirmFullReplace=true to proceed, or choose a more " //$NON-NLS-1$
                            + "narrowly targeted mode (replaceMethod, replaceLines, searchReplace) to " //$NON-NLS-1$
                            + "touch a smaller fragment."; //$NON-NLS-1$
                    }
                    if (removalPercent > 30)
                    {
                        protectionWarning = "WARNING: this edit strips " + removalPercent //$NON-NLS-1$
                            + "% of the module (from " + totalOriginal + " to " //$NON-NLS-1$ //$NON-NLS-2$
                            + newLines.size() + " lines)"; //$NON-NLS-1$
                    }
                }
            }

            // --- step 11: dryRun preview ---
            if (dryRun)
            {
                YamlFrontMatter dryFm = YamlFrontMatter.create()
                    .put("tool", NAME) //$NON-NLS-1$
                    .put("projectName", projectName) //$NON-NLS-1$
                    .put("modulePath", modulePath) //$NON-NLS-1$
                    .put("mode", mode) //$NON-NLS-1$
                    .put("status", "preview") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("dryRun", true) //$NON-NLS-1$
                    .put("linesBefore", totalOriginal) //$NON-NLS-1$
                    .put("linesAfter", newLines.size()) //$NON-NLS-1$
                    .put("lineDelta", newLines.size() - totalOriginal); //$NON-NLS-1$
                if (protectionWarning != null)
                    dryFm.put("protection", protectionWarning); //$NON-NLS-1$
                StringBuilder preview = new StringBuilder();
                preview.append("## Preview (Dry Run)\n\n"); //$NON-NLS-1$
                if (protectionWarning != null)
                    preview.append("**").append(protectionWarning).append("**\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                preview.append("Lines: ").append(totalOriginal).append(" -> ") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(newLines.size()).append("\n\n"); //$NON-NLS-1$
                return dryFm.wrapContent(preview.toString());
            }

            // --- step 12: BSL syntax check (pre-write) ---
            if (!skipSyntaxCheck)
            {
                BslSyntaxValidator.CheckResult checkResult = BslSyntaxValidator.check(newLines);
                if (!checkResult.isValid())
                {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Error: the BSL syntax check failed. The write was blocked.\n\n"); //$NON-NLS-1$
                    sb.append("**Syntax errors:**\n"); //$NON-NLS-1$
                    for (String error : checkResult.getErrors())
                        sb.append("- ").append(error).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                    sb.append("\nPass skipSyntaxCheck=true to force the write through anyway."); //$NON-NLS-1$
                    return sb.toString();
                }
            }

            // --- step 13: write file ---
            writeFile(file, newLines, hasBom, fileExists);

            // --- step 14: persistence sync (BM flush) ---
            String moduleFqn = resolveFqnForValidation(objectName, modulePath);
            PersistenceResult persistence = forceExportModule(project, moduleFqn);

            // --- step 15: optional EDT validation ---
            FileMarkers.Grouped validation = null;
            if (validateAfterWrite)
                validation = collectValidation(project, objectName, modulePath);

            // --- step 16: duplicate-method detection ---
            List<String> duplicateMethods = findDuplicateMethods(newLines);

            // --- step 17: success response ---
            YamlFrontMatter fm = YamlFrontMatter.create()
                .put("tool", NAME) //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("modulePath", modulePath) //$NON-NLS-1$
                .put("mode", mode) //$NON-NLS-1$
                .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
                .put("linesAfter", newLines.size()) //$NON-NLS-1$
                .put("syntaxCheck", skipSyntaxCheck ? "skipped" : "passed"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            if (fileExists)
                fm.put("linesBefore", totalOriginal); //$NON-NLS-1$
            else
                fm.put("newFile", true); //$NON-NLS-1$

            if (protectionWarning != null)
                fm.put("protection", protectionWarning); //$NON-NLS-1$

            if (validation != null)
            {
                fm.put("validationErrors", validation.errorCount()); //$NON-NLS-1$
                fm.put("validationWarnings", validation.warningCount()); //$NON-NLS-1$
                fm.put("validationCodeStyle", validation.codeStyleCount()); //$NON-NLS-1$
                fm.put("validationHint", buildValidationHint(validation)); //$NON-NLS-1$
            }

            if (!duplicateMethods.isEmpty())
                fm.put("duplicateMethods", String.join(", ", duplicateMethods)); //$NON-NLS-1$ //$NON-NLS-2$

            fm.put("persistenceSyncOk", persistence.ok); //$NON-NLS-1$
            fm.put("persistenceSyncMs", persistence.elapsedMs); //$NON-NLS-1$
            if (persistence.detail != null && !persistence.detail.isEmpty())
                fm.put("persistenceSyncDetail", persistence.detail); //$NON-NLS-1$

            StringBuilder body = new StringBuilder("Write finished successfully"); //$NON-NLS-1$
            if (protectionWarning != null)
                body.append("\n\n**").append(protectionWarning).append("**"); //$NON-NLS-1$ //$NON-NLS-2$

            if (!duplicateMethods.isEmpty())
            {
                body.append("\n\n**Warning: found duplicate method name(s): ") //$NON-NLS-1$
                    .append(String.join(", ", duplicateMethods)) //$NON-NLS-1$
                    .append(". A module with two methods sharing a name gets rejected by the " //$NON-NLS-1$
                        + "runtime (\"уже определена\"" //$NON-NLS-1$ // "уже определена"
                        + ") even though EDT itself will not flag it - rename or delete one.**"); //$NON-NLS-1$
            }

            if (validation != null)
                appendValidationSection(body, validation);

            return fm.wrapContent(body.toString());
        }
        catch (Exception e)
        {
            return "Failed while writing the file: " + TextSuggest.safeMessage(e); //$NON-NLS-1$
        }
    }

    /**
     * Is the supplied token one of the eight known modes?
     *
     * @param token candidate
     * @return true when recognised
     */
    private static boolean isValidMode(String token)
    {
        return MODE_REPLACE.equals(token) || MODE_APPEND.equals(token)
            || MODE_SEARCH_REPLACE.equals(token) || MODE_REPLACE_LINES.equals(token)
            || MODE_REPLACE_METHOD.equals(token) || MODE_REPLACE_METHODS.equals(token)
            || MODE_INSERT_BEFORE.equals(token) || MODE_INSERT_AFTER.equals(token);
    }

    /**
     * Picks the BSL source from the first non-empty alias in
     * {@code ["source", "content", "sourceCode", "code", "text"]}. Before multi-alias support only
     * {@code source} was honoured and the rest were silently dropped, surfacing as a misleading
     * "source is required" error that pushed agents to hand-edit.
     *
     * @param params tool params
     * @return the source, or {@code null} when none of the aliases carry a non-empty value
     */
    private static String extractSource(Map<String, String> params)
    {
        String[] keys = {"source", "content", "sourceCode", "code", "text"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        for (String key : keys)
        {
            String value = JsonUtils.extractStringArgument(params, key);
            if (value != null && !value.isEmpty())
                return value;
        }
        return null;
    }

    /**
     * Resolves a module path relative to {@code src/} from an object FQN plus module type.
     *
     * @param objectName the FQN ({@code Type.Name})
     * @param moduleType module type token (may be null/empty - default chosen by type)
     * @param formName required for {@code FormModule} unless the type is {@code CommonForm}
     * @param commandName required for {@code CommandModule} unless the type is {@code CommonCommand}
     * @return the path, or an {@code "Error: ..."} string
     */
    private static String resolveModulePath(String objectName, String moduleType, String formName,
        String commandName)
    {
        int dotIndex = objectName.indexOf('.');
        if (dotIndex <= 0 || dotIndex >= objectName.length() - 1)
        {
            return "Error: objectName has to look like 'Type.Name' (e.g. 'Document.MyDoc', " //$NON-NLS-1$
                + "'CommonModule.MyModule')"; //$NON-NLS-1$
        }
        String typePart = objectName.substring(0, dotIndex);
        String namePart = objectName.substring(dotIndex + 1);

        String englishType = MetadataTypeCatalog.toEnglishSingular(typePart);
        if (englishType == null)
            return "Error: unrecognized metadata type: " + typePart; //$NON-NLS-1$
        String dirName = MetadataTypeCatalog.getDirectoryName(typePart);
        if (dirName == null)
            return "Error: metadata type '" + typePart + "' carries no src folder"; //$NON-NLS-1$ //$NON-NLS-2$

        if (moduleType == null || moduleType.isEmpty())
        {
            switch (englishType)
            {
                case "CommonModule": //$NON-NLS-1$
                case "CommonForm": //$NON-NLS-1$
                case "WebService": //$NON-NLS-1$
                case "HTTPService": //$NON-NLS-1$
                    moduleType = "Module"; //$NON-NLS-1$
                    break;
                case "CommonCommand": //$NON-NLS-1$
                    moduleType = "CommandModule"; //$NON-NLS-1$
                    break;
                default:
                    moduleType = "ObjectModule"; //$NON-NLS-1$
                    break;
            }
        }

        switch (moduleType)
        {
            case "Module": //$NON-NLS-1$
                return dirName + "/" + namePart + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ObjectModule": //$NON-NLS-1$
                return dirName + "/" + namePart + "/ObjectModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
            case "ManagerModule": //$NON-NLS-1$
                return dirName + "/" + namePart + "/ManagerModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
            case "RecordSetModule": //$NON-NLS-1$
                return dirName + "/" + namePart + "/RecordSetModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
            case "FormModule": //$NON-NLS-1$
                if (englishType.equals("CommonForm")) //$NON-NLS-1$
                    return dirName + "/" + namePart + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
                if (formName == null || formName.isEmpty())
                    return "Error: formName must be supplied when moduleType=FormModule"; //$NON-NLS-1$
                return dirName + "/" + namePart + "/Forms/" + formName + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "CommandModule": //$NON-NLS-1$
                if (englishType.equals("CommonCommand")) //$NON-NLS-1$
                    return dirName + "/" + namePart + "/CommandModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
                if (commandName == null || commandName.isEmpty())
                    return "Error: commandName must be supplied when moduleType=CommandModule"; //$NON-NLS-1$
                return dirName + "/" + namePart + "/Commands/" + commandName + "/CommandModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default:
                return "Error: unrecognized moduleType: " + moduleType + ". Valid values: ObjectModule, " //$NON-NLS-1$ //$NON-NLS-2$
                    + "ManagerModule, FormModule, CommandModule, RecordSetModule, Module"; //$NON-NLS-1$
        }
    }

    /**
     * Splits a source string into lines. A trailing newline does not produce an extra empty entry.
     *
     * @param source the source (assumed already normalised to {@code \n})
     * @return the lines, never {@code null}
     */
    private static List<String> splitSourceLines(String source)
    {
        if (source.isEmpty())
            return new ArrayList<>();
        String[] parts = source.split("\n", -1); //$NON-NLS-1$
        List<String> lines = new ArrayList<>(Arrays.asList(parts));
        if (source.endsWith("\n") && lines.size() > 1 && lines.get(lines.size() - 1).isEmpty()) //$NON-NLS-1$
            lines.remove(lines.size() - 1);
        return lines;
    }

    /**
     * Counts non-overlapping occurrences of {@code search} inside {@code text}.
     *
     * @param text haystack
     * @param search needle
     * @return the count
     */
    private static int countOccurrences(String text, String search)
    {
        int count = 0;
        int idx = 0;
        while (true)
        {
            int hit = text.indexOf(search, idx);
            if (hit < 0)
                break;
            count++;
            idx = hit + 1;
        }
        return count;
    }

    /**
     * Does the first non-blank line of {@code src} start with {@code //}?
     *
     * @param src source (any line endings)
     * @return true when the first non-blank line is a comment; false on null or all-blank input
     */
    private static boolean firstNonBlankIsComment(String src)
    {
        if (src == null)
            return false;
        String[] lines = src.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        for (String line : lines)
        {
            String t = line.trim();
            if (t.isEmpty())
                continue;
            return t.startsWith("//"); //$NON-NLS-1$
        }
        return false;
    }

    /**
     * Extends {@code start} upward over the contiguous {@code //} header-comment block directly
     * above it. A blank line between the comment and {@code start} stops the walk, so a
     * blank-separated section/region comment is never swallowed.
     *
     * @param lines the module lines
     * @param start the starting line index
     * @return the new start index (possibly the same)
     */
    private static int includeLeadingCommentBlock(List<String> lines, int start)
    {
        int s = start;
        for (int k = start - 1; k >= 0; k--)
        {
            if (lines.get(k).trim().startsWith("//")) //$NON-NLS-1$
                s = k;
            else
                break;
        }
        return s;
    }

    /**
     * Finds the inclusive span {@code [start, end]} of the method named {@code methodName} (matched
     * case-insensitively), including any preceding {@code &} compile-pragmas ({@code &НаКлиенте}
     * / {@code &НаСервере}). {@code #} region / preprocessor directives are NOT included - they
     * are structural and belong to the surrounding code, not the method.
     *
     * @param originalLines the module lines
     * @param methodName the method name to look for
     * @return {@code {start, end}} inclusive, or {@code null} when not found / unterminated
     */
    private static int[] findMethodSpan(List<String> originalLines, String methodName)
    {
        int total = originalLines.size();
        int methodStart = -1;
        for (int i = 0; i < total; i++)
        {
            Matcher m = BslModuleAccess.METHOD_START_PATTERN.matcher(originalLines.get(i));
            if (m.find() && m.group(1) != null && m.group(1).equalsIgnoreCase(methodName))
            {
                methodStart = i;
                break;
            }
        }
        if (methodStart < 0)
            return null;
        int directiveStart = methodStart;
        for (int k = methodStart - 1; k >= 0; k--)
        {
            String prevLine = originalLines.get(k).trim();
            // Only & compile pragmas (&НаКлиенте / &НаСервере / ...) are attached to
            // a method and travel with it. A # line is structural - a region directive
            // (#Область / #КонецОбласти) or a preprocessor block (#Если / #Тогда /
            // #КонецЕсли) - and belongs to the surrounding code: grabbing it would
            // delete the line and leave its closing half orphaned (row 41:
            // replace_method on the first method of a region ate the #Область line
            // and broke the region balance).
            if (prevLine.startsWith("&")) //$NON-NLS-1$
                directiveStart = k;
            else if (prevLine.isEmpty())
                continue;
            else
                break;
        }
        methodStart = directiveStart;
        for (int i = methodStart + 1; i < total; i++)
        {
            if (BslModuleAccess.METHOD_END_PATTERN.matcher(originalLines.get(i)).find())
                return new int[] {methodStart, i};
        }
        return null;
    }

    /**
     * Atomic multi-method replace. All-or-nothing: if any target is missing, the spec is malformed,
     * or two targets overlap, an error is returned and the caller writes nothing.
     *
     * @param originalLines the current module
     * @param methodsJson JSON array of {@code {methodName, source}} objects (aliases supported)
     * @return either the new lines or an error
     */
    private static ReplaceMethodsResult applyReplaceMethods(List<String> originalLines,
        String methodsJson)
    {
        if (methodsJson == null || methodsJson.trim().isEmpty())
        {
            return ReplaceMethodsResult.error(
                "Error: replaceMethods needs a 'methods' JSON array " //$NON-NLS-1$
                    + "[{\"methodName\":\"X\",\"source\":\"...\"}, ...]"); //$NON-NLS-1$
        }
        JsonArray arr;
        try
        {
            JsonElement parsed = JsonParser.parseString(methodsJson);
            if (!parsed.isJsonArray())
                return ReplaceMethodsResult.error("Error: 'methods' has to be a JSON array"); //$NON-NLS-1$
            arr = parsed.getAsJsonArray();
        }
        catch (Exception e)
        {
            return ReplaceMethodsResult
                .error("Error: 'methods' failed to parse as JSON: " + e.getMessage()); //$NON-NLS-1$
        }
        if (arr.size() == 0)
            return ReplaceMethodsResult.error("Error: 'methods' array has no entries"); //$NON-NLS-1$

        // Resolve every entry BEFORE touching anything.
        List<int[]> spans = new ArrayList<>();
        List<List<String>> sources = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++)
        {
            if (!arr.get(i).isJsonObject())
                return ReplaceMethodsResult.error("Error: methods[" + i + "] must be an object shaped " //$NON-NLS-1$ //$NON-NLS-2$
                    + "{methodName, source}"); //$NON-NLS-1$
            JsonObject obj = arr.get(i).getAsJsonObject();
            String name = memberString(obj, "methodName", "name"); //$NON-NLS-1$ //$NON-NLS-2$
            String src = memberString(obj, "source", "content", "sourceCode", "code", "text"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            if (name == null || name.trim().isEmpty())
                return ReplaceMethodsResult
                    .error("Error: methods[" + i + "] has no 'methodName'"); //$NON-NLS-1$ //$NON-NLS-2$
            if (src == null)
                return ReplaceMethodsResult.error(
                    "Error: methods[" + i + "] ('" + name + "') has no 'source'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (src.trim().isEmpty())
                return ReplaceMethodsResult.error("Error: methods[" + i + "] ('" + name //$NON-NLS-1$ //$NON-NLS-2$
                    + "') has an empty source - replaceMethods swaps out a method's body, " //$NON-NLS-1$
                    + "it will not delete a method"); //$NON-NLS-1$
            if (src.length() > MAX_SOURCE_LENGTH)
                return ReplaceMethodsResult
                    .error("Error: methods[" + i + "] source is longer than the maximum allowed"); //$NON-NLS-1$ //$NON-NLS-2$

            int[] span = findMethodSpan(originalLines, name.trim());
            if (span == null)
            {
                missing.add(name.trim());
                continue;
            }
            if (firstNonBlankIsComment(src))
                span[0] = includeLeadingCommentBlock(originalLines, span[0]);
            spans.add(span);
            sources.add(splitSourceLines(src.replace("\r\n", "\n").replace("\r", "\n"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            names.add(name.trim());
        }
        if (!missing.isEmpty())
        {
            return ReplaceMethodsResult.error("Error: method(s) missing from the module: " //$NON-NLS-1$
                + String.join(", ", missing) + " - nothing was written"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Apply replacements left-to-right (ascending start), rejecting overlaps.
        Integer[] order = new Integer[spans.size()];
        for (int i = 0; i < order.length; i++)
            order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(spans.get(a)[0], spans.get(b)[0]));

        List<String> newLines = new ArrayList<>();
        int cursor = 0;
        int prevEnd = -1;
        for (Integer oi : order)
        {
            int idx = oi;
            int start = spans.get(idx)[0];
            int end = spans.get(idx)[1];
            if (start <= prevEnd)
            {
                return ReplaceMethodsResult.error(
                    "Error: overlapping or duplicate target method '" + names.get(idx) //$NON-NLS-1$
                        + "' - nothing was written"); //$NON-NLS-1$
            }
            newLines.addAll(originalLines.subList(cursor, start));
            newLines.addAll(sources.get(idx));
            cursor = end + 1;
            prevEnd = end;
        }
        if (cursor < originalLines.size())
            newLines.addAll(originalLines.subList(cursor, originalLines.size()));
        return ReplaceMethodsResult.ok(newLines);
    }

    /**
     * Reads the first present key (in order) from a JSON object as a string.
     *
     * @param obj the object
     * @param keys candidate key names in priority order
     * @return the value, or {@code null} when no candidate is a string primitive
     */
    private static String memberString(JsonObject obj, String... keys)
    {
        for (String k : keys)
        {
            if (obj.has(k) && obj.get(k).isJsonPrimitive())
                return obj.get(k).getAsString();
        }
        return null;
    }

    /**
     * Probes the file's first three bytes for a UTF-8 BOM. On any read failure, assumes the BOM is
     * present (BSL files conventionally carry one).
     *
     * @param file the file (must exist)
     * @return true when a BOM is detected (or on read error)
     */
    private boolean detectBom(IFile file)
    {
        try (InputStream is = file.getContents();
            BufferedInputStream bis = new BufferedInputStream(is))
        {
            byte[] bom = new byte[3];
            int read = bis.read(bom);
            return read == 3 && (bom[0] & 0xFF) == 0xEF && (bom[1] & 0xFF) == 0xBB
                && (bom[2] & 0xFF) == 0xBF;
        }
        catch (Exception e)
        {
            return true;
        }
    }

    /**
     * Writes the lines to the file, always terminating the content with a newline and preserving
     * (or adding) a UTF-8 BOM.
     * <p>
     * Reading normalises the module to {@code \n} so the editing above can address lines in one
     * form; this is where that is undone. Joining with {@code \n} and stopping there would convert
     * every module the plugin touches to LF while its neighbours stay CRLF - see
     * {@link LineDelimiters}.
     * </p>
     *
     * @param file target file
     * @param lines lines to write, joined with the delimiter the file already uses
     * @param withBom whether to prepend the BOM
     * @param fileExists whether the file already exists (create vs. setContents)
     * @throws Exception on any I/O or workspace failure
     */
    private void writeFile(IFile file, List<String> lines, boolean withBom, boolean fileExists)
        throws Exception
    {
        String content = String.join("\n", lines); //$NON-NLS-1$
        if (!content.endsWith("\n")) //$NON-NLS-1$
            content = content + "\n"; //$NON-NLS-1$
        // Rewrite the assembled text rather than joining with the delimiter directly: a caller can
        // hand in a line that carries its own breaks, and joining would leave those behind as LF.
        content = LineDelimiters.rewrite(content, LineDelimiters.of(file));
        byte[] contentBytes = content.getBytes("UTF-8"); //$NON-NLS-1$
        byte[] output;
        if (withBom)
        {
            output = new byte[UTF8_BOM.length + contentBytes.length];
            System.arraycopy(UTF8_BOM, 0, output, 0, UTF8_BOM.length);
            System.arraycopy(contentBytes, 0, output, UTF8_BOM.length, contentBytes.length);
        }
        else
        {
            output = contentBytes;
        }
        try (InputStream stream = new ByteArrayInputStream(output))
        {
            if (fileExists)
                file.setContents(stream, IResource.FORCE | IResource.KEEP_HISTORY, null);
            else
            {
                createParentFolders(file);
                file.create(stream, true, null);
            }
        }
    }

    /**
     * Recursively creates the parent folders of {@code file} as workspace IFolders.
     *
     * @param file the file whose parents are needed
     * @throws Exception on any workspace failure
     */
    private void createParentFolders(IFile file) throws Exception
    {
        IFolder parent = (IFolder)file.getParent();
        createFolder(parent);
    }

    /**
     * Recursively creates a folder and its ancestors.
     *
     * @param folder the folder to create
     * @throws Exception on any workspace failure
     */
    private void createFolder(IFolder folder) throws Exception
    {
        if (folder.exists())
            return;
        if (folder.getParent() instanceof IFolder && !folder.getParent().exists())
            createFolder((IFolder)folder.getParent());
        folder.create(true, true, null);
    }

    /**
     * Flushes the BM index for the written module so subsequent EDT-side reads see the new content.
     *
     * @param project the project
     * @param moduleFqn the module FQN (may be null/empty - skip)
     * @return the persistence outcome
     */
    private PersistenceResult forceExportModule(IProject project, String moduleFqn)
    {
        long start = System.currentTimeMillis();
        PersistenceResult result = new PersistenceResult();
        if (project == null || moduleFqn == null || moduleFqn.isEmpty())
        {
            result.ok = false;
            result.elapsedMs = System.currentTimeMillis() - start;
            result.detail = "skipped: project or moduleFqn was unavailable"; //$NON-NLS-1$
            return result;
        }
        IBmModelManager mgr = Activator.getDefault().getBmModelManager();
        if (mgr == null)
        {
            result.ok = false;
            result.elapsedMs = System.currentTimeMillis() - start;
            result.detail = "skipped: no IBmModelManager instance was available"; //$NON-NLS-1$
            return result;
        }
        BmExportHelper.Result r = BmExportHelper.forceExportAndWait(mgr, project, moduleFqn);
        result.ok = r.isOk() && !r.syncFlushPending;
        result.elapsedMs = r.totalMs;
        if (r.error != null)
            result.detail = r.error;
        else if (r.syncFlushPending)
        {
            result.detail = "forceExport succeeded, but the BM index sync did not confirm within the " //$NON-NLS-1$
                + "wait budget - the module change is already committed to BM and the save is wrapping " //$NON-NLS-1$
                + "up in the background. The .bsl file on disk already reflects it; subsequent " //$NON-NLS-1$
                + "EDT-side reads (validation / F7 / deploy) may lag briefly."; //$NON-NLS-1$
        }
        else if (!r.waitComputationOk)
        {
            result.detail = "forceExport succeeded, but waitComputation timed out (" + r.waitComputationMs //$NON-NLS-1$
                + " ms)"; //$NON-NLS-1$
        }
        return result;
    }

    /**
     * Derives the module FQN used to look up markers, preferring an explicit {@code objectName}.
     *
     * @param objectName explicit FQN, may be null
     * @param modulePath path under src/, may be null
     * @return the FQN, or null when neither yields one
     */
    private static String resolveFqnForValidation(String objectName, String modulePath)
    {
        if (objectName != null && !objectName.isEmpty())
            return objectName;
        if (modulePath == null || modulePath.isEmpty())
            return null;
        String[] parts = modulePath.replace('\\', '/').split("/"); //$NON-NLS-1$ //$NON-NLS-2$
        if (parts.length < 2)
            return null;
        String dirName = parts[0];
        String namePart = parts[1];
        String typePart = MetadataTypeCatalog.getTypeByDirectoryName(dirName);
        if (typePart == null)
            return null;
        return typePart + "." + namePart; //$NON-NLS-1$
    }

    /**
     * Collects EDT markers for the freshly written module, polling up to three times so the index
     * has time to publish.
     *
     * @param project the project
     * @param objectName explicit FQN, may be null
     * @param modulePath path under src/, used when objectName is null
     * @return grouped markers, or null when the marker manager / FQN is unavailable
     */
    private FileMarkers.Grouped collectValidation(IProject project, String objectName,
        String modulePath)
    {
        try
        {
            IMarkerManager markerManager = Activator.getDefault().getMarkerManager();
            if (markerManager == null)
                return null;
            String fqn = resolveFqnForValidation(objectName, modulePath);
            if (fqn == null || fqn.isEmpty())
                return null;
            List<FileMarkers.MarkerInfo> markers = null;
            for (int attempt = 0; attempt < 3; attempt++)
            {
                if (attempt > 0)
                {
                    try
                    {
                        Thread.sleep(300);
                    }
                    catch (InterruptedException ie)
                    {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                markers = FileMarkers.getMarkersByObjectPresentation(markerManager, project, fqn,
                    null, 200);
                if (!markers.isEmpty())
                    break;
            }
            return FileMarkers.groupBySeverity(markers != null ? markers : new ArrayList<>());
        }
        catch (Exception e)
        {
            Activator.logWarning("validateAfterWrite step was skipped: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Builds a short human hint from the grouped marker counts.
     *
     * @param g the grouped markers
     * @return the hint, never {@code null}
     */
    private static String buildValidationHint(FileMarkers.Grouped g)
    {
        if (g.isEmpty())
            return "Nothing to flag"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        if (g.errorCount() > 0)
            sb.append(g.errorCount()).append(" error(s) - resolve these first. "); //$NON-NLS-1$
        if (g.warningCount() > 0)
            sb.append(g.warningCount()).append(" warning(s) - worth reviewing. "); //$NON-NLS-1$
        if (g.codeStyleCount() > 0)
            sb.append(g.codeStyleCount()).append(" style suggestion(s) - take or leave. "); //$NON-NLS-1$
        return sb.toString().trim();
    }

    /**
     * Finds method names declared more than once (case-insensitive - BSL method names are).
     * Commented-out declarations do not match: the leading {@code //} breaks the anchor.
     *
     * @param lines the module lines
     * @return the duplicate names in first-seen order
     */
    private static List<String> findDuplicateMethods(List<String> lines)
    {
        Map<String, String> firstSeen = new LinkedHashMap<>();
        LinkedHashSet<String> dups = new LinkedHashSet<>();
        for (String line : lines)
        {
            Matcher m = BslModuleAccess.METHOD_START_PATTERN.matcher(line);
            if (m.find())
            {
                String name = m.group(1);
                if (name == null || name.isEmpty())
                    continue;
                String key = name.toLowerCase(Locale.ROOT);
                if (firstSeen.containsKey(key))
                    dups.add(firstSeen.get(key));
                else
                    firstSeen.put(key, name);
            }
        }
        return new ArrayList<>(dups);
    }

    /**
     * Appends a {@code ## Validation} section to the body.
     *
     * @param body the body builder
     * @param g the grouped markers
     */
    private void appendValidationSection(StringBuilder body, FileMarkers.Grouped g)
    {
        if (g.isEmpty())
        {
            body.append("\n\n## Validation Findings\n\nNothing to flag."); //$NON-NLS-1$
            return;
        }
        body.append("\n\n## Validation Findings\n"); //$NON-NLS-1$
        appendMarkerList(body, "Errors", g.errors); //$NON-NLS-1$
        appendMarkerList(body, "Warnings", g.warnings); //$NON-NLS-1$
        appendMarkerList(body, "Style notes", g.codeStyle); //$NON-NLS-1$
    }

    /**
     * Appends a titled marker list to the body.
     *
     * @param body the body builder
     * @param title section title
     * @param list markers
     */
    private void appendMarkerList(StringBuilder body, String title,
        List<FileMarkers.MarkerInfo> list)
    {
        if (list.isEmpty())
            return;
        body.append("\n### ").append(title).append(" (").append(list.size()).append(")\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (FileMarkers.MarkerInfo m : list)
        {
            body.append("- "); //$NON-NLS-1$
            if (m.line > 0)
                body.append("L").append(m.line).append(" "); //$NON-NLS-1$ //$NON-NLS-2$
            body.append(m.message);
            if (m.checkId != null && !m.checkId.isEmpty())
                body.append(" `").append(m.checkId).append("`"); //$NON-NLS-1$ //$NON-NLS-2$
            body.append("\n"); //$NON-NLS-1$
        }
    }

    /**
     * Normalises line endings and strips trailing CR/LF only (not spaces/tabs - the actualBlock
     * keeps meaningful trailing whitespace on its last line, so stripping it here would make the
     * comparison asymmetric).
     *
     * @param s raw expected text
     * @return the normalised text
     */
    private static String normalizeForCompare(String s)
    {
        String n = s.replace("\r\n", "\n").replace("\r", "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        while (n.endsWith("\n") || n.endsWith("\r")) //$NON-NLS-1$ //$NON-NLS-2$
            n = n.substring(0, n.length() - 1);
        return n;
    }

    /**
     * Builds the verbatim "stale line numbers" error for the {@code replaceLines} drift guard.
     *
     * @param lineFrom 1-based start
     * @param lineTo 1-based end
     * @param expected the expected block (already normalised)
     * @param actual the actual block
     * @return the multi-line error string
     */
    private static String lineDriftError(int lineFrom, int lineTo, String expected, String actual)
    {
        return "Error: expectedText no longer matches what is currently on lines " + lineFrom //$NON-NLS-1$
            + "-" + lineTo + ". The module changed since you last read it (so the line numbers are stale) - the " //$NON-NLS-1$ //$NON-NLS-2$
            + "replaceLines edit was REJECTED to avoid overwriting the wrong lines. Re-read the " //$NON-NLS-1$
            + "module (get_module_structure / read_module_source / read_method_source), then retry " //$NON-NLS-1$
            + "with fresh line numbers and expectedText.\n" //$NON-NLS-1$
            + "--- expected (" + countLines(expected) + " lines) ---\n" //$NON-NLS-1$ //$NON-NLS-2$
            + truncateForError(expected) + "\n" //$NON-NLS-1$
            + "--- actual (" + countLines(actual) + " lines) ---\n" //$NON-NLS-1$ //$NON-NLS-2$
            + truncateForError(actual);
    }

    /**
     * Counts the lines in a string (an empty string is zero lines; otherwise one plus the number of
     * newlines).
     *
     * @param s the string
     * @return the line count
     */
    private static int countLines(String s)
    {
        if (s.isEmpty())
            return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++)
        {
            if (s.charAt(i) == '\n')
                n++;
        }
        return n;
    }

    /**
     * Truncates a string to 600 chars, appending a "+N more chars" note when it exceeds that.
     *
     * @param s the string
     * @return the original, or a truncated form with a suffix
     */
    private static String truncateForError(String s)
    {
        final int max = 600;
        if (s.length() <= max)
            return s;
        return s.substring(0, max) + "\n... [+" + (s.length() - max) + " more characters truncated]"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Outcome of {@link #applyReplaceMethods(List, String)}: either the new lines or an error.
     */
    private static final class ReplaceMethodsResult
    {
        final List<String> newLines;
        final String error;

        private ReplaceMethodsResult(List<String> newLines, String error)
        {
            this.newLines = newLines;
            this.error = error;
        }

        static ReplaceMethodsResult error(String msg)
        {
            return new ReplaceMethodsResult(null, msg);
        }

        static ReplaceMethodsResult ok(List<String> lines)
        {
            return new ReplaceMethodsResult(lines, null);
        }
    }

    /**
     * Outcome of {@link #forceExportModule(IProject, String)}: ok flag, elapsed time, optional
     * detail.
     */
    private static final class PersistenceResult
    {
        boolean ok;
        long elapsedMs;
        String detail;
    }
}
