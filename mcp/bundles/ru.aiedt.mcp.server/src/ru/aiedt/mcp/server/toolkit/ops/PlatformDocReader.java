/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.resource.IEObjectDescription;

import com._1c.g5.v8.dt.bm.xtext.BmAwareResourceSetProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.Ctor;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.Method;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.Parameter;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeContainer;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.JsonUtils;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.MarkdownTableHelper;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Looks up platform documentation for 1C:Enterprise types, methods, properties and built-in
 * functions through the EDT mcore model. Searches by type name and optional member name; the answer
 * is rendered as Markdown.
 */
public final class PlatformDocReader implements IMcpTool
{
    public static final String NAME = "get_platform_documentation"; //$NON-NLS-1$

    private static final String CATEGORY_TYPE = "type"; //$NON-NLS-1$
    private static final String CATEGORY_BUILTIN = "builtin"; //$NON-NLS-1$
    private static final String MEMBER_ALL = "all"; //$NON-NLS-1$
    private static final String MEMBER_METHOD = "method"; //$NON-NLS-1$
    private static final String MEMBER_PROPERTY = "property"; //$NON-NLS-1$
    private static final String MEMBER_CONSTRUCTOR = "constructor"; //$NON-NLS-1$
    private static final String MEMBER_EVENT = "event"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `docs_lookup` `operation=get_platform_documentation`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Looks up 1C:Enterprise platform reference documentation: types, methods, " //$NON-NLS-1$
            + "properties, and global built-in functions. " //$NON-NLS-1$
            + "Usage: typeName='ValueTable', or typeName='Array' with memberName='Add'"; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("typeName", "Name of the platform type or symbol to look up (e.g. " //$NON-NLS-1$ //$NON-NLS-2$
                + "'ValueTable', 'Array', 'Structure'). Accepts either English or Russian names.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("category", "Kind of lookup to perform: 'type' (platform types such " //$NON-NLS-1$ //$NON-NLS-2$
                + "as ValueTable), 'builtin' (global built-in functions). Defaults to 'type'") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("memberName", "Keep only members whose name matches this text (method " //$NON-NLS-1$ //$NON-NLS-2$
                + "or property). Case-insensitive substring match. Example: 'Add', 'Insert', 'Count'") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("memberType", "Limit results to one member kind: 'method', 'property', " //$NON-NLS-1$ //$NON-NLS-2$
                + "'constructor', 'event', 'all'. Defaults to 'all'") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("projectName", "Name of the EDT project used to resolve the platform version. Optional.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", "Upper bound on the number of results returned. Defaults to 50") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("language", "Language for the returned text: 'en' (English) or 'ru' (Russian). Defaults to 'en'") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String typeName = JsonUtils.extractStringArgument(params, "typeName"); //$NON-NLS-1$
        if (typeName != null && !typeName.isEmpty())
        {
            return "doc-" + typeName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "platform-documentation.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String typeName = JsonUtils.extractStringArgument(params, "typeName"); //$NON-NLS-1$
        String category = JsonUtils.extractStringArgument(params, "category"); //$NON-NLS-1$
        String memberName = JsonUtils.extractStringArgument(params, "memberName"); //$NON-NLS-1$
        String memberType = JsonUtils.extractStringArgument(params, "memberType"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String limitStr = JsonUtils.extractStringArgument(params, "limit"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$

        if (typeName == null || typeName.isEmpty())
        {
            return "Error: the 'typeName' parameter is required"; //$NON-NLS-1$
        }

        if (category == null || category.isEmpty())
        {
            category = CATEGORY_TYPE;
        }
        if (memberType == null || memberType.isEmpty())
        {
            memberType = MEMBER_ALL;
        }
        if (language == null || language.isEmpty())
        {
            language = "en"; //$NON-NLS-1$
        }

        int limit = 50;
        if (limitStr != null && !limitStr.isEmpty())
        {
            try
            {
                limit = Math.min((int)Double.parseDouble(limitStr), 200);
            }
            catch (NumberFormatException e)
            {
                // keep the default
            }
        }

        boolean useRussian = "ru".equalsIgnoreCase(language); //$NON-NLS-1$

        switch (category.toLowerCase())
        {
        case CATEGORY_TYPE:
            return getTypeDocumentation(typeName, memberName, memberType, projectName, limit, useRussian);
        case CATEGORY_BUILTIN:
            return getBuiltinFunctionDocumentation(typeName, useRussian);
        default:
            return "Error: unrecognized category '" + category + "'. Supported values: 'type', 'builtin'"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private String getTypeDocumentation(String typeName, String memberName, String memberType,
        String projectName, int limit, boolean useRussian)
    {
        try
        {
            return UiSync.call(() -> getTypeDocumentationInternal(typeName, memberName, memberType,
                projectName, limit, useRussian));
        }
        catch (Exception e)
        {
            Activator.logError("Failed to build type documentation", e); //$NON-NLS-1$
            return "Error: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    private String getTypeDocumentationInternal(String typeName, String memberName, String memberType,
        String projectName, int limit, boolean useRussian)
    {
        Version version = getProjectVersion(projectName);
        if (version == null)
        {
            version = Version.LATEST;
        }

        IEObjectProvider.Registry registry = IEObjectProvider.Registry.INSTANCE;
        IEObjectProvider typeProvider = registry.get(McorePackage.Literals.TYPE, version);
        boolean typeProviderHasContent = false;
        if (typeProvider != null)
        {
            Iterable<IEObjectDescription> typeDes = typeProvider.getEObjectDescriptions(null);
            if (typeDes != null && typeDes.iterator().hasNext())
            {
                typeProviderHasContent = true;
            }
        }

        IEObjectProvider typeItemProvider = registry.get(McorePackage.Literals.TYPE_ITEM, version);
        if (!typeProviderHasContent)
        {
            typeProvider = typeItemProvider;
        }

        if (typeProvider == null)
        {
            return "Error: unable to obtain the type provider - make sure the EDT workspace is open."; //$NON-NLS-1$
        }

        Type foundType = null;
        List<String> availableTypes = new ArrayList<>();

        Iterable<IEObjectDescription> descriptions = typeProvider.getEObjectDescriptions(null);
        if (descriptions != null)
        {
            for (IEObjectDescription desc : descriptions)
            {
                String fullName = desc.getName().toString();
                String lastSegment = desc.getName().getLastSegment();

                if (availableTypes.size() < 30)
                {
                    availableTypes.add(lastSegment != null ? lastSegment : fullName);
                }

                if (fullName.equalsIgnoreCase(typeName)
                    || (lastSegment != null && lastSegment.equalsIgnoreCase(typeName)))
                {
                    EObject resolved = desc.getEObjectOrProxy();
                    if (resolved instanceof Type)
                    {
                        if (resolved.eIsProxy())
                        {
                            URI uri = desc.getEObjectURI();
                            try
                            {
                                ResourceSetImpl tempResourceSet = new ResourceSetImpl();
                                resolved = EcoreUtil.resolve(resolved, tempResourceSet);
                            }
                            catch (Exception e)
                            {
                                Activator.logError("Failed to resolve type proxy: " + uri, e); //$NON-NLS-1$
                            }
                        }
                        if (!resolved.eIsProxy())
                        {
                            foundType = (Type)resolved;
                            break;
                        }
                    }
                }
            }
        }

        if (foundType == null)
        {
            StringBuilder sb = new StringBuilder();
            sb.append("Error: unable to locate type: " + typeName + "\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("Known types (first " + availableTypes.size() + "):\n"); //$NON-NLS-1$ //$NON-NLS-2$
            if (availableTypes.isEmpty())
            {
                sb.append("(no types were found - the provider may be empty)\n"); //$NON-NLS-1$
            }
            else
            {
                for (String availType : availableTypes)
                {
                    sb.append("- " + availType + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (availableTypes.size() >= 30)
                {
                    sb.append("... (additional types not shown)\n"); //$NON-NLS-1$
                }
            }
            return sb.toString();
        }

        return buildTypeDocumentation(foundType, memberName, memberType, limit, useRussian);
    }

    private String buildTypeDocumentation(Type type, String memberName, String memberType, int limit,
        boolean useRussian)
    {
        StringBuilder sb = new StringBuilder();

        String displayName = useRussian ? type.getNameRu() : type.getName();
        String altName = useRussian ? type.getName() : type.getNameRu();
        sb.append("# " + (displayName != null ? displayName : "Unnamed")); //$NON-NLS-1$
        if (altName != null && !altName.equals(displayName))
        {
            sb.append(" / " + altName); //$NON-NLS-1$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        sb.append("**Type Summary:**\n"); //$NON-NLS-1$
        sb.append("- Supports iteration: " + (type.isIterable() ? "Yes" : "No") + "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("- Supports index access: " + (type.isIndexAccessible() ? "Yes" : "No") + "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("- Instantiable via New: " + (type.isCreatedByNewOperator() ? "Yes" : "No") + "\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        TypeContainer elementTypes = type.getCollectionElementTypes();
        if (elementTypes != null)
        {
            EList<TypeItem> elemTypesList = elementTypes.allTypes();
            if (elemTypesList != null && !elemTypesList.isEmpty())
            {
                sb.append("**Element types:** " //$NON-NLS-1$
                    + joinTypeNames(elemTypesList, useRussian, ", ") + "\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        int count = 0;

        if (shouldIncludeMemberType(memberType, MEMBER_CONSTRUCTOR))
        {
            EList<Ctor> ctors = type.getCtors();
            if (ctors != null && !ctors.isEmpty())
            {
                sb.append("## Available Constructors\n\n"); //$NON-NLS-1$
                for (int i = 0; i < ctors.size(); i++)
                {
                    if (count >= limit)
                    {
                        break;
                    }
                    appendCtorDocumentation(sb, ctors.get(i), i + 1, useRussian);
                    count++;
                }
                sb.append("\n"); //$NON-NLS-1$
            }
        }

        ContextDef contextDef = type.getContextDef();
        if (contextDef != null)
        {
            if (shouldIncludeMemberType(memberType, MEMBER_METHOD))
            {
                EList<Method> methods = contextDef.allMethods();
                if (methods != null && !methods.isEmpty())
                {
                    sb.append("## Available Methods\n\n"); //$NON-NLS-1$
                    for (Method method : methods)
                    {
                        if (count >= limit)
                        {
                            break;
                        }
                        String methodName = useRussian ? method.getNameRu() : method.getName();
                        if (memberName == null || matchesMemberName(methodName, memberName)
                            || matchesMemberName(method.getName(), memberName)
                            || matchesMemberName(method.getNameRu(), memberName))
                        {
                            appendMethodDocumentation(sb, method, useRussian);
                            count++;
                        }
                    }
                    sb.append("\n"); //$NON-NLS-1$
                }
            }

            if (shouldIncludeMemberType(memberType, MEMBER_PROPERTY))
            {
                EList<Property> properties = contextDef.allProperties();
                if (properties != null && !properties.isEmpty())
                {
                    sb.append("## Available Properties\n\n"); //$NON-NLS-1$
                    for (Property prop : properties)
                    {
                        if (count >= limit)
                        {
                            break;
                        }
                        String propName = useRussian ? prop.getNameRu() : prop.getName();
                        if (memberName == null || matchesMemberName(propName, memberName)
                            || matchesMemberName(prop.getName(), memberName)
                            || matchesMemberName(prop.getNameRu(), memberName))
                        {
                            appendPropertyDocumentation(sb, prop, useRussian);
                            count++;
                        }
                    }
                    sb.append("\n"); //$NON-NLS-1$
                }
            }
        }

        if (shouldIncludeMemberType(memberType, MEMBER_EVENT))
        {
            EList<Event> events = type.getEvents();
            if (events != null && !events.isEmpty())
            {
                sb.append("## Available Events\n\n"); //$NON-NLS-1$
                for (Event event : events)
                {
                    if (count >= limit)
                    {
                        break;
                    }
                    String eventName = useRussian ? event.getNameRu() : event.getName();
                    if (memberName == null || matchesMemberName(eventName, memberName)
                        || matchesMemberName(event.getName(), memberName)
                        || matchesMemberName(event.getNameRu(), memberName))
                    {
                        appendEventDocumentation(sb, event, useRussian);
                        count++;
                    }
                }
            }
        }

        if (count >= limit)
        {
            sb.append("\n*Output truncated to " + limit + " entries.*\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return sb.toString();
    }

    private String getBuiltinFunctionDocumentation(String functionName, boolean useRussian)
    {
        try
        {
            return UiSync.call(() -> getBuiltinFunctionDocumentationInternal(functionName, useRussian));
        }
        catch (Exception e)
        {
            Activator.logError("Failed to build built-in function documentation", e); //$NON-NLS-1$
            return "Error: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    private String getBuiltinFunctionDocumentationInternal(String functionName, boolean useRussian)
    {
        Version version = getProjectVersion(null);
        if (version == null)
        {
            version = Version.LATEST;
        }

        IEObjectProvider.Registry registry = IEObjectProvider.Registry.INSTANCE;
        IEObjectProvider methodProvider = registry.get(McorePackage.Literals.METHOD, version);
        if (methodProvider == null)
        {
            return "Error: unable to obtain the method provider - make sure the EDT workspace is open."; //$NON-NLS-1$
        }

        ResourceSet resourceSet = null;
        BmAwareResourceSetProvider resourceSetProvider = Activator.getDefault().getResourceSetProvider();
        IV8ProjectManager v8pm = Activator.getDefault().getV8ProjectManager();
        if (v8pm != null && resourceSetProvider != null)
        {
            for (IV8Project project : v8pm.getProjects())
            {
                resourceSet = resourceSetProvider.get(project.getProject());
                if (resourceSet != null)
                {
                    break;
                }
            }
        }

        Method foundMethod = null;
        List<String> availableMethods = new ArrayList<>();

        Iterable<IEObjectDescription> descriptions = methodProvider.getEObjectDescriptions(null);
        if (descriptions != null)
        {
            for (IEObjectDescription desc : descriptions)
            {
                String methodName = desc.getName().getLastSegment();
                if (methodName == null)
                {
                    methodName = desc.getName().toString();
                }

                if (availableMethods.size() < 30)
                {
                    availableMethods.add(methodName);
                }

                if (methodName.equalsIgnoreCase(functionName))
                {
                    EObject resolved = desc.getEObjectOrProxy();
                    if (resolved != null)
                    {
                        if (resolved.eIsProxy() && resourceSet != null)
                        {
                            resolved = EcoreUtil.resolve(resolved, resourceSet);
                        }
                        else if (resolved.eIsProxy())
                        {
                            ResourceSetImpl temp = new ResourceSetImpl();
                            resolved = EcoreUtil.resolve(resolved, temp);
                        }
                        if (resolved instanceof Method && !resolved.eIsProxy())
                        {
                            foundMethod = (Method)resolved;
                            break;
                        }
                    }
                }
            }
        }

        if (foundMethod == null)
        {
            StringBuilder sb = new StringBuilder();
            sb.append("Error: no built-in function named: " + functionName + "\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("Known global methods (first " + availableMethods.size() + "):\n"); //$NON-NLS-1$ //$NON-NLS-2$
            if (availableMethods.isEmpty())
            {
                sb.append("(no methods were found - the provider may be empty)\n"); //$NON-NLS-1$
            }
            else
            {
                for (String method : availableMethods)
                {
                    sb.append("- " + method + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (availableMethods.size() >= 30)
                {
                    sb.append("... (additional methods not shown)\n"); //$NON-NLS-1$
                }
            }
            return sb.toString();
        }

        return buildBuiltinMethodDocumentation(foundMethod, useRussian);
    }

    private String buildBuiltinMethodDocumentation(Method method, boolean useRussian)
    {
        StringBuilder sb = new StringBuilder();

        String displayName = useRussian ? method.getNameRu() : method.getName();
        String altName = useRussian ? method.getName() : method.getNameRu();
        sb.append("# " + (displayName != null ? displayName : "Unnamed")); //$NON-NLS-1$
        if (altName != null && !altName.equals(displayName))
        {
            sb.append(" / " + altName); //$NON-NLS-1$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        sb.append("**Kind:** Global built-in function\n\n"); //$NON-NLS-1$

        if (method.isRetVal())
        {
            sb.append("*Has a return value*\n\n"); //$NON-NLS-1$
        }
        else
        {
            sb.append("*Procedure - does not return a value*\n\n"); //$NON-NLS-1$
        }

        EList<ParamSet> paramSets = method.getParamSet();
        if (paramSets != null && !paramSets.isEmpty())
        {
            sb.append("## Arguments\n\n"); //$NON-NLS-1$
            for (int i = 0; i < paramSets.size(); i++)
            {
                if (paramSets.size() > 1)
                {
                    sb.append("### Signature " + (i + 1) + "\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                appendParamSetDocumentation(sb, paramSets.get(i), useRussian);
                sb.append("\n"); //$NON-NLS-1$
            }
        }
        else
        {
            sb.append("## Arguments\n\n*No arguments*\n\n"); //$NON-NLS-1$
        }

        EList<TypeItem> retValTypes = method.getRetValType();
        if (retValTypes != null && !retValTypes.isEmpty())
        {
            sb.append("## Returns\n\n"); //$NON-NLS-1$
            sb.append("**Return type:** " + String.join(" | ", collectTypeNames(retValTypes, useRussian)) //$NON-NLS-1$ //$NON-NLS-2$
                + "\n\n"); //$NON-NLS-1$
        }

        return sb.toString();
    }

    private Version getProjectVersion(String projectName)
    {
        if (projectName == null || projectName.isEmpty())
        {
            IV8ProjectManager v8pm = Activator.getDefault().getV8ProjectManager();
            if (v8pm != null)
            {
                for (IV8Project project : v8pm.getProjects())
                {
                    return project.getVersion();
                }
            }
            return null;
        }

        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project != null && project.exists())
            {
                IDtProjectManager dtpm = Activator.getDefault().getDtProjectManager();
                if (dtpm != null)
                {
                    IDtProject dtProject = dtpm.getDtProject(project);
                    if (dtProject != null)
                    {
                        IV8ProjectManager v8pm = Activator.getDefault().getV8ProjectManager();
                        if (v8pm != null)
                        {
                            IV8Project v8Project = v8pm.getProject(dtProject);
                            if (v8Project != null)
                            {
                                return v8Project.getVersion();
                            }
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Failed to resolve project version", e); //$NON-NLS-1$
        }
        return null;
    }

    private boolean shouldIncludeMemberType(String memberTypeFilter, String actualType)
    {
        if (memberTypeFilter == null || memberTypeFilter.isEmpty() || MEMBER_ALL.equals(memberTypeFilter))
        {
            return true;
        }
        return memberTypeFilter.equalsIgnoreCase(actualType);
    }

    private boolean matchesMemberName(String actualName, String filter)
    {
        if (actualName == null || filter == null)
        {
            return false;
        }
        return actualName.toLowerCase().contains(filter.toLowerCase());
    }

    private void appendCtorDocumentation(StringBuilder sb, Ctor ctor, int ctorNumber, boolean useRussian)
    {
        sb.append("### Ctor " + ctorNumber + "\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        EList<Parameter> params = ctor.getParams();
        if (params != null && !params.isEmpty())
        {
            sb.append("**Arguments:**\n"); //$NON-NLS-1$
            for (Parameter param : params)
            {
                appendParameterDocumentation(sb, param, useRussian);
            }
        }
        else
        {
            sb.append("*No arguments*\n"); //$NON-NLS-1$
        }
        sb.append("\n"); //$NON-NLS-1$
    }

    private void appendMethodDocumentation(StringBuilder sb, Method method, boolean useRussian)
    {
        String name = (useRussian && method.getNameRu() != null) ? method.getNameRu() : method.getName();
        String altName = useRussian ? method.getName() : method.getNameRu();

        sb.append("### " + (name != null ? MarkdownTableHelper.escapeMarkdown(name) : "Unnamed")); //$NON-NLS-1$
        if (altName != null && !altName.equals(name))
        {
            sb.append(" / " + MarkdownTableHelper.escapeMarkdown(altName)); //$NON-NLS-1$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        if (method.isRetVal())
        {
            sb.append("*Has a return value*\n\n"); //$NON-NLS-1$
        }

        EList<ParamSet> paramSets = method.getParamSet();
        if (paramSets != null && !paramSets.isEmpty())
        {
            for (int i = 0; i < paramSets.size(); i++)
            {
                ParamSet ps = paramSets.get(i);
                if (paramSets.size() > 1)
                {
                    sb.append("**Signature " + (i + 1) + ":**\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                appendParamSetDocumentation(sb, ps, useRussian);
            }
        }

        EList<TypeItem> retValTypes = method.getRetValType();
        if (retValTypes != null && !retValTypes.isEmpty())
        {
            sb.append("**Return type:** " + String.join(" | ", collectTypeNames(retValTypes, useRussian)) //$NON-NLS-1$ //$NON-NLS-2$
                + "\n"); //$NON-NLS-1$
        }

        sb.append("\n"); //$NON-NLS-1$
    }

    private void appendParamSetDocumentation(StringBuilder sb, ParamSet paramSet, boolean useRussian)
    {
        EList<Parameter> params = paramSet.getParams();
        if (params != null && !params.isEmpty())
        {
            sb.append("**Arguments:**\n"); //$NON-NLS-1$
            for (Parameter param : params)
            {
                appendParameterDocumentation(sb, param, useRussian);
            }
        }
    }

    private void appendParameterDocumentation(StringBuilder sb, Parameter param, boolean useRussian)
    {
        String paramName = (useRussian && param.getNameRu() != null) ? param.getNameRu() : param.getName();
        sb.append("- `" + (paramName != null ? paramName : "arg") + "`"); //$NON-NLS-1$ //$NON-NLS-2$

        EList<TypeItem> paramTypes = param.getType();
        if (paramTypes != null && !paramTypes.isEmpty())
        {
            String joined = String.join(" | ", collectTypeNames(paramTypes, useRussian)); //$NON-NLS-1$
            sb.append(" (" + joined + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        if (param.isDefaultValue())
        {
            sb.append(" - *may be omitted*"); //$NON-NLS-1$
        }
        if (param.isOut())
        {
            sb.append(" - *output parameter*"); //$NON-NLS-1$
        }
        sb.append("\n"); //$NON-NLS-1$
    }

    private void appendPropertyDocumentation(StringBuilder sb, Property prop, boolean useRussian)
    {
        String name = (useRussian && prop.getNameRu() != null) ? prop.getNameRu() : prop.getName();
        String altName = useRussian ? prop.getName() : prop.getNameRu();

        sb.append("### " + (name != null ? MarkdownTableHelper.escapeMarkdown(name) : "Unnamed")); //$NON-NLS-1$
        if (altName != null && !altName.equals(name))
        {
            sb.append(" / " + MarkdownTableHelper.escapeMarkdown(altName)); //$NON-NLS-1$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        List<String> flags = new ArrayList<>();
        if (prop.isReadable())
        {
            flags.add("readable"); //$NON-NLS-1$
        }
        if (prop.isWritable())
        {
            flags.add("writable"); //$NON-NLS-1$
        }
        if (!flags.isEmpty())
        {
            sb.append("*Accessibility: " + String.join("/", flags) + "*\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        EList<TypeItem> propTypes = prop.getTypes();
        if (propTypes != null && !propTypes.isEmpty())
        {
            sb.append("**Value type:** " + String.join(" | ", collectTypeNames(propTypes, useRussian)) //$NON-NLS-1$ //$NON-NLS-2$
                + "\n\n"); //$NON-NLS-1$
        }
    }

    private void appendEventDocumentation(StringBuilder sb, Event event, boolean useRussian)
    {
        String name = (useRussian && event.getNameRu() != null) ? event.getNameRu() : event.getName();
        String altName = useRussian ? event.getName() : event.getNameRu();

        sb.append("### " + (name != null ? MarkdownTableHelper.escapeMarkdown(name) : "Unnamed")); //$NON-NLS-1$
        if (altName != null && !altName.equals(name))
        {
            sb.append(" / " + MarkdownTableHelper.escapeMarkdown(altName)); //$NON-NLS-1$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        EList<ParamSet> paramSets = event.getParamSet();
        if (paramSets != null && !paramSets.isEmpty())
        {
            for (ParamSet ps : paramSets)
            {
                appendParamSetDocumentation(sb, ps, useRussian);
            }
        }
    }

    /**
     * Joins the (localized) names of the given type items, skipping {@code null}s. Returns the empty
     * string when nothing survives, so callers gate the surrounding header on the list itself being
     * non-empty, as the spec prescribes.
     */
    private String joinTypeNames(EList<TypeItem> types, boolean useRussian, String separator)
    {
        return String.join(separator, collectTypeNames(types, useRussian));
    }

    private List<String> collectTypeNames(EList<TypeItem> types, boolean useRussian)
    {
        List<String> names = new ArrayList<>();
        if (types == null)
        {
            return names;
        }
        for (TypeItem typeItem : types)
        {
            String name = useRussian ? typeItem.getNameRu() : typeItem.getName();
            if (name != null)
            {
                names.add(name);
            }
        }
        return names;
    }
}
