/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.Activator;

/**
 * Asks what a new release breaks in an extension, object by object.
 * <p>
 * <b>An extension breaks against a release differently from a configuration.</b> A configuration is
 * merged and the conflicts are visible in the comparison; an extension is either applied by the
 * platform or refused whole, and the reason arrives as one message about the first thing that went
 * wrong. So the useful thing to do beforehand is to enumerate what an extension is coupled to and
 * check each coupling separately, which is what this does.
 * </p>
 * <p>
 * <b>What it cannot do, said here rather than discovered later.</b> Finding nothing does not mean
 * the extension applies. Only the platform loading it says that. This reads declarations: an object
 * an extension adopts, a field it borrows, a type it depends on. A dependency written as a string
 * in code, a name inside a query, a controlled fragment whose text drifted - none of those are
 * declarations, and none of them are visible from here.
 * </p>
 */
public final class ExtensionFitness
{
    /** How many findings one answer carries before it stops being readable. */
    private static final int MAX_FINDINGS = 500;

    /** One thing the new release broke. */
    public static final class Finding
    {
        /** What the extension is coupled to. */
        public String object;

        /** What kind of coupling it is - the adopted object, one of its fields, a type. */
        public String kind;

        /** What happened to it in the new release. */
        public String what;

        /**
         * Records one finding.
         *
         * @param object what the extension is coupled to.
         * @param kind the kind of coupling.
         * @param what happened to it.
         */
        public Finding(String object, String kind, String what)
        {
            this.object = object;
            this.kind = kind;
            this.what = what;
        }
    }

    /** What a new release does to an extension. */
    public static final class Verdict
    {
        /** Why nothing could be checked. Present only when the answer is a refusal. */
        public String cannotTell;

        /** How many objects the extension adopts from the configuration. */
        public int adoptedObjects;

        /** What the release broke. */
        public final List<Finding> findings = new ArrayList<>();

        /** True when there were more findings than one answer carries. */
        public boolean truncated;
    }

    private ExtensionFitness()
    {
        // Static check.
    }

    /**
     * Checks every object an extension adopts against the configuration it would be applied to.
     *
     * @param extensionProject the extension.
     * @param baseProject the configuration the new delivery is loaded as.
     * @return what the release breaks, or a refusal
     */
    public static Verdict check(String extensionProject, String baseProject)
    {
        Verdict verdict = new Verdict();
        IProject ext = ProjectResolver.resolve(extensionProject);
        IProject base = ProjectResolver.resolve(baseProject);
        if (ext == null)
        {
            verdict.cannotTell = ProjectResolver.describeNotFound(extensionProject);
            return verdict;
        }
        if (base == null)
        {
            verdict.cannotTell = ProjectResolver.describeNotFound(baseProject);
            return verdict;
        }
        try
        {
            com._1c.g5.v8.dt.core.platform.IConfigurationProvider provider =
                Activator.getDefault().getConfigurationProvider();
            Configuration extConfig = provider == null ? null : provider.getConfiguration(ext);
            Configuration baseConfig = provider == null ? null : provider.getConfiguration(base);
            if (extConfig == null || baseConfig == null)
            {
                verdict.cannotTell = "one of the configurations is not loaded - both projects have " //$NON-NLS-1$
                    + "to be open and indexed"; //$NON-NLS-1$
                return verdict;
            }
            walk(extConfig, baseConfig, verdict);
        }
        catch (RuntimeException | LinkageError cannotCheck)
        {
            verdict.cannotTell = "the extension could not be checked: " + cannotCheck; //$NON-NLS-1$
            Activator.logDebug("extension fitness failed: " + cannotCheck); //$NON-NLS-1$
        }
        return verdict;
    }

    /**
     * Walks the extension's objects and looks each one up in the delivery.
     *
     * @param extConfig the extension's configuration.
     * @param baseConfig the delivery's configuration.
     * @param verdict the answer being built.
     */
    private static void walk(Configuration extConfig, Configuration baseConfig, Verdict verdict)
    {
        for (String type : MetadataTypeCatalog.getAllEnglishSingularNames())
        {
            List<? extends MdObject> objects;
            try
            {
                objects = MetadataTypeCatalog.getObjects(extConfig, type);
            }
            catch (RuntimeException noSuchCollection)
            {
                continue;
            }
            if (objects == null)
            {
                continue;
            }
            for (MdObject object : objects)
            {
                if (object == null || object.getName() == null || !isAdopted(object))
                {
                    continue;
                }
                verdict.adoptedObjects++;
                checkOne(type, object, baseConfig, verdict);
            }
        }
    }

