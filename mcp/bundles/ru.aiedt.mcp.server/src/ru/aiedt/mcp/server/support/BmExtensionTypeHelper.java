/*
 * Copyright (C) 2026 AI-EDT contributors
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.ObjectExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.MdClassExtensionFactory;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeDescriptionExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.TypeExtension;
import com._1c.g5.v8.dt.metadata.mdclass.extension.type.MdPropertyState;

/**
 * Adds types to an object an extension has adopted, without disturbing the ones it
 * inherited.
 * <p>
 * An adopted object keeps no type composition of its own. Its types live in the
 * extension block, under {@code typeExtension}, one entry per type with a state saying
 * where the type came from: {@code Checked} for what the base configuration already had,
 * {@code Extended} for what this extension adds. Measured on a working extension, only
 * the added ones are listed with {@code Extended}; the inherited ones stay {@code Checked}
 * and are never repeated.
 * </p>
 * <p>
 * That is why this is a separate operation rather than a flag on {@code set_object_type}.
 * Setting a type REPLACES the composition, and there is nothing to replace here - measured
 * on a clean probe, {@code set_object_type} on an adopted object reported
 * {@code applied:true} and wrote nothing at all, because it was writing to a property the
 * adopted object does not carry. Extending ADDS, and adds where the object actually keeps
 * its types.
 * </p>
 * <p>
 * The block is reached through its EMF feature rather than a cast: eleven unrelated
 * extension interfaces declare {@code typeExtension} (attributes, dimensions, resources,
 * constants, defined types, session parameters and more) with no common supertype between
 * them, and the feature is the one thing they do share.
 * </p>
 */
public final class BmExtensionTypeHelper
{
    /** The EMF feature every type-carrying extension block names its composition with. */
    private static final String TYPE_EXTENSION_FEATURE = "typeExtension"; //$NON-NLS-1$

    private BmExtensionTypeHelper()
    {
    }

    /** What an extend pass did, in the words the response needs. */
    public static final class ExtendResult
    {
        /** True when the pass finished and the requested types are on the object. */
        public boolean ok;

        /** True when this pass actually changed the model. */
        public boolean mutated;

        /** Types this pass added, marked Extended. */
        public final List<String> added = new ArrayList<>();

        /** Types the object already carried, inherited or added earlier. */
        public final List<String> alreadyPresent = new ArrayList<>();

        /** Types refused: unknown name, or an object that does not exist. */
        public final List<String> unresolved = new ArrayList<>();

        /** Why the pass could not run. Null when {@link #ok}. */
        public String error;

        /** Which extension block was written, for the caller to recognise. */
        public String blockKind;

        /**
         * The block's own {@code type} state, reported rather than set: on the shape
         * measured live it stays unset while the composition does the work, and guessing
         * at it on other kinds is how a silent half-write would start.
         */
        public String propertyState;
    }

