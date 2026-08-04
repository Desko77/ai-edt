/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.EMap;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Language;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.wire.SchemaComposer;
import ru.aiedt.mcp.server.wire.ToolResult;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Reports the top-level properties of a project's configuration: its name and localized texts, its
 * run and compatibility modes, its vendor and version.
 * <p>
 * The configuration model is read on the UI thread, because that is the thread EDT builds it on. With
 * no project named the first configuration project in the workspace answers; an extension is taken
 * only when there is no configuration project, so a workspace that holds both reports the base.
 * </p>
 */
public class ConfigurationInfoReader
    implements IMcpTool
{
    @Override
    public String getName()
    {
        return "get_configuration_properties"; //$NON-NLS-1$
    }

    @Override
    public String getDescription()
    {
        return "Back-compat alias of `project_admin` `operation=get_configuration_properties`; prefer the facade for new prompts. " //$NON-NLS-1$
            + "Reads a 1C:Enterprise configuration's top-level properties (name, synonym, comment, " //$NON-NLS-1$
            + "script variant, compatibility mode, and the rest)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return SchemaComposer.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "Project name (optional; when omitted, the first configuration project answers)") //$NON-NLS-1$
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
        String projectName = params.get("projectName"); //$NON-NLS-1$
        try
        {
            return UiSync.call(() -> getConfigurationProperties(projectName));
        }
        catch (UiSync.UiBusyException e)
        {
            return ToolResult.error(e.getMessage()).put("tag", e.tag()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the configuration and renders its properties.
     * <p>
     * Public and static so callers that already hold the UI thread can reuse it without the threading
     * dance in {@link #execute(Map)}.
     * </p>
     *
     * @param projectName the project to read, or <code>null</code>/empty for the first configuration
     *            project in the workspace
     * @return the properties as a JSON document, or a JSON error
     */
    public static String getConfigurationProperties(String projectName)
    {
        try
        {
            Activator.logInfo("getConfigurationProperties: starting"); //$NON-NLS-1$

            Activator activator = Activator.getDefault();
            IDtProjectManager dtProjectManager = activator == null ? null : activator.getDtProjectManager();
            IV8ProjectManager v8ProjectManager = activator == null ? null : activator.getV8ProjectManager();
            if (dtProjectManager == null || v8ProjectManager == null)
            {
                return ToolResult.error("The project manager cannot be reached").toJson(); //$NON-NLS-1$
            }

            IConfigurationProject configurationProject = null;
            IExtensionProject extensionProject = null;

            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            {
                if (!project.isOpen())
                {
                    continue;
                }
                IDtProject dtProject = dtProjectManager.getDtProject(project);
                if (dtProject == null)
                {
                    continue;
                }
                IV8Project v8Project = v8ProjectManager.getProject(dtProject);
                if (v8Project == null)
                {
                    continue;
                }

                boolean nameMatches =
                    projectName == null || projectName.isEmpty() || project.getName().equals(projectName);
                if (!nameMatches)
                {
                    continue;
                }

                if (v8Project instanceof IConfigurationProject)
                {
                    configurationProject = (IConfigurationProject)v8Project;
                    break;
                }
                if (v8Project instanceof IExtensionProject)
                {
                    // Remembered, but a later configuration project would still win.
                    extensionProject = (IExtensionProject)v8Project;
                }
            }

            Configuration configuration;
            String ownerName;
            boolean isExtension;
            if (configurationProject != null)
            {
                configuration = configurationProject.getConfiguration();
                ownerName = configurationProject.getProject().getName();
                isExtension = false;
            }
            else if (extensionProject != null)
            {
                configuration = extensionProject.getConfiguration();
                ownerName = extensionProject.getProject().getName();
                isExtension = true;
            }
            else
            {
                configuration = null;
                ownerName = null;
                isExtension = false;
            }

            if (configuration == null)
            {
                String suffix = projectName != null && !projectName.isEmpty()
                    ? " with name: " + projectName : ""; //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.error("Found no configuration or extension project" + suffix).toJson(); //$NON-NLS-1$
            }

            return render(configuration, ownerName, isExtension);
        }
        catch (Exception e)
        {
            Activator.logError("Failed to read configuration properties", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }

    /**
     * Builds the properties document in its fixed key order.
     *
     * @param configuration the configuration
     * @param ownerName the name of the owning project
     * @param isExtension whether the configuration came from an extension
     * @return the JSON document
     */
    private static String render(Configuration configuration, String ownerName, boolean isExtension)
    {
        List<String> usePurposes = new ArrayList<>();
        for (Object purpose : configuration.getUsePurposes())
        {
            usePurposes.add(purpose.toString());
        }

        ToolResult result = ToolResult.success();
        result.put("name", configuration.getName()); //$NON-NLS-1$
        result.put("synonym", toLocalizedMap(configuration.getSynonym())); //$NON-NLS-1$
        result.put("comment", configuration.getComment()); //$NON-NLS-1$
        putEnum(result, "scriptVariant", configuration.getScriptVariant()); //$NON-NLS-1$
        putEnum(result, "defaultRunMode", configuration.getDefaultRunMode()); //$NON-NLS-1$
        putEnum(result, "dataLockControlMode", configuration.getDataLockControlMode()); //$NON-NLS-1$
        putEnum(result, "compatibilityMode", configuration.getCompatibilityMode()); //$NON-NLS-1$
        putEnum(result, "modalityUseMode", configuration.getModalityUseMode()); //$NON-NLS-1$
        putEnum(result, "interfaceCompatibilityMode", configuration.getInterfaceCompatibilityMode()); //$NON-NLS-1$
        putEnum(result, "objectAutonumerationMode", configuration.getObjectAutonumerationMode()); //$NON-NLS-1$
        result.put("usePurposes", usePurposes); //$NON-NLS-1$
        result.put("briefInformation", toLocalizedMap(configuration.getBriefInformation())); //$NON-NLS-1$
        result.put("detailedInformation", toLocalizedMap(configuration.getDetailedInformation())); //$NON-NLS-1$
        result.put("vendor", configuration.getVendor()); //$NON-NLS-1$
        result.put("version", configuration.getVersion()); //$NON-NLS-1$
        result.put("copyright", toLocalizedMap(configuration.getCopyright())); //$NON-NLS-1$
        result.put("vendorInformationAddress", toLocalizedMap(configuration.getVendorInformationAddress())); //$NON-NLS-1$
        result.put("configurationInformationAddress", //$NON-NLS-1$
            toLocalizedMap(configuration.getConfigurationInformationAddress()));

        Language defaultLanguage = configuration.getDefaultLanguage();
        if (defaultLanguage != null)
        {
            result.put("defaultLanguage", defaultLanguage.getName()); //$NON-NLS-1$
        }
        // 1.43.x: extension name prefix (Префикс имени) - the RSV 5.9 basic-tab item the
        // parity audit flagged as missing. Non-empty only on extension configurations.
        String namePrefix = configuration.getNamePrefix();
        if (namePrefix != null && !namePrefix.isEmpty())
        {
            result.put("namePrefix", namePrefix); //$NON-NLS-1$
        }
        // 1.43.x: default roles (ОсновныеРоли) and main configuration forms
        // (default/auxiliary report, constants, dynamic-list, search, data-history
        // forms). Parity item flagged by the RSV 5.9 audit as missing.
        List<String> mainRoles = collectMainRoles(configuration);
        if (!mainRoles.isEmpty())
        {
            result.put("mainRoles", mainRoles); //$NON-NLS-1$
        }
        Map<String, String> mainForms = collectMainForms(configuration);
        if (!mainForms.isEmpty())
        {
            result.put("mainForms", mainForms); //$NON-NLS-1$
        }
        if (ownerName != null)
        {
            result.put("projectName", ownerName); //$NON-NLS-1$
        }
        if (isExtension)
        {
            result.put("isExtension", true); //$NON-NLS-1$
        }
        return result.toJson();
    }

    /**
     * Puts an enum value by its {@code toString()}, dropping it when the value is absent.
     *
     * @param result the result being built
     * @param key the member name
     * @param value the enum value, or <code>null</code>
     */
    private static void putEnum(ToolResult result, String key, Object value)
    {
        if (value != null)
        {
            result.put(key, value.toString());
        }
    }

    /**
     * Copies a localized EMF map into a plain map.
     * <p>
     * A {@link HashMap} on purpose: the language-key order is not meant to be stable, and imposing one
     * would misrepresent what the model holds.
     * </p>
     *
     * @param source the localized map; may be <code>null</code>
     * @return a map of language code to text, never <code>null</code>
     */
    private static Map<String, String> toLocalizedMap(EMap<String, String> source)
    {
        Map<String, String> map = new HashMap<>();
        if (source != null)
        {
            for (Map.Entry<String, String> entry : source.entrySet())
            {
                map.put(entry.getKey(), entry.getValue());
            }
        }
        return map;
    }

    /**
     * Collects the configuration's default roles (ОсновныеРоли) by name.
     *
     * @param configuration the configuration
     * @return role names, never <code>null</code>
     */
    private static List<String> collectMainRoles(Configuration configuration)
    {
        List<String> roles = new ArrayList<>();
        for (Object role : configuration.getDefaultRoles())
        {
            if (role instanceof com._1c.g5.v8.dt.metadata.mdclass.Role)
            {
                String name = ((com._1c.g5.v8.dt.metadata.mdclass.Role)role).getName();
                if (name != null && !name.isEmpty())
                {
                    roles.add(name);
                }
            }
        }
        return roles;
    }

    /**
     * Collects the configuration's main forms (every {@code get*Form()} returning a non-null
     * {@link com._1c.g5.v8.dt.metadata.mdclass.CommonForm} - default/auxiliary report, constants,
     * dynamic-list, search, data-history, etc.). Reflective so new form kinds EDT adds later are
     * picked up without touching this tool.
     *
     * @param configuration the configuration
     * @return a map of property name (e.g. {@code defaultReportForm}) to the form's metadata name
     */
    private static Map<String, String> collectMainForms(Configuration configuration)
    {
        Map<String, String> forms = new java.util.LinkedHashMap<>();
        Class<?> commonForm = com._1c.g5.v8.dt.metadata.mdclass.CommonForm.class;
        for (java.lang.reflect.Method m : configuration.getClass().getMethods())
        {
            if (m.getParameterCount() != 0)
            {
                continue;
            }
            String name = m.getName();
            // skip eGet/eDynamicGet and the like; keep only plain get*Form getters
            if (name.length() <= 4 || !name.startsWith("get") //$NON-NLS-1$
                || !name.endsWith("Form")) //$NON-NLS-1$
            {
                continue;
            }
            Class<?> rt = m.getReturnType();
            if (rt == null || !commonForm.isAssignableFrom(rt))
            {
                continue;
            }
            try
            {
                Object form = m.invoke(configuration);
                if (form instanceof com._1c.g5.v8.dt.metadata.mdclass.CommonForm)
                {
                    String key = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                    forms.put(key,
                        ((com._1c.g5.v8.dt.metadata.mdclass.CommonForm)form).getName());
                }
            }
            catch (Exception ignored)
            {
                // best-effort: a single unreadable form does not abort the whole report
            }
        }
        return forms;
    }
}
