/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;

import ru.aiedt.mcp.server.Activator;

/**
 * The numbers a user is allowed to change about a tool - today, six tools and seven limits.
 * <p>
 * What this does <em>not</em> do is rewrite anything an agent can see. A tool's JSON schema is a
 * fixed string; it still advertises the limit it was written with, whatever the user has configured.
 * The setting bites inside the tool's own <code>execute</code>, and only when the agent left the
 * argument out - an argument the agent did supply always wins, and is then clamped by the tool. So
 * the contract is: same schema, different behaviour on omission. Do not try to make the schema
 * dynamic; the agent is not the one being configured here, the user is.
 * </p>
 * <p>
 * The catalogue is static and immutable, and every read goes straight to the preference store, so
 * tools may call this from the server's worker threads while the preference page writes from the UI
 * thread. Nothing is cached and nothing is locked.
 * </p>
 */
public final class ToolParamSettings
{
    private static final ToolParamSettings INSTANCE = new ToolParamSettings();

    private static final String KEY_PREFIX = "tool."; //$NON-NLS-1$

    private static final String KEY_SEPARATOR = "."; //$NON-NLS-1$

    private static final String RESULT_LIMIT = "Result limit"; //$NON-NLS-1$

    /**
     * Tool name to its parameters, in the order the detail panel lays out the spinners.
     */
    private static final Map<String, List<ParameterDef>> PARAMETERS = buildCatalogue();

    private ToolParamSettings()
    {
        // Singleton
    }

    /**
     * Returns the service.
     *
     * @return the single instance, never <code>null</code>
     */
    public static ToolParamSettings getInstance()
    {
        return INSTANCE;
    }

    /**
     * Returns what can be configured about a tool.
     *
     * @param toolName the tool name
     * @return its parameters in declaration order, or an empty list when the tool has none - which
     *         is the case for all but six of them. Never <code>null</code>
     */
    public List<ParameterDef> getParametersForTool(String toolName)
    {
        List<ParameterDef> parameters = PARAMETERS.get(toolName);
        return parameters != null ? parameters : Collections.emptyList();
    }

    /**
     * Returns the tools that have anything to configure.
     *
     * @return a fresh list of tool names, in catalogue order
     */
    public List<String> getConfigurableToolNames()
    {
        return new ArrayList<>(PARAMETERS.keySet());
    }

    /**
     * Returns the whole catalogue.
     *
     * @return an unmodifiable map of tool name to parameters
     */
    public Map<String, List<ParameterDef>> getAllParameters()
    {
        return PARAMETERS;
    }

    /**
     * Returns the value a tool should use when the agent did not say.
     * <p>
     * The user's number is only honoured once they have actually chosen one. Until then - and where
     * there are no preferences to consult at all - the answer is the parameter's own default rather
     * than the caller's fallback, which is why the two agree in every call site there is. Anything
     * the user did store is clamped back into range on the way out: the spinner cannot produce a bad
     * number, but a hand-edited <code>.prefs</code> file can.
     * </p>
     *
     * @param toolName  the tool asking
     * @param paramName the parameter it wants
     * @param fallback  what to answer when the tool or the parameter is not one this knows about
     * @return the configured value, the parameter's default, or the fallback
     */
    public int getParameterValue(String toolName, String paramName, int fallback)
    {
        List<ParameterDef> parameters = PARAMETERS.get(toolName);
        if (parameters == null)
        {
            return fallback;
        }

        ParameterDef definition = find(parameters, paramName);
        if (definition == null)
        {
            return fallback;
        }

        IPreferenceStore store = store();
        if (store == null)
        {
            return definition.getDefaultValue();
        }

        String key = buildKey(toolName, paramName);
        if (store.isDefault(key))
        {
            return definition.getDefaultValue();
        }

        int value = store.getInt(key);
        return Math.max(definition.getMinValue(), Math.min(definition.getMaxValue(), value));
    }

    /**
     * Records a value for a parameter.
     * <p>
     * Unvalidated on purpose - it stores what it is given. The only guard on the range is the
     * spinner the user actually types into; a caller that goes round the spinner is trusted to know
     * what it is doing, and {@link #getParameterValue} clamps whatever comes back anyway.
     * </p>
     *
     * @param toolName  the tool
     * @param paramName the parameter
     * @param value     the value
     */
    public void setParameterValue(String toolName, String paramName, int value)
    {
        IPreferenceStore store = store();
        if (store == null)
        {
            return;
        }
        store.setValue(buildKey(toolName, paramName), value);
    }

    /**
     * Forgets what the user chose for one parameter, so it goes back to its default.
     *
     * @param toolName  the tool
     * @param paramName the parameter
     */
    public void resetParameter(String toolName, String paramName)
    {
        IPreferenceStore store = store();
        if (store == null)
        {
            return;
        }
        store.setToDefault(buildKey(toolName, paramName));
    }

    /**
     * Registers the shipped value of every parameter with the store.
     * <p>
     * Without this the store has no notion of a default, so it cannot tell a value the user chose
     * from one they never touched - and {@link #getParameterValue} decides between them by asking
     * exactly that.
     * </p>
     *
     * @param store the preference store to seed
     */
    public void initializeDefaults(IPreferenceStore store)
    {
        for (Map.Entry<String, List<ParameterDef>> entry : PARAMETERS.entrySet())
        {
            for (ParameterDef definition : entry.getValue())
            {
                store.setDefault(buildKey(entry.getKey(), definition.getName()),
                    definition.getDefaultValue());
            }
        }
    }

