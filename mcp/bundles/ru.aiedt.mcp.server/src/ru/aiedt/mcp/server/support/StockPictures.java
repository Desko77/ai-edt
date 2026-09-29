/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.xtext.resource.IEObjectDescription;

import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;

import ru.aiedt.mcp.server.Activator;

/**
 * The stock pictures of a platform version: the {@code StdPicture} and {@code StdExtPicture} names.
 * <p>
 * EDT registers each stock picture of a version as an object description named
 * {@code StdPicture.<Name>} or {@code StdExtPicture.<Name>}, once under the English name and once
 * under the Russian one, both pointing at the same object. The list is read from these
 * descriptions; the pictures are not static fields of any class.
 * </p>
 */
public final class StockPictures
{
    /** The prefix of a standard picture. */
    public static final String STD = "StdPicture"; //$NON-NLS-1$

    /** The prefix of an extended standard picture. */
    public static final String STD_EXT = "StdExtPicture"; //$NON-NLS-1$

    /** The namespace of the platform model, which declares the picture class of a version. */
    private static final String PLATFORM_MODEL_NS = "http://g5.1c.ru/v8/dt/platform/model"; //$NON-NLS-1$

    /**
     * One stock picture.
     */
    public static final class Entry
    {
        /** {@link StockPictures#STD} or {@link StockPictures#STD_EXT}. */
        public final String prefix;

        /** The English name, or the Russian one when the version registers no English name. */
        public final String name;

        /** The Russian name, or <code>null</code> when the version registers none. */
        public final String nameRu;

        /**
         * @param prefix the prefix
         * @param name the English name
         * @param nameRu the Russian name, or <code>null</code>
         */
        Entry(String prefix, String name, String nameRu)
        {
            this.prefix = prefix;
            this.name = name;
            this.nameRu = nameRu;
        }

        /**
         * Whether the picture answers to a name, English or Russian, ignoring case.
         *
         * @param candidate the name, without the prefix
         * @return <code>true</code> when either name equals it
         */
        public boolean answersTo(String candidate)
        {
            return name.equalsIgnoreCase(candidate) || (nameRu != null && nameRu.equalsIgnoreCase(candidate));
        }

        /**
         * Whether a text occurs in either name, ignoring case.
         *
         * @param filter the text, or <code>null</code> or empty for every picture
         * @return <code>true</code> when the picture passes the filter
         */
        public boolean matches(String filter)
        {
            if (filter == null || filter.isEmpty())
            {
                return true;
            }
            String text = filter.toLowerCase(Locale.ROOT);
            return name.toLowerCase(Locale.ROOT).contains(text)
                || (nameRu != null && nameRu.toLowerCase(Locale.ROOT).contains(text));
        }
    }

    private StockPictures()
    {
    }

    /**
     * Reads the stock pictures a platform version registers.
     *
     * @param version the platform version
     * @return the pictures, standard ones first, each group ordered by name; empty when this runtime
     *         registers no picture descriptions for the version
     */
    public static List<Entry> read(Version version)
    {
        List<String[]> named = new ArrayList<>();
        for (EClass pictureClass : pictureClasses())
        {
            IEObjectProvider provider = IEObjectProvider.Registry.INSTANCE.get(pictureClass, version);
            Iterable<IEObjectDescription> descriptions =
                provider == null ? null : provider.getEObjectDescriptions(null);
            if (descriptions == null)
            {
                continue;
            }
            for (IEObjectDescription description : descriptions)
            {
                named.add(new String[] { description.getName().toString(),
                    String.valueOf(description.getEObjectURI()) });
            }
        }
        return fromDescriptions(named);
    }

    /**
     * The English names of the pictures of one prefix that pass a filter.
     *
     * @param pictures the stock pictures of a version
     * @param prefix {@link #STD} or {@link #STD_EXT}
     * @param filter matched against the English and the Russian name, or <code>null</code>
     * @return the names in the order of the list
     */
    public static List<String> names(List<Entry> pictures, String prefix, String filter)
    {
        List<String> names = new ArrayList<>();
        for (Entry picture : pictures)
        {
            if (picture.prefix.equals(prefix) && picture.matches(filter))
            {
                names.add(picture.name);
            }
        }
        return names;
    }