    /**
     * Says whether an object is one the extension borrowed from the configuration.
     * <p>
     * An object the extension invented is its own and cannot be broken by a release; only what it
     * borrowed is coupled to something that can change underneath it.
     * </p>
     *
     * @param object the object.
     * @return <code>true</code> when it was adopted
     */
    private static boolean isAdopted(MdObject object)
    {
        try
        {
            Object belonging = object.getObjectBelonging();
            return belonging != null && "ADOPTED".equalsIgnoreCase(String.valueOf(belonging)); //$NON-NLS-1$
        }
        catch (RuntimeException | LinkageError cannotTell)
        {
            return false;
        }
    }

    /**
     * Looks one adopted object up in the delivery and compares what the extension borrows.
     *
     * @param type the metadata type.
     * @param adopted the object as the extension has it.
     * @param baseConfig the delivery.
     * @param verdict the answer being built.
     */
    private static void checkOne(String type, MdObject adopted, Configuration baseConfig,
        Verdict verdict)
    {
        String fqn = type + "." + adopted.getName(); //$NON-NLS-1$
        MdObject inBase = MetadataTypeCatalog.findObject(baseConfig, type, adopted.getName());
        if (inBase == null)
        {
            // The heaviest finding there is: everything the extension does to this object has
            // nothing left to do it to.
            add(verdict, new Finding(fqn, "object", //$NON-NLS-1$
                "the delivery no longer has this object, so nothing the extension borrows from " //$NON-NLS-1$
                    + "it exists")); //$NON-NLS-1$
            return;
        }
        compareFields(fqn, adopted, inBase, verdict);
    }

    /**
     * Compares the fields the extension borrows with the ones the delivery has.
     *
     * @param fqn the object.
     * @param adopted the extension's copy.
     * @param inBase the delivery's copy.
     * @param verdict the answer being built.
     */
    private static void compareFields(String fqn, MdObject adopted, MdObject inBase, Verdict verdict)
    {
        Map<String, EObject> mine = MdChildren.byName(adopted, "getAttributes"); //$NON-NLS-1$
        Map<String, EObject> theirs = MdChildren.byName(inBase, "getAttributes"); //$NON-NLS-1$
        for (Map.Entry<String, EObject> field : mine.entrySet())
        {
            EObject other = theirs.get(field.getKey());
            if (other == null)
            {
                add(verdict, new Finding(fqn + "." + field.getKey(), "attribute", //$NON-NLS-1$ //$NON-NLS-2$
                    "the delivery no longer has this attribute")); //$NON-NLS-1$
                continue;
            }
            String was = MdChildren.typeOf(field.getValue());
            String now = MdChildren.typeOf(other);
            if (was != null && now != null && !was.equals(now))
            {
                // Not cosmetic. Code written against one type and run against another fails where
                // it is used, not where it is declared, which is the hardest kind to trace back.
                add(verdict, new Finding(fqn + "." + field.getKey(), "attribute type", //$NON-NLS-1$ //$NON-NLS-2$
                    "the type changed: " + was + " -> " + now)); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    /**
     * Adds a finding, stopping short rather than running away with the answer.
     *
     * @param verdict the answer being built.
     * @param finding what was found.
     */
    private static void add(Verdict verdict, Finding finding)
    {
        if (verdict.findings.size() >= MAX_FINDINGS)
        {
            verdict.truncated = true;
            return;
        }
        verdict.findings.add(finding);
    }

    /** Reads named children off a metadata object without knowing its type. */
    static final class MdChildren
    {
        private MdChildren()
        {
            // Static helper.
        }

        /**
         * Indexes an object's children by name.
         *
         * @param object the owner.
         * @param getter the accessor, by name.
         * @return the children by name, empty when the owner has no such collection
         */
        static Map<String, EObject> byName(MdObject object, String getter)
        {
            Map<String, EObject> found = new LinkedHashMap<>();
            try
            {
                Object list = object.getClass().getMethod(getter).invoke(object);
                if (!(list instanceof List))
                {
                    return found;
                }
                for (Object child : (List<?>)list)
                {
                    if (!(child instanceof EObject))
                    {
                        continue;
                    }
                    Object name = child.getClass().getMethod("getName").invoke(child); //$NON-NLS-1$
                    if (name != null)
                    {
                        found.put(String.valueOf(name), (EObject)child);
                    }
                }
            }
            catch (ReflectiveOperationException | RuntimeException noSuchCollection)
            {
                // An object type without attributes is ordinary, not a failure.
                return found;
            }
            return found;
        }

        /**
         * Renders a field's type for comparison.
         *
         * @param field the field.
         * @return the type as text, or <code>null</code> when it will not say
         */
        static String typeOf(EObject field)
        {
            try
            {
                Object type = field.getClass().getMethod("getType").invoke(field); //$NON-NLS-1$
                return type == null ? null : String.valueOf(type);
            }
            catch (ReflectiveOperationException | RuntimeException noType)
            {
                return null;
            }
        }
    }
}