    /**
     * Builds the preference key a parameter is stored under.
     * <p>
     * The shape is <code>tool.&lt;tool&gt;.&lt;parameter&gt;</code>, and the page takes it apart
     * again by splitting on the dot into three. A tool name with a dot in it would therefore be
     * saved and never read back. There is no such tool, and there must not be.
     * </p>
     *
     * @param toolName  the tool
     * @param paramName the parameter
     * @return the preference key
     */
    static String buildKey(String toolName, String paramName)
    {
        return KEY_PREFIX + toolName + KEY_SEPARATOR + paramName;
    }

    /**
     * Picks a parameter out of a tool's list.
     *
     * @param parameters the tool's parameters
     * @param paramName  the one wanted
     * @return the definition, or <code>null</code> when the tool has no such parameter
     */
    private static ParameterDef find(List<ParameterDef> parameters, String paramName)
    {
        for (ParameterDef definition : parameters)
        {
            if (definition.getName().equals(paramName))
            {
                return definition;
            }
        }
        return null;
    }

    /**
     * Returns the plugin's preference store.
     *
     * @return the store, or <code>null</code> when there is no plugin to ask
     */
    private static IPreferenceStore store()
    {
        Activator activator = Activator.getDefault();
        return activator != null ? activator.getPreferenceStore() : null;
    }

    /**
     * Writes down every parameter there is. Insertion order is the order the user sees.
     *
     * @return the catalogue, unmodifiable all the way down
     */
    private static Map<String, List<ParameterDef>> buildCatalogue()
    {
        Map<String, List<ParameterDef>> catalogue = new LinkedHashMap<>();

        catalogue.put("get_project_errors", parameters(new ParameterDef("limit", RESULT_LIMIT, //$NON-NLS-1$ //$NON-NLS-2$
            "How many problems a call reports unless asked otherwise", 100, 1, 1000))); //$NON-NLS-1$

        catalogue.put("get_bookmarks", parameters(new ParameterDef("limit", RESULT_LIMIT, //$NON-NLS-1$ //$NON-NLS-2$
            "Default number of bookmarks to return", 100, 1, 1000))); //$NON-NLS-1$

        catalogue.put("get_tasks", parameters(new ParameterDef("limit", RESULT_LIMIT, //$NON-NLS-1$ //$NON-NLS-2$
            "Default number of tasks to return", 100, 1, 1000))); //$NON-NLS-1$

        catalogue.put("get_metadata_objects", parameters(new ParameterDef("limit", RESULT_LIMIT, //$NON-NLS-1$ //$NON-NLS-2$
            "Number of metadata objects returned by default", 100, 1, 1000))); //$NON-NLS-1$

        catalogue.put("get_content_assist", parameters(new ParameterDef("limit", RESULT_LIMIT, //$NON-NLS-1$ //$NON-NLS-2$
            "How many content assist proposals are returned by default", 100, 1, 1000))); //$NON-NLS-1$

        catalogue.put("search_in_code", parameters( //$NON-NLS-1$
            new ParameterDef("maxResults", "Max results", //$NON-NLS-1$ //$NON-NLS-2$
                "Upper bound on how many search matches are returned", 100, 1, 500), //$NON-NLS-1$
            new ParameterDef("contextLines", "Neighbouring lines", //$NON-NLS-1$ //$NON-NLS-2$
                "How many neighbouring lines travel with every hit", 2, 0, 5))); //$NON-NLS-1$

        return Collections.unmodifiableMap(catalogue);
    }

    /**
     * Wraps a tool's parameters so that nothing can add to them later.
     *
     * @param definitions the parameters
     * @return an unmodifiable list
     */
    private static List<ParameterDef> parameters(ParameterDef... definitions)
    {
        return Collections.unmodifiableList(Arrays.asList(definitions));
    }

    /**
     * One configurable number: what it is called, what it means, and what it may be.
     * <p>
     * Immutable, and built only by the catalogue above - hence the constructor no one outside this
     * package can reach.
     * </p>
     */
    public static final class ParameterDef
    {
        private final String name;

        private final String displayName;

        private final String description;

        private final int defaultValue;

        private final int minValue;

        private final int maxValue;

        ParameterDef(String name, String displayName, String description, int defaultValue, int minValue,
            int maxValue)
        {
            this.name = name;
            this.displayName = displayName;
            this.description = description;
            this.defaultValue = defaultValue;
            this.minValue = minValue;
            this.maxValue = maxValue;
        }

        /**
         * Returns the name the tool's JSON schema calls this parameter.
         *
         * @return the parameter name
         */
        public String getName()
        {
            return name;
        }

        /**
         * Returns the label next to the spinner.
         *
         * @return the display name
         */
        public String getDisplayName()
        {
            return displayName;
        }

        /**
         * Returns what the parameter does, shown as the tooltip.
         *
         * @return the description
         */
        public String getDescription()
        {
            return description;
        }

        /**
         * Returns the value the tool uses until the user says otherwise.
         *
         * @return the default
         */
        public int getDefaultValue()
        {
            return defaultValue;
        }

        /**
         * Returns the lowest value the spinner will offer.
         *
         * @return the minimum
         */
        public int getMinValue()
        {
            return minValue;
        }

        /**
         * Returns the highest value the spinner will offer.
         *
         * @return the maximum
         */
        public int getMaxValue()
        {
            return maxValue;
        }
    }
}