    /**
     * Adds the requested types to an adopted object's extension block.
     * <p>
     * Types already on the object - whether inherited or added by an earlier call - are
     * left exactly as they are and reported as already present, so calling twice with the
     * same argument changes nothing the second time.
     * </p>
     *
     * @param object the adopted object, or the adopted child (attribute, dimension,
     *            resource) whose composition is being extended.
     * @param project owning project, for canonical primitive resolution.
     * @param config configuration the reference types are resolved against.
     * @param typeFqns types to add, each a platform name or a reference FQN. A single
     *            element may carry a comma-separated list.
     * @return what was added, what was already there, and what was refused.
     */
    public static ExtendResult extendTypes(MdObject object, IProject project,
        Configuration config, List<String> typeFqns)
    {
        ExtendResult r = new ExtendResult();
        if (object == null || typeFqns == null || typeFqns.isEmpty())
        {
            r.error = "object and typeFqns are required"; //$NON-NLS-1$
            return r;
        }
        ObjectExtension block = object.getExtension();
        if (block == null)
        {
            r.error = object.getName() + " carries no extension block, so it has no place to " //$NON-NLS-1$
                + "keep an extended type. Borrow it into the extension first."; //$NON-NLS-1$
            return r;
        }
        r.blockKind = block.eClass().getName();
        EStructuralFeature feature = block.eClass().getEStructuralFeature(TYPE_EXTENSION_FEATURE);
        if (feature == null)
        {
            r.error = "A " + r.blockKind + " keeps no type composition (no '" //$NON-NLS-1$ //$NON-NLS-2$
                + TYPE_EXTENSION_FEATURE + "' feature), so there is no type here to extend. " //$NON-NLS-1$
                + "This operation applies to objects and children that carry a type: " //$NON-NLS-1$
                + "attributes, dimensions, resources, constants, defined types, " //$NON-NLS-1$
                + "session parameters."; //$NON-NLS-1$
            return r;
        }
        r.propertyState = readPropertyState(block);

        List<String> requested = expand(typeFqns);
        if (requested.isEmpty())
        {
            r.error = "No type names to add"; //$NON-NLS-1$
            return r;
        }

        TypeDescriptionExtension composition = readComposition(block, feature);
        boolean freshComposition = composition == null;
        if (freshComposition)
        {
            composition = MdClassExtensionFactory.eINSTANCE.createTypeDescriptionExtension();
        }
        Set<String> present = presentTypeNames(composition);

        List<String> accepted = new ArrayList<>();
        for (String fqn : requested)
        {
            if (present.contains(fqn))
            {
                r.alreadyPresent.add(fqn);
                continue;
            }
            if (!BmDefinedTypeHelper.isAcceptableType(fqn, project, config))
            {
                r.unresolved.add(fqn);
                continue;
            }
            accepted.add(fqn);
        }
        if (!r.unresolved.isEmpty())
        {
            r.error = "Some types could not be resolved: " + String.join(", ", r.unresolved) //$NON-NLS-1$
                + ". Check the spelling against the platform's own type names, and for a " //$NON-NLS-1$
                + "reference type check that the object it names exists in this configuration."; //$NON-NLS-1$
            return r;
        }
        if (accepted.isEmpty())
        {
            // Every requested type was already there. Nothing to write, and saying so is
            // the whole answer - a second identical call must not look like a first one.
            r.ok = true;
            return r;
        }

        try
        {
            for (String fqn : accepted)
            {
                Object item = BmDefinedTypeHelper.createTypeItem(fqn, project, config);
                if (!(item instanceof TypeItem))
                {
                    r.unresolved.add(fqn);
                    continue;
                }
                TypeExtension entry = MdClassExtensionFactory.eINSTANCE.createTypeExtension();
                entry.setState(MdPropertyState.EXTENDED);
                entry.setType((TypeItem)item);
                composition.getTypes().add(entry);
                r.added.add(fqn);
            }
            if (!r.unresolved.isEmpty())
            {
                // These passed the name check a moment ago, so failing to build them is not a
                // spelling problem - something is wrong, and a partial write reported as success
                // would leave the caller believing the whole list landed.
                r.error = "Could not build a type for: " + String.join(", ", r.unresolved) //$NON-NLS-1$ //$NON-NLS-2$
                    + ". The names are known to the platform, so this is not a misspelling."; //$NON-NLS-1$
                return r;
            }
            if (freshComposition)
            {
                // Attached only once something is in it: an empty typeExtension on an
                // object that never had one is a change with no meaning.
                block.eSet(feature, composition);
            }
            // An added primitive needs its qualifier element the same way it does on an
            // ordinary type composition, and the block names its qualifiers identically.
            BmDefinedTypeHelper.attachPrimitiveQualifiers(composition, r.added);
            r.ok = true;
            r.mutated = true;
            return r;
        }
        catch (Exception e)
        {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage(); //$NON-NLS-1$
            return r;
        }
    }

    /**
     * Reads the composition currently on the block, if any.
     *
     * @param block the extension block.
     * @param feature its {@code typeExtension} feature.
     * @return the composition, or null when the block has none yet.
     */
    private static TypeDescriptionExtension readComposition(ObjectExtension block,
        EStructuralFeature feature)
    {
        Object current = block.eGet(feature);
        return current instanceof TypeDescriptionExtension ? (TypeDescriptionExtension)current : null;
    }

    /**
     * Names of the types already in the composition, inherited and added alike.
     *
     * @param composition the composition to read, possibly empty.
     * @return the names, in no particular order.
     */
    private static Set<String> presentTypeNames(TypeDescriptionExtension composition)
    {
        Set<String> names = new LinkedHashSet<>();
        if (composition == null)
        {
            return names;
        }
        for (TypeExtension entry : composition.getTypes())
        {
            if (entry == null)
            {
                continue;
            }
            String name = BmDefinedTypeHelper.readTypeNameOf(entry.getType());
            if (name != null && !name.isEmpty())
            {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Reads the block's own {@code type} state where it has one, as a name.
     *
     * @param block the extension block.
     * @return the state's name, or null when the block keeps no such property.
     */
    private static String readPropertyState(EObject block)
    {
        EStructuralFeature typeFeature = block.eClass().getEStructuralFeature("type"); //$NON-NLS-1$
        if (typeFeature == null)
        {
            return null;
        }
        Object value = block.eGet(typeFeature);
        return value instanceof MdPropertyState ? ((MdPropertyState)value).getName() : null;
    }

    /**
     * Splits comma-joined arguments into single type names and normalises the Russian
     * spelling of a primitive, matching what an ordinary type composition accepts.
     *
     * @param typeFqns as the caller passed them.
     * @return one name per element, duplicates removed, order kept.
     */
    private static List<String> expand(List<String> typeFqns)
    {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : typeFqns)
        {
            if (raw == null)
            {
                continue;
            }
            for (String part : raw.split(",")) //$NON-NLS-1$
            {
                String s = part.trim();
                if (!s.isEmpty())
                {
                    out.add(BmDefinedTypeHelper.normalizePrimitiveFqn(s));
                }
            }
        }
        return new ArrayList<>(out);
    }
}