    /**
     * The name a stock picture is written under: its English one, whichever name the caller gave.
     * A name without a prefix is a standard picture, as the validator reads it.
     *
     * @param pictures the stock pictures of a version
     * @param qualified the picture name, with its prefix or without one
     * @return {@code <prefix>.<English name>} for a stock picture the list carries;
     *         {@code StdPicture.<name>} for a name without a prefix the list does not carry; any
     *         other name as it was given
     */
    public static String writtenName(List<Entry> pictures, String qualified)
    {
        if (qualified == null || qualified.isEmpty())
        {
            return qualified;
        }
        int dot = qualified.indexOf('.');
        String prefix = dot > 0 ? qualified.substring(0, dot) : STD;
        String name = dot > 0 ? qualified.substring(dot + 1) : qualified;
        for (Entry picture : pictures)
        {
            if (picture.prefix.equalsIgnoreCase(prefix) && picture.answersTo(name))
            {
                return picture.prefix + '.' + picture.name;
            }
        }
        return dot > 0 ? qualified : STD + '.' + qualified;
    }

    /**
     * The platform version a project targets.
     *
     * @param projectName the project, or <code>null</code>
     * @return the project's version, or {@link Version#LATEST} without a project or when the version
     *         cannot be read
     */
    public static Version versionOf(String projectName)
    {
        Activator activator = Activator.getDefault();
        IRuntimeVersionSupport versionSupport = activator == null ? null : activator.getRuntimeVersionSupport();
        if (projectName == null || projectName.isEmpty() || versionSupport == null)
        {
            return Version.LATEST;
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return Version.LATEST;
        }
        return versionSupport.getRuntimeVersionOrDefault(project, Version.LATEST);
    }

    /**
     * The classes the picture descriptions are registered under: the model picture, and the platform
     * picture class when this runtime declares it.
     *
     * @return the classes to ask the provider registry for
     */
    private static List<EClass> pictureClasses()
    {
        List<EClass> classes = new ArrayList<>();
        classes.add(McorePackage.Literals.PICTURE);
        EPackage platformModel = EPackage.Registry.INSTANCE.getEPackage(PLATFORM_MODEL_NS);
        EClassifier platformPicture =
            platformModel == null ? null : platformModel.getEClassifier("PlatformPicture"); //$NON-NLS-1$
        if (platformPicture instanceof EClass)
        {
            classes.add((EClass)platformPicture);
        }
        return classes;
    }

    /**
     * Builds the pictures from their descriptions: the English and the Russian description of one
     * picture share the object they point at, so they are paired by that object. A name made of
     * Latin letters is the English one.
     *
     * @param named each description as its qualified name and the URI of the object it points at
     * @return the pictures, standard ones first, each group ordered by name, one entry per written
     *         name; descriptions of other prefixes are left out
     */
    static List<Entry> fromDescriptions(List<String[]> named)
    {
        Map<String, String[]> byObject = new LinkedHashMap<>();
        for (String[] description : named)
        {
            String qualified = description[0];
            int dot = qualified.indexOf('.');
            if (dot <= 0 || dot == qualified.length() - 1)
            {
                continue;
            }
            String prefix = qualified.substring(0, dot);
            if (!STD.equals(prefix) && !STD_EXT.equals(prefix))
            {
                continue;
            }
            String name = qualified.substring(dot + 1);
            String[] picture = byObject.computeIfAbsent(prefix + '|' + description[1],
                key -> new String[] { prefix, null, null });
            int slot = isLatin(name) ? 1 : 2;
            if (picture[slot] == null)
            {
                picture[slot] = name;
            }
        }
        // Two objects may carry one written name; the entry that also knows the Russian name stays.
        Map<String, Entry> byWrittenName = new LinkedHashMap<>();
        for (String[] picture : byObject.values())
        {
            Entry entry = new Entry(picture[0], picture[1] != null ? picture[1] : picture[2], picture[2]);
            byWrittenName.merge(entry.prefix + '.' + entry.name.toLowerCase(Locale.ROOT), entry,
                (kept, other) -> kept.nameRu != null ? kept : other);
        }
        List<Entry> pictures = new ArrayList<>(byWrittenName.values());
        pictures.sort(Comparator.comparing((Entry entry) -> !STD.equals(entry.prefix))
            .thenComparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER));
        return pictures;
    }

    /**
     * @param name a picture name
     * @return <code>true</code> when every character of it is ASCII
     */
    private static boolean isLatin(String name)
    {
        for (int i = 0; i < name.length(); i++)
        {
            if (name.charAt(i) > 127)
            {
                return false;
            }
        }
        return true;
    }
}
