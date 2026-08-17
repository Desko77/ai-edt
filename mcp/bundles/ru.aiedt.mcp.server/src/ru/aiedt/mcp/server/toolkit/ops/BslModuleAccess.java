/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.linking.lazy.LazyLinkingResource;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.util.CancelIndicator;

import com._1c.g5.v8.dt.bm.xtext.BmAwareResourceSetProvider;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.ToolCallScope;

/**
 * The one place that turns a module reference into a file, loads its BSL model, reads its bytes, and
 * reads line numbers, source slices and signatures off its AST.
 * <p>
 * A module reference reaches this class as either a real {@code src/}-relative path or a dot-separated
 * module FQN, and both come back as a path the workspace understands. The AST accessors sit on top of
 * the Xtext node model, so callers that want a method's lines or source never touch the node model
 * directly. The regex toolkit at the top is shared with the tools that scan module text without loading
 * the model at all.
 * </p>
 * <p>
 * Everything is static and works on locals; the keyword patterns are compiled once. Cyrillic in the
 * patterns is written as {@code \}{@code uXXXX} escapes so the file stays pure ASCII and the compiled
 * patterns cannot be mangled by an editor guessing an encoding.
 * </p>
 */
public final class BslModuleAccess
{
    /**
     * A dummy {@code .bsl} URI, used only to look up the BSL language services from the Xtext registry.
     * <p>
     * Any path ending in {@code .bsl} resolves the BSL language; the exact value is not otherwise
     * meaningful, but callers reference this field, so it stays put.
     * </p>
     */
    public static final URI BSL_LOOKUP_URI = URI.createURI("/nopr/module.bsl"); //$NON-NLS-1$

