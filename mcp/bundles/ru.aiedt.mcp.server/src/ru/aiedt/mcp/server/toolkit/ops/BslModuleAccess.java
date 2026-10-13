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
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import ru.aiedt.mcp.server.support.MetadataTypeCatalog.MetadataTypeInfo;
import ru.aiedt.mcp.server.support.modules.ModuleSources;
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
     * The header may open with the {@code Async} modifier.
     */
    public static final Pattern METHOD_START_PATTERN = Pattern.compile(
        "^\\s*(?:(?:\u0410\u0441\u0438\u043D\u0445|Async)\\s+)?(?:\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430|\u0424\u0443\u043D\u043A\u0446\u0438\u044F|Procedure|Function)\\s+(\\S+?)\\s*\\((.*)$", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Matches the line that ends a method. */
    public static final Pattern METHOD_END_PATTERN = Pattern.compile(
        "^\\s*(?:\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B|\u041A\u043E\u043D\u0435\u0446\u0424\u0443\u043D\u043A\u0446\u0438\u0438|EndProcedure|EndFunction)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Matches a header that begins a function, telling it apart from a procedure. */
    public static final Pattern FUNC_KEYWORD_PATTERN = Pattern.compile(
        "^\\s*(?:(?:\u0410\u0441\u0438\u043D\u0445|Async)\\s+)?(?:\u0424\u0443\u043D\u043A\u0446\u0438\u044F|Function)\\s", //$NON-NLS-1$
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
     * disk wins; more than one existing is an ambiguity the caller is told to disambiguate. This is the
     * set of names the addressing knows, and the kind table below adds no name outside it.
     */
    static final String[] OBJECT_MODULE_FILE_NAMES = {
        "Module.bsl", //$NON-NLS-1$
        "ManagerModule.bsl", //$NON-NLS-1$
        "ObjectModule.bsl", //$NON-NLS-1$
        "RecordSetModule.bsl", //$NON-NLS-1$
        "ValueManagerModule.bsl", //$NON-NLS-1$
        "CommandModule.bsl" //$NON-NLS-1$
    };

    /**
     * The module files the configuration root owns, read off the {@code Configuration} model: the
     * session module, the external-connection module, the managed-application module and the
     * ordinary-application module. The root is no kind in {@link MetadataTypeCatalog}, so this list
     * is the root's own.
     */
    private static final String[] ROOT_MODULE_FILE_NAMES = {
        "SessionModule.bsl", //$NON-NLS-1$
        "ExternalConnectionModule.bsl", //$NON-NLS-1$
        "ManagedApplicationModule.bsl", //$NON-NLS-1$
        "OrdinaryApplicationModule.bsl" //$NON-NLS-1$
    };

    /** The directory the configuration root's own modules live in, outside every kind directory. */
    private static final String CONFIGURATION_ROOT_DIRECTORY = "Configuration"; //$NON-NLS-1$

    /** The module files of an object that owns both its own module and a manager module. */
    private static final List<String> OBJECT_AND_MANAGER_MODULE_FILES =
        List.of("ObjectModule.bsl", "ManagerModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

    /** The module files of a register: its record set and its manager. */
    private static final List<String> RECORD_SET_AND_MANAGER_MODULE_FILES =
        List.of("RecordSetModule.bsl", "ManagerModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

    /** The module files of a constant: its value manager and its manager. */
    private static final List<String> VALUE_MANAGER_AND_MANAGER_MODULE_FILES =
        List.of("ValueManagerModule.bsl", "ManagerModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$

    /** The one module file of an object that owns a manager module only. */
    private static final List<String> MANAGER_MODULE_FILE = List.of("ManagerModule.bsl"); //$NON-NLS-1$

    /** The one module file of an object that owns a single module. */
    private static final List<String> SINGLE_MODULE_FILE = List.of("Module.bsl"); //$NON-NLS-1$

    /** The one module file of an object that owns its own module only. */
    private static final List<String> OBJECT_MODULE_FILE = List.of("ObjectModule.bsl"); //$NON-NLS-1$

    /** The one module file of a command. */
    private static final List<String> COMMAND_MODULE_FILE = List.of("CommandModule.bsl"); //$NON-NLS-1$

    /** The one module file of a sequence. */
    private static final List<String> RECORD_SET_MODULE_FILE = List.of("RecordSetModule.bsl"); //$NON-NLS-1$

    /** The module files of a table of an external data source. */
    private static final List<String> TABLE_MODULE_FILES =
        List.of("ObjectModule.bsl", "RecordSetModule.bsl", "ManagerModule.bsl"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** The folder under an object's own folder where the object's forms live. */
    private static final String FORMS_FOLDER = "Forms"; //$NON-NLS-1$

    /** The folder under an object's own folder where the object's commands live. */
    private static final String COMMANDS_FOLDER = "Commands"; //$NON-NLS-1$

    /** The folder under an external data source where its tables live. */
    private static final String TABLES_FOLDER = "Tables"; //$NON-NLS-1$

    /** The folder under an external data source where its cubes live. */
    private static final String CUBES_FOLDER = "Cubes"; //$NON-NLS-1$

    /** The folder under a cube where its dimension tables live. */
    private static final String DIMENSION_TABLES_FOLDER = "DimensionTables"; //$NON-NLS-1$

    /** The folder under a calculation register where its recalculations live. */
    private static final String RECALCULATIONS_FOLDER = "Recalculations"; //$NON-NLS-1$

    /**
     * The {@code src/} folder of the kinds the catalog holds no directory name for.
     * <p>
     * A bot, an integration service and a web socket client each own one module file and no forms.
     * The catalog leaves them without a directory because that one name answers for forms too, and a
     * form path for these kinds is a path to a folder that never exists. Module addressing is the
     * only caller that needs their folder, so the three names live here. They are the folder names
     * the platform's XML support places the sources of those kinds by
     * ({@code BaseXmlExportFileSystemSupport} of {@code com._1c.g5.v8.dt.export.xml}).
     * </p>
     */
    private static final Map<MetadataTypeInfo, String> MODULE_FOLDERS_BESIDE_THE_CATALOG = Map.of(
        MetadataTypeInfo.BOT, "Bots", //$NON-NLS-1$
        MetadataTypeInfo.INTEGRATION_SERVICE, "IntegrationServices", //$NON-NLS-1$
        MetadataTypeInfo.WEB_SOCKET_CLIENT, "WebSocketClients"); //$NON-NLS-1$

    /**
     * The module files each kind of metadata owns, and the levels that kind holds below its objects.
     * <p>
     * Taken from the {@code mdclass} model of the target platform ({@code com._1c.g5.v8.dt.metadata}):
     * a kind's row holds one file per {@code get*Module()} property its class declares, and its levels
     * are the collections the model gives it - the forms and commands of most kinds, the tables and
     * cubes of an external data source, the recalculations of a calculation register. A kind with a
     * row whose files are empty owns no module of its own, so no new module file is created for it,
     * while its levels stay reachable.
     * </p>
     */
    private static final Map<MetadataTypeInfo, KindModules> KIND_MODULES = kindModuleTable();

    /**
     * The levels of the layout below an object: the folder a path spells below the object's own
     * folder, the name that folder stands for and the module files the level owns.
     * <p>
     * Taken from the same {@code mdclass} model: a table of an external data source owns an object, a
     * record set and a manager module and holds forms and commands; a dimension table of a cube owns
     * an object and a manager module and holds forms and commands; a cube owns a record set and a
     * manager module and holds forms, commands and dimension tables; a recalculation of a calculation
     * register owns a record set module alone. The folders are the ones the platform writes those
     * sources under ({@code BaseXmlExportFileSystemSupport} of
     * {@code com._1c.g5.v8.dt.export.xml}).
     * </p>
     */
    private static final Map<String, Level> NESTED_LEVELS = nestedLevelTable();

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
        /** The model changed while the references were being resolved, so they cannot be trusted. */
        MODEL_MOVED,
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
            case MODEL_MOVED:
                return "the model changed while the module's types were being computed, so a " //$NON-NLS-1$
                    + "position without a type here may answer with one after the build settles - " //$NON-NLS-1$
                    + "ask again"; //$NON-NLS-1$
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
     * traversal check - a reading caller then finds the file missing and says so, and a writing
     * caller runs {@link #checkModulePathForWrite} over the path before anything is read or written,
     * which is where a path outside the layout is refused. A reference with no separator is treated
     * as a dot-separated module FQN and resolved against the project.
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
            // Already a real path. Reads take it verbatim; writes validate it separately.
            return ModulePathResolution.resolved(input);
        }
        return resolveFqnToModulePath(project, input);
    }

    /**
     * The canonical form of a module path whose first segment spells a metadata kind by any of its
     * names but the folder name.
     * <p>
     * {@code CommonModule/X/Module.bsl} answers {@code CommonModules/X/Module.bsl}, and so do
     * {@code catalogs/X/Module.bsl} and the Russian spelling of a kind; the rest of the path is
     * object names and is copied through untouched. A path that already opens with its kind folder,
     * or that names no kind, answers <code>null</code>.
     * </p>
     *
     * @param modulePath the path to canonicalize; may be <code>null</code>
     * @return the canonical path, or <code>null</code> when the path needs no correction
     */
    public static String canonicalModulePathOf(String modulePath)
    {
        List<String> segments = pathSegments(modulePath);
        if (segments.isEmpty())
        {
            return null;
        }
        MetadataTypeCatalog.MetadataTypeInfo kind = MetadataTypeCatalog.resolve(segments.get(0));
        String folder = moduleFolderOf(kind);
        if (folder == null || folder.equals(segments.get(0)))
        {
            return null;
        }
        StringBuilder canonical = new StringBuilder(folder);
        for (int i = 1; i < segments.size(); i++)
        {
            canonical.append('/').append(segments.get(i));
        }
        return canonical.toString();
    }

    /**
     * Whether a module path may be written, and what stands in the way when it may not.
     * <p>
     * A write used to answer success with a path whose first segment was not the object's own
     * folder - {@code CommonModule/Name/Module.bsl} instead of {@code CommonModules/Name/Module.bsl} -
     * and leave a file in a folder no object reads. The path is now judged against the layout
     * before anything is read or written, a dry run included:
     * </p>
     * <ul>
     * <li>the separators are forward slashes, the path is relative and climbs no parent;</li>
     * <li>the first segment is a kind folder of {@link MetadataTypeCatalog} or the configuration
     * root's own folder; a segment that names the kind rather than the folder is refused with the
     * canonical path in full, and a kind that owns no modules at all is refused with that said;</li>
     * <li>the place is found by walking the path: what sits between the kind folder and the file is a
     * level of the layout below an object - {@code Forms}, {@code Commands}, and the nested objects
     * of the kinds that have them, such as the tables and cubes of an external data source - and each
     * level is judged against the one above it. A folder the level above does not hold, a level under
     * a place that holds none, and a kind that owns no module of its own, are refused with the levels
     * that place holds, and so is a path of even length, which puts the file where the layout has an
     * object folder;</li>
     * <li>when the file is new, every folder of the path already exists - a module is written only
     * for an object, a form or a command the configuration already holds - and the file name is a
     * module name that the place the path reaches owns: a common module takes {@code Module.bsl}
     * alone, a catalog takes {@code ObjectModule.bsl} and {@code ManagerModule.bsl}, a register takes
     * {@code RecordSetModule.bsl} with its manager module, and a table of an external data source
     * takes its object, record set and manager modules.</li>
     * </ul>
     * <p>
     * An existing file is a fact of the project: the folder and name checks guard what a write
     * creates, so they step aside for a file that is already there. That check stands before every
     * refusal that judges the kind, and a path naming a file the project holds is written whatever
     * folder it opens with. A module a provider holds is an address, not a file, and its caller
     * never asks this question.
     * </p>
     *
     * @param project the project the path is written in; the folder checks are skipped for
     *            <code>null</code>
     * @param modulePath the path under {@code src/}, with or without a leading {@code src/}
     * @param fileExists whether the file the path names is already in the project
     * @return <code>null</code> when the path may be written, or the {@code Error: ...} refusal
     */
    public static String checkModulePathForWrite(IProject project, String modulePath,
        boolean fileExists)
    {
        if (modulePath == null || modulePath.isEmpty())
        {
            return null;
        }
        if (modulePath.indexOf('\\') >= 0)
        {
            return "Error: modulePath for a write has to use forward slashes. " //$NON-NLS-1$
                + "The same path is '" + modulePath.replace('\\', '/') + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (modulePath.startsWith("/") //$NON-NLS-1$
            || modulePath.length() > 1 && modulePath.charAt(1) == ':')
        {
            return "Error: modulePath has to be a path under the project's src/ folder, " //$NON-NLS-1$
                + "not an absolute path: '" + modulePath + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (modulePath.contains("..")) //$NON-NLS-1$
        {
            return "Error: modulePath is not allowed to contain '..'"; //$NON-NLS-1$
        }
        List<String> segments = pathSegments(modulePath);
        if (segments.size() < 2)
        {
            return "Error: modulePath has to name a module file under its kind folder, such as " //$NON-NLS-1$
                + "'CommonModules/MyModule/Module.bsl' or 'Catalogs/MyCatalog/ObjectModule.bsl'."; //$NON-NLS-1$
        }
        if (fileExists)
        {
            // The file is already in the project: writing it back creates no folder and puts no file
            // where no object reads, so the kind questions below do not apply to it.
            return null;
        }
        String first = segments.get(0);
        MetadataTypeCatalog.MetadataTypeInfo kind = MetadataTypeCatalog.resolve(first);
        boolean configurationRoot =
            kind == null && CONFIGURATION_ROOT_DIRECTORY.equalsIgnoreCase(first);
        if (kind == null && !configurationRoot)
        {
            StringBuilder refusal = new StringBuilder("Error: '" + first //$NON-NLS-1$
                + "' is not a metadata kind folder under src/. Module files live in a kind folder: " //$NON-NLS-1$
                + "'CommonModules/<name>/Module.bsl', 'Catalogs/<name>/ObjectModule.bsl'."); //$NON-NLS-1$
            List<String> near = MetadataTypeCatalog.findSimilarDirectoryNames(first, 3);
            if (!near.isEmpty())
            {
                refusal.append(" Closest kind folders: ").append(String.join(", ", near)).append('.'); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return refusal.toString();
        }
        String folder = moduleFolderOf(kind);
        if (kind != null && folder == null)
        {
            return "Error: metadata kind '" + kind.getEnglishSingular() + "' owns no module files, " //$NON-NLS-1$ //$NON-NLS-2$
                + "so '" + modulePath + "' names no module of the layout. A module write needs a " //$NON-NLS-1$ //$NON-NLS-2$
                + "kind folder such as CommonModules or Catalogs."; //$NON-NLS-1$
        }
        if (kind != null && !folder.equals(first))
        {
            return "Error: '" + first + "' is not the folder this layout uses. The canonical path " //$NON-NLS-1$ //$NON-NLS-2$
                + "is '" + canonicalModulePathOf(modulePath) + "'."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        Ownership owner = ownershipOf(kind, segments, modulePath);
        if (owner.refusal() != null)
        {
            return owner.refusal();
        }
        if (project == null)
        {
            return null;
        }
        MissingFolder missingFolder = firstMissingFolder(project, segments);
        if (missingFolder != null)
        {
            return "Error: src/" + missingFolder.folder() + " does not exist: " //$NON-NLS-1$ //$NON-NLS-2$
                + missingFolder.reason()
                + " A module is written only where the object it belongs to already exists; " //$NON-NLS-1$
                + "use list_modules to find the exact path."; //$NON-NLS-1$
        }
        String fileName = segments.get(segments.size() - 1);
        List<String> allowed = owner.modules().objectModules();
        if (allowed.isEmpty())
        {
            return "Error: metadata kind '" + kind.getEnglishSingular() + "' owns no module files of " //$NON-NLS-1$ //$NON-NLS-2$
                + "its own, so '" + modulePath + "' names no module of the layout."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!allowed.contains(fileName))
        {
            return "Error: '" + fileName + "' is not a module file of this place. " //$NON-NLS-1$ //$NON-NLS-2$
                + "The module files here are: " + String.join(", ", allowed) + '.'; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Splits a module path into its segments: both slashes separate, a leading {@code src/} is
     * dropped, and empty or current-directory segments are dropped rather than refused.
     *
     * @param modulePath the path; may be <code>null</code>
     * @return the segments, at least the file name; empty for a <code>null</code> or blank path
     */
    private static List<String> pathSegments(String modulePath)
    {
        List<String> segments = new ArrayList<>();
        if (modulePath == null || modulePath.isEmpty())
        {
            return segments;
        }
        for (String token : modulePath.replace('\\', '/').split("/")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            if (token.isEmpty() || token.equals(".")) //$NON-NLS-1$
            {
                continue;
            }
            segments.add(token);
        }
        if (segments.size() > 1 && "src".equalsIgnoreCase(segments.get(0))) //$NON-NLS-1$
        {
            segments.remove(0);
        }
        return segments;
    }

    /**
     * The first folder of a module path the project does not hold, with a reason naming what is
     * missing: an object, a form or a command of the configuration.
     *
     * @param project the project
     * @param segments the path segments, a kind folder first and a file name last
     * @return the missing folder with its reason, or <code>null</code> when every folder is there
     */
    private static MissingFolder firstMissingFolder(IProject project, List<String> segments)
    {
        StringBuilder cumulative = new StringBuilder();
        for (int i = 0; i < segments.size() - 1; i++)
        {
            if (i > 0)
            {
                cumulative.append('/');
            }
            cumulative.append(segments.get(i));
            if (!folderExistsInProject(project, cumulative.toString()))
            {
                return new MissingFolder(cumulative.toString(),
                    missingFolderReason(segments, i));
            }
        }
        return null;
    }

    /**
     * Says what a missing path folder stands for, from the segment ahead of it: the object folder
     * of a kind, an object a level holds such as a form, a command or a table, or nothing the layout
     * knows.
     *
     * @param segments the path segments
     * @param missingAt the index of the first missing folder's own segment
     * @return the reason phrase, closed with a semicolon
     */
    private static String missingFolderReason(List<String> segments, int missingAt)
    {
        if (missingAt == 1)
        {
            return "the project has no object '" + segments.get(1) + "' of this kind;"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (missingAt >= 2)
        {
            Level level = nestedLevel(segments.get(missingAt - 1));
            if (level != null)
            {
                return "the object holds no " + level.singular() + " '" + segments.get(missingAt) //$NON-NLS-1$ //$NON-NLS-2$
                    + "';"; //$NON-NLS-1$
            }
        }
        return "the folder is not part of this project's layout;"; //$NON-NLS-1$
    }

    /**
     * Tells whether a {@code src/}-relative folder is in the project.
     *
     * @param project the project; may be <code>null</code>
     * @param relativePath the folder under {@code src/}
     * @return <code>true</code> when the workspace holds the folder; <code>false</code> on any error
     */
    private static boolean folderExistsInProject(IProject project, String relativePath)
    {
        if (project == null)
        {
            return false;
        }
        try
        {
            return project.getFolder(new Path("src").append(relativePath)).exists(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * The place a module path reaches and the file names it owns.
     */
    private static final class Ownership
    {
        private final KindModules modules;

        private final String refusal;

        private Ownership(KindModules modules, String refusal)
        {
            this.modules = modules;
            this.refusal = refusal;
        }

        /**
         * @param modules the module files and levels the place owns
         * @return a path that reached a place
         */
        static Ownership of(KindModules modules)
        {
            return new Ownership(modules, null);
        }

        /**
         * @param refusal the reason the path reaches no place of the layout
         * @return a path that is refused
         */
        static Ownership refused(String refusal)
        {
            return new Ownership(null, refusal);
        }

        /** @return what the place owns; <code>null</code> when the path is refused */
        KindModules modules()
        {
            return modules;
        }

        /** @return the refusal, or <code>null</code> when the path reaches a place */
        String refusal()
        {
            return refusal;
        }
    }

    /**
     * Walks a module path from its kind folder down to the file and answers what owns that file.
     * <p>
     * Below an object's own folder the layout alternates: a level folder, then the name of the object
     * that level holds, then another level folder. Every segment in that part is therefore judged
     * against the levels the level above it holds, and the file name at the end against the level the
     * path reaches - not against the kind it opened with.
     * </p>
     *
     * @param kind the kind the path opens with, or <code>null</code> for the configuration root
     * @param segments the path segments, a kind folder first and a file name last
     * @param modulePath the caller's path, for the refusal text
     * @return the place the path reaches, or the refusal
     */
    private static Ownership ownershipOf(MetadataTypeInfo kind, List<String> segments,
        String modulePath)
    {
        if (kind == null)
        {
            // The configuration root is no kind, so nothing below its own folder can be judged
            // against a kind: the addressing list applies there as it did before the kind table.
            List<String> rootFiles =
                segments.size() == 2 && CONFIGURATION_ROOT_DIRECTORY.equalsIgnoreCase(segments.get(0))
                    ? List.of(ROOT_MODULE_FILE_NAMES) : List.of(OBJECT_MODULE_FILE_NAMES);
            return Ownership.of(new KindModules(rootFiles, List.of()));
        }
        String holder = kind.getEnglishSingular();
        KindModules modules = kindModules(kind);
        if (segments.size() % 2 == 0)
        {
            // Every level folder below a kind comes with the name of the object it holds, and the path
            // ends in a module file, so a path of even length puts the file where the layout has an
            // object folder and names no module.
            return Ownership.refused("Error: '" + modulePath //$NON-NLS-1$
                + "' names no module of the layout: below a kind folder a path alternates a level " //$NON-NLS-1$
                + "folder and the name of the object it holds, and ends in a module file."); //$NON-NLS-1$
        }
        // The loop steps over the name of an object that belongs to the current holder, and judges the
        // level folder the path spells after it. Its own file's folder is the segment before the file.
        for (int i = 3; i < segments.size(); i += 2)
        {
            String segment = segments.get(i - 1);
            Level level = nestedLevel(segment);
            if (level == null || modules == null || !modules.holds(segment))
            {
                return Ownership.refused(
                    nestedFolderRefusal(segment, level, holder, modules, modulePath));
            }
            holder = level.name();
            modules = level.modules();
        }
        return Ownership.of(modules == null ? new KindModules(List.of(), List.of()) : modules);
    }

    /**
     * The levels of the layout below an object.
     *
     * @return the folder a path spells to the level it stands for, unmodifiable
     */
    static Map<String, Level> nestedLevels()
    {
        return NESTED_LEVELS;
    }

    /**
     * The level a folder name stands for.
     *
     * @param segment the folder a path spells; may be <code>null</code>
     * @return the level, or <code>null</code> when the layout knows no level under that name
     */
    static Level nestedLevel(String segment)
    {
        if (segment == null)
        {
            return null;
        }
        for (Map.Entry<String, Level> entry : NESTED_LEVELS.entrySet())
        {
            if (entry.getKey().equalsIgnoreCase(segment))
            {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Refuses a path whose folder is not a level the place above it holds.
     *
     * @param segment the folder the path spells
     * @param level the level that folder name stands for, or <code>null</code> when no level is
     *            spelled that way
     * @param holder the name of the place the path reached
     * @param modules what that place holds; may be <code>null</code>
     * @param modulePath the caller's path, for the refusal text
     * @return the refusal
     */
    private static String nestedFolderRefusal(String segment, Level level, String holder,
        KindModules modules, String modulePath)
    {
        StringBuilder refusal = new StringBuilder("Error: '" + segment //$NON-NLS-1$
            + "' is not a level of " + holder); //$NON-NLS-1$
        List<String> levels = modules == null ? List.of() : modules.nestedFolders();
        if (levels.isEmpty())
        {
            refusal.append(", which holds no "); //$NON-NLS-1$
            refusal.append(level == null ? "level of its own" : level.plural()); //$NON-NLS-1$
            refusal.append(", so '").append(modulePath).append("' names no module of the layout."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            refusal.append(". The levels here are: ").append(String.join(", ", levels)).append('.'); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return refusal.toString();
    }

    /**
     * The module files and nested levels a kind of metadata owns.
     *
     * @param kind the kind, or <code>null</code> for the configuration root, which is no kind
     * @return the kind's row, or <code>null</code> when the table has none for it
     */
    static KindModules kindModules(MetadataTypeInfo kind)
    {
        return kind == null ? null : KIND_MODULES.get(kind);
    }

    /**
     * The folder under {@code src/} a kind's module files live in.
     * <p>
     * The catalog's own directory when it has one, otherwise the folder
     * {@link #MODULE_FOLDERS_BESIDE_THE_CATALOG} records for the kind. A kind whose module folder
     * nothing knows owns no module file a caller may address by path.
     * </p>
     *
     * @param kind the kind, or <code>null</code> for the configuration root, which is no kind
     * @return the folder name, or <code>null</code> when the kind owns no module files of the layout
     */
    static String moduleFolderOf(MetadataTypeInfo kind)
    {
        if (kind == null)
        {
            return null;
        }
        String fromCatalog = kind.getDirectoryName();
        return fromCatalog != null ? fromCatalog : MODULE_FOLDERS_BESIDE_THE_CATALOG.get(kind);
    }

    /**
     * Builds the kind table.
     *
     * @return the kind to the module files and nested levels it owns, unmodifiable
     */
    private static Map<MetadataTypeInfo, KindModules> kindModuleTable()
    {
        Map<MetadataTypeInfo, KindModules> table = new EnumMap<>(MetadataTypeInfo.class);
        put(table, MetadataTypeInfo.CATALOG, OBJECT_AND_MANAGER_MODULE_FILES, true, true);
        put(table, MetadataTypeInfo.DOCUMENT, OBJECT_AND_MANAGER_MODULE_FILES, true, true);
        put(table, MetadataTypeInfo.COMMON_MODULE, SINGLE_MODULE_FILE, false, false);
        put(table, MetadataTypeInfo.INFORMATION_REGISTER, RECORD_SET_AND_MANAGER_MODULE_FILES, true, true);
        put(table, MetadataTypeInfo.ACCUMULATION_REGISTER, RECORD_SET_AND_MANAGER_MODULE_FILES, true, true);
        put(table, MetadataTypeInfo.ENUM, MANAGER_MODULE_FILE, true, true);
        put(table, MetadataTypeInfo.REPORT, OBJECT_AND_MANAGER_MODULE_FILES, true, true);
        put(table, MetadataTypeInfo.DATA_PROCESSOR, OBJECT_AND_MANAGER_MODULE_FILES, true, true);
        put(table, MetadataTypeInfo.EXCHANGE_PLAN, OBJECT_AND_MANAGER_MODULE_FILES, true, true);
        put(table, MetadataTypeInfo.BUSINESS_PROCESS, OBJECT_AND_MANAGER_MODULE_FILES, true, true);
        put(table, MetadataTypeInfo.TASK, OBJECT_AND_MANAGER_MODULE_FILES, true, true);
        put(table, MetadataTypeInfo.COMMON_COMMAND, COMMAND_MODULE_FILE, false, false);
        put(table, MetadataTypeInfo.COMMON_FORM, SINGLE_MODULE_FILE, false, false);
        put(table, MetadataTypeInfo.WEB_SERVICE, SINGLE_MODULE_FILE, false, false);
        put(table, MetadataTypeInfo.HTTP_SERVICE, SINGLE_MODULE_FILE, false, false);
        put(table, MetadataTypeInfo.CONSTANT, VALUE_MANAGER_AND_MANAGER_MODULE_FILES, false, false);
        put(table, MetadataTypeInfo.CHART_OF_CHARACTERISTIC_TYPES, OBJECT_AND_MANAGER_MODULE_FILES, true, true);
        put(table, MetadataTypeInfo.CHART_OF_ACCOUNTS, OBJECT_AND_MANAGER_MODULE_FILES, true, true);
        put(table, MetadataTypeInfo.CHART_OF_CALCULATION_TYPES, OBJECT_AND_MANAGER_MODULE_FILES, true, true);
        put(table, MetadataTypeInfo.ACCOUNTING_REGISTER, RECORD_SET_AND_MANAGER_MODULE_FILES, true, true);
        put(table, MetadataTypeInfo.CALCULATION_REGISTER, RECORD_SET_AND_MANAGER_MODULE_FILES,
            List.of(FORMS_FOLDER, COMMANDS_FOLDER, RECALCULATIONS_FOLDER));
        put(table, MetadataTypeInfo.DOCUMENT_JOURNAL, MANAGER_MODULE_FILE, true, true);
        put(table, MetadataTypeInfo.SEQUENCE, RECORD_SET_MODULE_FILE, false, false);
        put(table, MetadataTypeInfo.FILTER_CRITERION, MANAGER_MODULE_FILE, true, true);
        put(table, MetadataTypeInfo.SETTINGS_STORAGE, MANAGER_MODULE_FILE, true, false);
        put(table, MetadataTypeInfo.EXTERNAL_DATA_PROCESSOR, OBJECT_MODULE_FILE, true, false);
        put(table, MetadataTypeInfo.EXTERNAL_REPORT, OBJECT_MODULE_FILE, true, false);
        put(table, MetadataTypeInfo.BOT, SINGLE_MODULE_FILE, false, false);
        put(table, MetadataTypeInfo.INTEGRATION_SERVICE, SINGLE_MODULE_FILE, false, false);
        put(table, MetadataTypeInfo.WEB_SOCKET_CLIENT, SINGLE_MODULE_FILE, false, false);
        put(table, MetadataTypeInfo.EXTERNAL_DATA_SOURCE, List.of(),
            List.of(TABLES_FOLDER, CUBES_FOLDER));
        return Collections.unmodifiableMap(table);
    }

    /**
     * Builds the table of the levels below an object.
     *
     * @return the folder a path spells to the level it stands for, unmodifiable
     */
    private static Map<String, Level> nestedLevelTable()
    {
        Map<String, Level> table = new LinkedHashMap<>();
        table.put(FORMS_FOLDER,
            new Level("Form", "form", "forms", new KindModules(SINGLE_MODULE_FILE, List.of()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        table.put(COMMANDS_FOLDER,
            new Level("Command", "command", "commands", new KindModules(COMMAND_MODULE_FILE, List.of()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        table.put(TABLES_FOLDER, new Level("Table", "table", "tables", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new KindModules(TABLE_MODULE_FILES, List.of(FORMS_FOLDER, COMMANDS_FOLDER))));
        table.put(CUBES_FOLDER, new Level("Cube", "cube", "cubes", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new KindModules(RECORD_SET_AND_MANAGER_MODULE_FILES,
                List.of(FORMS_FOLDER, COMMANDS_FOLDER, DIMENSION_TABLES_FOLDER))));
        table.put(DIMENSION_TABLES_FOLDER, new Level("DimensionTable", "dimension table", //$NON-NLS-1$ //$NON-NLS-2$
            "dimension tables", //$NON-NLS-1$
            new KindModules(OBJECT_AND_MANAGER_MODULE_FILES, List.of(FORMS_FOLDER, COMMANDS_FOLDER))));
        table.put(RECALCULATIONS_FOLDER, new Level("Recalculation", "recalculation", //$NON-NLS-1$ //$NON-NLS-2$
            "recalculations", new KindModules(RECORD_SET_MODULE_FILE, List.of()))); //$NON-NLS-1$
        return Collections.unmodifiableMap(table);
    }

    /**
     * Adds one row to the kind table.
     *
     * @param table the table being built
     * @param kind the kind
     * @param objectModules the kind's own module files
     * @param forms whether the kind holds forms
     * @param commands whether the kind holds commands
     */
    private static void put(Map<MetadataTypeInfo, KindModules> table, MetadataTypeInfo kind,
        List<String> objectModules, boolean forms, boolean commands)
    {
        List<String> levels = new ArrayList<>();
        if (forms)
        {
            levels.add(FORMS_FOLDER);
        }
        if (commands)
        {
            levels.add(COMMANDS_FOLDER);
        }
        put(table, kind, objectModules, levels);
    }

    /**
     * Adds one row to the kind table.
     *
     * @param table the table being built
     * @param kind the kind
     * @param objectModules the kind's own module files
     * @param nestedFolders the folders of the levels the kind holds below its objects
     */
    private static void put(Map<MetadataTypeInfo, KindModules> table, MetadataTypeInfo kind,
        List<String> objectModules, List<String> nestedFolders)
    {
        table.put(kind, new KindModules(objectModules, nestedFolders));
    }

    /**
     * The module files a place owns of its own, and the levels it holds below its objects.
     */
    static final class KindModules
    {
        private final List<String> objectModules;

        private final List<String> nestedFolders;

        /**
         * @param objectModules the file names of the place's own modules
         * @param nestedFolders the folders of the levels the place holds below its objects
         */
        KindModules(List<String> objectModules, List<String> nestedFolders)
        {
            this.objectModules = objectModules;
            this.nestedFolders = nestedFolders;
        }

        /** @return the file names of the place's own modules, empty when it owns none */
        List<String> objectModules()
        {
            return objectModules;
        }

        /** @return the folders of the levels the place holds, in the order a refusal names them */
        List<String> nestedFolders()
        {
            return nestedFolders;
        }

        /**
         * Tells whether this place holds a level under one folder name.
         *
         * @param folder the folder a path spells
         * @return <code>true</code> when the place holds such a level
         */
        boolean holds(String folder)
        {
            for (String held : nestedFolders)
            {
                if (held.equalsIgnoreCase(folder))
                {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * A level of the layout below an object.
     */
    static final class Level
    {
        private final String name;

        private final String singular;

        private final String plural;

        private final KindModules modules;

        /**
         * @param name the name the folder stands for, for refusal texts
         * @param singular the name in running text, for the reason a folder is missing
         * @param plural the name in the plural, for refusal texts
         * @param modules the module files and levels the level owns
         */
        Level(String name, String singular, String plural, KindModules modules)
        {
            this.name = name;
            this.singular = singular;
            this.plural = plural;
            this.modules = modules;
        }

        /** @return the name the folder stands for */
        String name()
        {
            return name;
        }

        /** @return the name of one object of the level, in running text */
        String singular()
        {
            return singular;
        }

        /** @return the plural of that name */
        String plural()
        {
            return plural;
        }

        /** @return the module files and levels the level owns */
        KindModules modules()
        {
            return modules;
        }
    }

    /** A folder of a module path the project does not hold, and why it matters. */
    private static final class MissingFolder
    {
        private final String folder;

        private final String reason;

        MissingFolder(String folder, String reason)
        {
            this.folder = folder;
            this.reason = reason;
        }

        /** @return the {@code src/}-relative folder that is not there */
        String folder()
        {
            return folder;
        }

        /** @return the phrase naming what is missing */
        String reason()
        {
            return reason;
        }
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
    static boolean isFormKeyword(String token)
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
     * Tells whether a {@code src/}-relative path names an existing file in a project, or a module
     * a provider holds, which has no file of its own and is addressed as if it had.
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
            return project.getFile(new Path("src").append(relativePath)).exists() //$NON-NLS-1$
                || ModuleSources.locate(project, relativePath) != null;
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
     * The text of a module named by its path or FQN, read from its file under the project's
     * {@code src/} folder.
     * <p>
     * {@link #resolveModulePath} answers a {@code src/}-relative path, so the file is looked up
     * under {@code src/}; a module that has no file yet answers null rather than an error, which
     * lets a caller about to create it treat it as empty.
     * </p>
     *
     * @param project the project that owns the module
     * @param modulePathOrFqn the module path under {@code src/} or its FQN
     * @return the module text, or <code>null</code> when the reference does not resolve or the
     *         module has no file
     * @throws Exception when the file is there and cannot be read
     */
    public static String readModuleIfPresent(IProject project, String modulePathOrFqn) throws Exception
    {
        ModulePathResolution resolved = resolveModulePath(project, modulePathOrFqn);
        if (project == null || !resolved.isResolved())
        {
            return null;
        }
        IFile file = project.getFile(new Path("src").append(resolved.getPath())); //$NON-NLS-1$
        boolean onDisk = file.getLocation() != null && file.getLocation().toFile().isFile();
        if (!file.exists() && !onDisk)
        {
            return null;
        }
        return readFileText(file);
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