    /**
     * Matches a method header. Group 1 is the method name, group 2 is everything from just after the
     * opening parenthesis to the end of the line.
     */
    public static final Pattern METHOD_START_PATTERN = Pattern.compile(
        "^\\s*(?:\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430|\u0424\u0443\u043D\u043A\u0446\u0438\u044F|Procedure|Function)\\s+(\\S+?)\\s*\\((.*)$", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Matches the line that ends a method. */
    public static final Pattern METHOD_END_PATTERN = Pattern.compile(
        "^\\s*(?:\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B|\u041A\u043E\u043D\u0435\u0446\u0424\u0443\u043D\u043A\u0446\u0438\u0438|EndProcedure|EndFunction)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Matches a header that begins a function, telling it apart from a procedure. */
    public static final Pattern FUNC_KEYWORD_PATTERN = Pattern.compile(
        "^\\s*(?:\u0424\u0443\u043D\u043A\u0446\u0438\u044F|Function)\\s", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Matches a region header. Group 1 is the region name. */
    public static final Pattern REGION_START_PATTERN = Pattern.compile(
        "^\\s*#(?:\u041E\u0431\u043B\u0430\u0441\u0442\u044C|Region)\\s+(\\S+)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Matches the line that ends a region. */
    public static final Pattern REGION_END_PATTERN = Pattern.compile(
        "^\\s*#(?:\u041A\u043E\u043D\u0435\u0446\u041E\u0431\u043B\u0430\u0441\u0442\u0438|EndRegion)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * The module files probed, in order, for a two-part FQN {@code Type.Name}. The first that exists on
     * disk wins; more than one existing is an ambiguity the caller is told to disambiguate.
     */
    private static final String[] OBJECT_MODULE_FILE_NAMES = {
        "Module.bsl", //$NON-NLS-1$
        "ManagerModule.bsl", //$NON-NLS-1$
        "ObjectModule.bsl", //$NON-NLS-1$
        "RecordSetModule.bsl", //$NON-NLS-1$
        "ValueManagerModule.bsl", //$NON-NLS-1$
        "CommandModule.bsl" //$NON-NLS-1$
    };

    /** How many characters long the {@code /src/} marker is. */
    private static final int SRC_MARKER_LENGTH = 5;

    /** How many bytes a UTF-8 byte-order mark takes. */
    private static final int BOM_LENGTH = 3;

    /** Size of the read buffer when slurping a whole file into a string. */
    private static final int READ_BUFFER_SIZE = 4096;

    private BslModuleAccess()
    {
        // utility
    }

    /**
     * Loads the BSL model root for a module.
     * <p>
     * The path is taken verbatim after {@code <project>/src/}; the same folder layout serves
     * configuration and extension projects, so nothing here branches on the project kind. An empty
     * resource means the model is not built yet - the caller should treat a <code>null</code> return as
     * "AST not available" and fall back to reading text.
     * </p>
     *
     * @param project the project the module lives in
     * @param modulePath the module path relative to {@code src/}, for example
     *            {@code CommonModules/MyModule/Module.bsl}
     * @return the module model root, or <code>null</code> on any failure - no provider, no resource set,
     *         a resource that would not load, an unbuilt model, or a root that is not a module
     */
    public static Module loadModule(IProject project, String modulePath)
    {
        BmAwareResourceSetProvider resourceSetProvider = obtainResourceSetProvider();
        if (resourceSetProvider == null)
        {
            Activator.logWarning("Cannot load module: no BM-aware resource set provider is available"); //$NON-NLS-1$
            return null;
        }

        ResourceSet resourceSet = resourceSetProvider.get(project);
        if (resourceSet == null)
        {
            Activator.logWarning("Cannot load module: the resource set for the project is null"); //$NON-NLS-1$
            return null;
        }

        URI uri = URI.createPlatformResourceURI(project.getName() + "/src/" + modulePath, true); //$NON-NLS-1$
        Activator.logInfo("Loading BSL module from " + uri); //$NON-NLS-1$

        try
        {
            Resource resource = resourceSet.getResource(uri, true);
            if (resource == null)
            {
                Activator.logWarning("Cannot load module: no resource at " + uri); //$NON-NLS-1$
                return null;
            }
            EList<EObject> contents = resource.getContents();
            if (contents.isEmpty())
            {
                // The resource exists but Xtext has not populated it: the model is not built yet.
                Activator.logWarning("Resource contents empty for " + uri); //$NON-NLS-1$
                return null;
            }
            EObject root = contents.get(0);
            if (root instanceof Module)
            {
                return (Module)root;
            }
            Activator.logWarning("Source root resolved to " + root.getClass().getName() + ", not Module"); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        catch (Exception e)
        {
            Activator.logError("Failed to load BSL module from " + uri, e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Resolves a module's cross-references, which is the step that puts types on its contents.
     * <p>
     * A variable's type is nowhere in the parse tree. It is installed by the BSL resource while it
     * resolves its own cross-references - the work an open editor used to do on our behalf. Resolving
     * the EMF proxies instead is not the same step: that was tried, shipped, and left the hover
     * answering with a bare name.
     * </p>
     * <p>
     * The call is synchronous off the display thread and asynchronous on it. Tools run on a request
     * thread, so it is the synchronous one - and it is genuinely expensive, which is why the callers
     * let it be declined, count themselves among the heavy tools, and let an operator interrupt reach
     * into it.
     * </p>
     *
     * @param resource the module's resource; anything else is ignored
     */
    public static TypeState resolveCrossReferences(Resource resource)
    {
        if (!(resource instanceof LazyLinkingResource))
        {
            return TypeState.NO_MODEL;
        }
        ToolCallScope scope = ToolCallScope.current();
        CancelIndicator cancelled =
            scope == null ? CancelIndicator.NullImpl : () -> scope.cancellation().isCancelled();
        try
        {
            // Dispatches to the BSL resource's own override, which installs the type state.
            ((LazyLinkingResource)resource).resolveLazyCrossReferences(cancelled);
        }
        catch (RuntimeException e)
        {
            // The names still answer without this, so a failure here makes the reply thinner rather
            // than absent.
            Activator.logWarning("Could not resolve the module's cross-references: " + e.getMessage()); //$NON-NLS-1$
            return TypeState.FAILED;
        }
        // Resolution is interruptible and stops where it is, leaving the type state partly
        // installed. The names still answer, so the reply looked ordinary - a position whose
        // type had not been reached read exactly like a position that has no type. Callers
        // comparing runs saw the same point answer differently and had no way to tell which
        // reading was the real one. Say which happened instead.
        return cancelled.isCanceled() ? TypeState.INTERRUPTED : TypeState.READY;
    }

    /**
     * What became of the type state a module's answers depend on.
     */
    public enum TypeState
    {
        /** Cross-references resolved; a missing type means there is none. */
        READY,
        /** Resolution was interrupted partway; a missing type may simply not have been reached. */
        INTERRUPTED,
        /** Resolution threw; types are unavailable for this module. */
        FAILED,
        /** The module has no resolvable model at all. */
        NO_MODEL;

        /**
         * @return the reason to hand the caller, or <code>null</code> when types are trustworthy
         */
        public String reason()
        {
            switch (this)
            {
            case INTERRUPTED:
                return "type computation was interrupted partway, so a position without a type " //$NON-NLS-1$
                    + "here may simply not have been reached - ask again to get a full answer"; //$NON-NLS-1$
            case FAILED:
                return "type computation failed for this module, so no position carries a type " //$NON-NLS-1$
                    + "in this answer"; //$NON-NLS-1$
            case NO_MODEL:
                return "this module has no resolvable model, so no position carries a type"; //$NON-NLS-1$
            default:
                return null;
            }
        }
    }

    /**
     * Obtains the BM-aware resource set provider, first from the OSGi service and then, if that is not
     * registered, from the BSL language's Guice injector.
     *
     * @return the provider, or <code>null</code> when neither path yields one
     */
    private static BmAwareResourceSetProvider obtainResourceSetProvider()
    {
        Activator activator = Activator.getDefault();
        if (activator != null)
        {
            BmAwareResourceSetProvider provider = activator.getResourceSetProvider();
            if (provider != null)
            {
                return provider;
            }
        }

        // The OSGi service is not registered; reach the provider through the Xtext injector instead.
        Activator.logInfo("Resource set provider service is not registered; using the Xtext injector"); //$NON-NLS-1$
        try
        {
            IResourceServiceProvider rsp =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(BSL_LOOKUP_URI);
            if (rsp != null)
            {
                return rsp.get(BmAwareResourceSetProvider.class);
            }
        }
        catch (Exception e)
        {
            Activator.logError("Failed to obtain the resource set provider from the Xtext injector", e); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Turns a module reference into a {@code src/}-relative path.
     * <p>
     * A reference that already carries a path separator is taken verbatim, with no existence and no
     * traversal check - callers that accept untrusted input reject {@code ..} themselves before calling
     * here. A reference with no separator is treated as a dot-separated module FQN and resolved against
     * the project.
     * </p>
     *
     * @param project the project to resolve an FQN against; may be <code>null</code>, in which case an
     *            FQN cannot be confirmed to exist
     * @param input the module reference; a path or an FQN
     * @return the resolution: resolved with a path, or unresolved with a hint explaining why. Never
     *         <code>null</code>
     */
    public static ModulePathResolution resolveModulePath(IProject project, String input)
    {
        if (input == null || input.isEmpty())
        {
            return ModulePathResolution.unresolved("Error: modulePath is required"); //$NON-NLS-1$
        }
        if (input.indexOf('/') >= 0 || input.indexOf('\\') >= 0)
        {
            // Already a real path. Historic contract: no checking here.
            return ModulePathResolution.resolved(input);
        }
        return resolveFqnToModulePath(project, input);
    }

    /**
     * Resolves a dot-separated module FQN to a {@code src/}-relative path by probing the layout on disk.
     *
     * @param project the project to probe; may be <code>null</code>
     * @param fqn the FQN, for example {@code CommonModule.MyModule} or
     *            {@code Catalog.Products.ManagerModule}
     * @return the resolution, resolved or with the appropriate hint
     */
    private static ModulePathResolution resolveFqnToModulePath(IProject project, String fqn)
    {
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return ModulePathResolution.unresolved(buildFormatHint(fqn));
        }

        String dir = MetadataTypeCatalog.getDirectoryName(parts[0]);
        if (dir == null)
        {
            // Either an unknown type or one that is not addressed by path - both mean no file to go to.
            return ModulePathResolution.unresolved(buildFormatHint(fqn));
        }

        String objectName = parts[1];
        List<String> candidates = new ArrayList<>();
        if (parts.length == 2)
        {
            for (String fileName : OBJECT_MODULE_FILE_NAMES)
            {
                candidates.add(dir + "/" + objectName + "/" + fileName); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        else if (parts.length == 3)
        {
            if (isFormKeyword(parts[2]))
            {
                // Type.Name.Form with no form name after it - nothing to resolve to.
                return ModulePathResolution.unresolved(buildFormatHint(fqn));
            }
            String fileName = endsWithBslIgnoreCase(parts[2]) ? parts[2] : parts[2] + ".bsl"; //$NON-NLS-1$
            candidates.add(dir + "/" + objectName + "/" + fileName); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else if (isFormKeyword(parts[2]))
        {
            // Type.Name.Form.FormName[.Module...] - the form's own module.
            candidates.add(dir + "/" + objectName + "/Forms/" + parts[3] + "/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        else
        {
            return ModulePathResolution.unresolved(buildFormatHint(fqn));
        }

        List<String> hits = new ArrayList<>();
        for (String candidate : candidates)
        {
            if (fileExistsInProject(project, candidate))
            {
                hits.add(candidate);
            }
        }
        if (hits.size() == 1)
        {
            return ModulePathResolution.resolved(hits.get(0));
        }
        if (hits.size() > 1)
        {
            return ModulePathResolution.unresolved(buildAmbiguousHint(fqn, hits));
        }
        return ModulePathResolution.unresolved(buildNotFoundHint(project, fqn, candidates));
    }

    /**
     * Tells whether a token is the "form" keyword, in either language, singular or plural, any case.
     *
     * @param token the FQN segment to test
     * @return <code>true</code> when it is one of {@code form}, {@code forms} and their Russian twins
     */
    private static boolean isFormKeyword(String token)
    {
        return "form".equalsIgnoreCase(token) //$NON-NLS-1$
            || "forms".equalsIgnoreCase(token) //$NON-NLS-1$
            || "\u0424\u043E\u0440\u043C\u0430".equalsIgnoreCase(token) //$NON-NLS-1$
            || "\u0424\u043E\u0440\u043C\u044B".equalsIgnoreCase(token); //$NON-NLS-1$
    }

    /**
     * Tells whether a string ends with the {@code .bsl} suffix, ignoring case.
     *
     * @param value the string to test
     * @return <code>true</code> when it ends in {@code .bsl} in any case
     */
    private static boolean endsWithBslIgnoreCase(String value)
    {
        return value.length() >= 4 && value.regionMatches(true, value.length() - 4, ".bsl", 0, 4); //$NON-NLS-1$
    }

    /**
     * Tells whether a {@code src/}-relative path names an existing file in a project.
     *
     * @param project the project; may be <code>null</code>
     * @param relativePath the path under {@code src/}
     * @return <code>true</code> when the file exists; <code>false</code> for a <code>null</code> project
     *         or on any error
     */
    private static boolean fileExistsInProject(IProject project, String relativePath)
    {
        if (project == null)
        {
            return false;
        }
        try
        {
            return project.getFile(new Path("src").append(relativePath)).exists(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * Builds the "cannot resolve" hint that shows both accepted forms of a module reference.
     *
     * @param input the reference that could not be resolved
     * @return the hint
     */
    private static String buildFormatHint(String input)
    {
        return "Error: cannot resolve modulePath '" + input + "'. Provide a path from src/ " //$NON-NLS-1$ //$NON-NLS-2$
            + "(e.g. 'CommonModules/MyModule/Module.bsl', 'Documents/SalesOrder/ObjectModule.bsl') " //$NON-NLS-1$
            + "or a module FQN (e.g. 'CommonModule.MyModule', 'Catalog.Products.ManagerModule', " //$NON-NLS-1$
            + "'Catalog.Products.Form.ItemForm')."; //$NON-NLS-1$
    }

    /**
     * Builds the "ambiguous" hint that lists the modules an FQN maps to and how to disambiguate.
     *
     * @param fqn the ambiguous FQN
     * @param hits the paths it matched, more than one
     * @return the hint
     */
    private static String buildAmbiguousHint(String fqn, List<String> hits)
    {
        StringBuilder builder = new StringBuilder();
        builder.append("Error: module FQN '").append(fqn) //$NON-NLS-1$
            .append("' is ambiguous - it maps to several existing modules:\n"); //$NON-NLS-1$
        for (String hit : hits)
        {
            builder.append("- ").append(hit).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        StringBuilder kinds = new StringBuilder();
        for (int i = 0; i < hits.size(); i++)
        {
            if (i > 0)
            {
                kinds.append(" or "); //$NON-NLS-1$
            }
            kinds.append("'").append(fqn).append(".").append(moduleKindFromPath(hits.get(i))).append("'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        builder.append("Disambiguate by appending the module kind, e.g. ").append(kinds) //$NON-NLS-1$
            .append(", or pass the full path from src/."); //$NON-NLS-1$
        return builder.toString();
    }

    /**
     * Builds the "not found" hint that lists every path that was probed and found missing.
     *
     * @param project the project probed; may be <code>null</code>
     * @param fqn the FQN that resolved to nothing
     * @param candidates the paths that were probed
     * @return the hint
     */
    private static String buildNotFoundHint(IProject project, String fqn, List<String> candidates)
    {
        String projectName = project == null ? "?" : project.getName(); //$NON-NLS-1$
        StringBuilder builder = new StringBuilder();
        builder.append("Error: cannot resolve module FQN '").append(fqn).append("' in project '") //$NON-NLS-1$ //$NON-NLS-2$
            .append(projectName).append("'. Probed paths (none exist):\n"); //$NON-NLS-1$
        for (String candidate : candidates)
        {
            builder.append("- src/").append(candidate).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        builder.append("Check the object name, or use list_modules to find the exact path."); //$NON-NLS-1$
        return builder.toString();
    }

    /**
     * Gives the module kind of a path: its file name without the {@code .bsl} suffix.
     *
     * @param path a {@code src/}-relative module path
     * @return the last segment with any {@code .bsl} suffix removed
     */
    private static String moduleKindFromPath(String path)
    {
        String fileName = path;
        int slash = path.lastIndexOf('/');
        if (slash >= 0)
        {
            fileName = path.substring(slash + 1);
        }
        if (endsWithBslIgnoreCase(fileName))
        {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }

    /**
     * Finds a method by name in a module, ignoring case.
     *
     * @param module the module; may be <code>null</code>
     * @param methodName the method name; may be <code>null</code>
     * @return the first method whose name matches, or <code>null</code> when either argument is
     *         <code>null</code> or no method matches
     */
    public static Method findMethod(Module module, String methodName)
    {
        if (module == null || methodName == null)
        {
            return null;
        }
        for (Method method : module.allMethods())
        {
            if (methodName.equalsIgnoreCase(method.getName()))
            {
                return method;
            }
        }
        return null;
    }

    /**
     * Reads a file into a list of lines, honoring a UTF-8 byte-order mark and otherwise the workspace's
     * declared charset.
     * <p>
     * Line separators are dropped. A workspace read that fails falls back to reading the file off disk.
     * </p>
     *
     * @param file the file to read
     * @return the lines, without their separators; possibly empty, never <code>null</code>
     * @throws Exception if the file cannot be read at all
     */
    public static List<String> readFileLines(IFile file) throws Exception
    {
        try (InputStream raw = openStream(file); BufferedInputStream input = new BufferedInputStream(raw))
        {
            String charset = detectCharset(file, input);
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, charset)))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    lines.add(line);
                }
            }
            return lines;
        }
    }

    /**
     * Reads a whole file into a string, honoring a UTF-8 byte-order mark and otherwise the workspace's
     * declared charset.
     * <p>
     * The original line separators are kept, because text offsets used elsewhere are against the raw
     * content, not a normalized copy. A workspace read that fails falls back to reading the file off
     * disk.
     * </p>
     *
     * @param file the file to read
     * @return the file's text; never <code>null</code>
     * @throws Exception if the file cannot be read at all
     */
    public static String readFileText(IFile file) throws Exception
    {
        try (InputStream raw = openStream(file); BufferedInputStream input = new BufferedInputStream(raw))
        {
            String charset = detectCharset(file, input);
            StringBuilder builder = new StringBuilder();
            try (InputStreamReader reader = new InputStreamReader(input, charset))
            {
                char[] buffer = new char[READ_BUFFER_SIZE];
                int count;
                while ((count = reader.read(buffer)) != -1)
                {
                    builder.append(buffer, 0, count);
                }
            }
            return builder.toString();
        }
    }

    /**
     * Opens a stream over a file, preferring the workspace copy and falling back to disk.
     * <p>
     * A large project can have an unsynchronized workspace where {@code getContents} fails; reading the
     * file off disk bypasses that. When there is no filesystem location, or the file is not there, the
     * original failure is re-thrown.
     * </p>
     *
     * @param file the file to open
     * @return an open stream
     * @throws Exception when neither the workspace nor the filesystem yields the file
     */
    private static InputStream openStream(IFile file) throws Exception
    {
        try
        {
            return file.getContents();
        }
        catch (Exception primary)
        {
            IPath location = file.getLocation();
            if (location == null)
            {
                throw primary;
            }
            File osFile = location.toFile();
            if (osFile == null || !osFile.exists())
            {
                throw primary;
            }
            return new FileInputStream(osFile);
        }
    }

    /**
     * Detects the charset to read a file with: forced UTF-8 when a byte-order mark is present, otherwise
     * the workspace's declared charset with a UTF-8 fallback.
     * <p>
     * When a mark is present its three bytes are consumed and not reset, so the reader begins after it;
     * when it is absent the stream is reset to the start.
     * </p>
     *
     * @param file the file, for its declared charset
     * @param input the buffered stream, positioned at the start
     * @return the charset name to decode with
     * @throws Exception if reading the mark fails
     */
    private static String detectCharset(IFile file, BufferedInputStream input) throws Exception
    {
        input.mark(BOM_LENGTH);
        byte[] bom = new byte[BOM_LENGTH];
        int read = input.read(bom, 0, BOM_LENGTH);
        boolean hasBom = read == BOM_LENGTH
            && (bom[0] & 0xFF) == 0xEF && (bom[1] & 0xFF) == 0xBB && (bom[2] & 0xFF) == 0xBF;
        if (!hasBom)
        {
            input.reset();
        }
        if (hasBom)
        {
            return "UTF-8"; //$NON-NLS-1$
        }
        try
        {
            return file.getCharset();
        }
        catch (Exception e)
        {
            return "UTF-8"; //$NON-NLS-1$
        }
    }

    /**
     * Strips everything up to and including {@code /src/} from a path, leaving the module path relative
     * to {@code src/}.
     *
     * @param path a full workspace or EMF path; may be <code>null</code>
     * @return the part after {@code /src/}, the literal {@code Module not recognised} for a <code>null</code>
     *         path, or the path unchanged when it has no {@code /src/}
     */
    public static String extractModulePath(String path)
    {
        if (path == null)
        {
            return "Module not recognised"; //$NON-NLS-1$
        }
        int index = path.indexOf("/src/"); //$NON-NLS-1$
        if (index >= 0)
        {
            return path.substring(index + SRC_MARKER_LENGTH);
        }
        return path;
    }

    /**
     * Gives the 1-based starting line of an object's source.
     *
     * @param eObject the model object; may be <code>null</code>
     * @return the starting line, or 0 when there is no object or no node behind it
     */
    public static int getStartLine(EObject eObject)
    {
        if (eObject == null)
        {
            return 0;
        }
        ICompositeNode node = NodeModelUtils.findActualNodeFor(eObject);
        return node == null ? 0 : node.getStartLine();
    }

    /**
     * Gives the 1-based ending line of an object's source.
     *
     * @param eObject the model object; may be <code>null</code>
     * @return the ending line, or 0 when there is no object or no node behind it
     */
    public static int getEndLine(EObject eObject)
    {
        if (eObject == null)
        {
            return 0;
        }
        ICompositeNode node = NodeModelUtils.findActualNodeFor(eObject);
        return node == null ? 0 : node.getEndLine();
    }

    /**
     * Gives the raw source slice behind an object, including whatever surrounding whitespace and
     * comments its node captured.
     *
     * @param eObject the model object; may be <code>null</code>
     * @return the source text, or <code>null</code> when there is no object or no node behind it
     */
    public static String getSourceText(EObject eObject)
    {
        if (eObject == null)
        {
            return null;
        }
        ICompositeNode node = NodeModelUtils.findActualNodeFor(eObject);
        return node == null ? null : node.getText();
    }

    /**
     * Builds the "method not found" response that lists the module's available methods.
     *
     * @param module the module the method was looked for in
     * @param modulePath the module path, for the message
     * @param methodName the method name that was not found
     * @return the response text
     */
    public static String buildMethodNotFoundResponse(Module module, String modulePath, String methodName)
    {
        EList<Method> methods = module.allMethods();
        StringBuilder builder = new StringBuilder();
        builder.append("Error: no method called '").append(methodName).append("' not found in ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(modulePath).append("\n\n"); //$NON-NLS-1$
        builder.append("**Methods in this module** (").append(methods.size()).append("):\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        for (Method method : methods)
        {
            builder.append("- ").append(method.getName()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return builder.toString();
    }

    /**
     * Builds a method signature: the keyword, the name, the parenthesized parameter list, and
     * {@code Export} when the method is exported.
     * <p>
     * Because the parameter list comes from {@link #buildParamsString(Method)}, a method with no
     * parameters renders with a dash inside the parentheses, for example {@code Procedure Foo(-)}. That
     * is the established output and is reproduced deliberately. No {@code Async} prefix and no pragmas or
     * directives are added here; callers do that off the model.
     * </p>
     *
     * @param method the method
     * @return the signature text
     */
    public static String buildSignature(Method method)
    {
        StringBuilder builder = new StringBuilder();
        builder.append(method instanceof Function ? "Function " : "Procedure "); //$NON-NLS-1$ //$NON-NLS-2$
        builder.append(method.getName());
        builder.append("("); //$NON-NLS-1$
        builder.append(buildParamsString(method));
        builder.append(")"); //$NON-NLS-1$
        if (method.isExport())
        {
            builder.append(" Export"); //$NON-NLS-1$
        }
        return builder.toString();
    }

    /**
     * Builds a method's parameter list: each parameter in order, comma-separated, with {@code Val} in
     * front of a by-value parameter and {@code = <default>} after one that has a default.
     *
     * @param method the method
     * @return the parameter list, or the literal {@code -} when the method has no parameters
     */
    public static String buildParamsString(Method method)
    {
        EList<FormalParam> params = method.getFormalParams();
        if (params == null || params.isEmpty())
        {
            return "-"; //$NON-NLS-1$
        }
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (FormalParam param : params)
        {
            if (!first)
            {
                builder.append(", "); //$NON-NLS-1$
            }
            first = false;
            if (param.isByValue())
            {
                builder.append("Val "); //$NON-NLS-1$
            }
            builder.append(param.getName());
            EObject defaultValue = param.getDefaultValue();
            if (defaultValue != null)
            {
                String defaultText = getSourceText(defaultValue);
                if (defaultText != null)
                {
                    builder.append(" = ").append(defaultText.trim()); //$NON-NLS-1$
                }
            }
        }
        return builder.toString();
    }

    /**
     * Finds the innermost {@code #Region} that encloses a 1-based line.
     * <p>
     * The {@code #Region} header line and the {@code #EndRegion} line both count as inside their region.
     * </p>
     *
     * @param allLines the module's lines
     * @param targetLine the 1-based line to locate
     * @return the innermost enclosing region name, or <code>null</code> when the line is inside no
     *         region, when {@code allLines} is <code>null</code>, or when {@code targetLine} is below 1
     */
    public static String findRegionForLine(List<String> allLines, int targetLine)
    {
        if (allLines == null || targetLine < 1)
        {
            return null;
        }
        List<String> stack = new ArrayList<>();
        for (int i = 0; i < allLines.size(); i++)
        {
            int lineNum = i + 1;
            String line = allLines.get(i);
            java.util.regex.Matcher startMatcher = REGION_START_PATTERN.matcher(line);
            if (startMatcher.find())
            {
                stack.add(startMatcher.group(1));
                if (lineNum >= targetLine)
                {
                    return stack.get(stack.size() - 1);
                }
                continue;
            }
            if (REGION_END_PATTERN.matcher(line).find())
            {
                if (lineNum >= targetLine && !stack.isEmpty())
                {
                    // The #EndRegion line still belongs to the region it closes.
                    return stack.get(stack.size() - 1);
                }
                if (!stack.isEmpty())
                {
                    stack.remove(stack.size() - 1);
                }
                continue;
            }
            if (lineNum >= targetLine)
            {
                return stack.isEmpty() ? null : stack.get(stack.size() - 1);
            }
        }
        return null;
    }

    /**
     * The result of resolving a module reference: a {@code src/}-relative path when it resolved, or a
     * hint explaining why it did not.
     * <p>
     * The fields are exposed without defensive copies; each result is used by a single call.
     * </p>
     */
    public static final class ModulePathResolution
    {
        private final String path;

        private final String hint;

        private ModulePathResolution(String path, String hint)
        {
            this.path = path;
            this.hint = hint;
        }

        /**
         * Builds a resolved result.
         *
         * @param path the {@code src/}-relative path
         * @return the result
         */
        static ModulePathResolution resolved(String path)
        {
            return new ModulePathResolution(path, null);
        }

        /**
         * Builds an unresolved result.
         *
         * @param hint why it did not resolve
         * @return the result
         */
        static ModulePathResolution unresolved(String hint)
        {
            return new ModulePathResolution(null, hint);
        }

        /**
         * @return <code>true</code> when a path was produced
         */
        public boolean isResolved()
        {
            return path != null;
        }

        /**
         * @return the {@code src/}-relative path, or <code>null</code> when it did not resolve
         */
        public String getPath()
        {
            return path;
        }

        /**
         * @return the failure hint, or <code>null</code> when it resolved
         */
        public String getHint()
        {
            return hint;
        }
    }
}
