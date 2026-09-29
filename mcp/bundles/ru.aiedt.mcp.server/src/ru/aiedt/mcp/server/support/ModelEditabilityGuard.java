/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.e1c.g5.v8.dt.distribution.IDistributionSupportManager;
import com.e1c.g5.v8.dt.distribution.model.DistributionSupport;
import com.e1c.g5.v8.dt.distribution.model.UserSupportMode;

import ru.aiedt.mcp.server.Activator;

/**
 * Refuses a write into a metadata object the support registry closed for changes.
 * <p>
 * An object taken from a vendor configuration and left in the mode EDT calls "changes are not
 * allowed" is read-only: the environment offers no way to change it, so a write into one would land
 * in the in-memory model and rewrite its module file, where the next vendor update replaces it -
 * with nothing in the answer saying so.
 * </p>
 * <p>
 * This guard is the one place the question is asked, and it is asked on the way into every write
 * path: {@link BmObjectHelper#executeWriteOnObject} for the metadata operations, the form writes
 * through {@code BmFormHelper} and the module writes through {@code ModuleSourceWriter}. Reads have
 * their own entries - {@link BmObjectHelper#executeReadOnObject} and
 * {@code BmFormHelper.executeFormReadOperation} - and no read asks this question: a form or a
 * template of a closed object stays readable, exactly as EDT reads it. On a write the question runs
 * before the model is touched and before the transaction opens, a preview included, so a dry run
 * answers the same refusal a real call would.
 * </p>
 * <p>
 * The object judged is the nearest one, the way EDT judges: the write aimed at a form asks the form
 * ({@code BasicForm}), the write aimed at an attribute or a tabular section asks that child - each
 * of them is a metadata object with a support record of its own - and only a write whose address
 * names no child asks the owner the address spells. The configuration root is a metadata object too
 * and is judged by its own record.
 * </p>
 * <p>
 * The answer comes from the support registry itself - {@link IDistributionSupportManager}, the same
 * service {@code SupportRegistryTool} reads through - rather than from anything this server keeps.
 * The environment's own judgement is reported next to the recorded mode: a refusal names the object,
 * the mode the registry holds for it, and what lifts it.
 * </p>
 * <p>
 * Three states pass, and each of them deliberately. An object with no supplier is not on support at
 * all; an object whose mode is {@code ChangesAllowed} is one the user has already opened for
 * editing; and an environment that cannot be asked - no support subsystem installed, a project whose
 * configuration is not loaded - is never a reason to refuse a write, since the guard would then
 * block work it knows nothing about. Only the two answers together, a registry record that forbids
 * changes and an environment that confirms it, produce a refusal.
 * </p>
 */
public final class ModelEditabilityGuard
{
    /**
     * The name this guard is known by inside the server. It is not a tool and not an operation: no
     * visible name of a tool, an operation or a preference key changes because of it.
     */
    public static final String NAME = "model_editability_guard"; //$NON-NLS-1$

    /** The one mode the registry writes for an object whose changes are not allowed. */
    private static final String CHANGES_NOT_ALLOWED = "ChangesNotAllowed"; //$NON-NLS-1$

    /** The mode an object carries once the user has opened it for editing. */
    private static final String CHANGES_ALLOWED = "ChangesAllowed"; //$NON-NLS-1$

    /** The child kind an address writes as {@code Subsystem}. */
    private static final String SUBSYSTEM_KIND = "subsystem"; //$NON-NLS-1$

    /** The feature a metadata object holds its subsystems in, below the configuration root. */
    private static final String SUBSYSTEMS_FEATURE = "subsystems"; //$NON-NLS-1$

    /**
     * What the environment says about one object. Every field is filled in by
     * {@link #askSupportService}, and a test fills them in by hand.
     */
    public static final class Editability
    {
        /** Whether the environment answered at all. */
        public boolean answered;

        /** Whether the environment would let this object be changed. */
        public boolean canEdit;

        /** The support mode the registry records, as the model spells it; <code>null</code> for none. */
        public String userMode;

        /** How the support service was reached, for the answer. */
        public String route;

        /** Why nothing could be said, when {@link #answered} is <code>false</code>. */
        public String cannotTell;

        /**
         * Whether the registry holds a record for this object that forbids changing it.
         *
         * @return <code>true</code> only for the mode that closes the object for changes
         */
        public boolean recordedAsNotAllowed()
        {
            return CHANGES_NOT_ALLOWED.equals(userMode);
        }

        /**
         * Whether the registry holds a record that opens this object for changes.
         *
         * @return <code>true</code> for the mode a user's own decision puts on an object
         */
        public boolean recordedAsAllowed()
        {
            return CHANGES_ALLOWED.equals(userMode);
        }
    }

    /**
     * Answers the editability question for one object.
     * <p>
     * The production implementation reads the support service. A test substitutes its own, which is
     * the only way this question can be asked without a project on support: the acceptance of a
     * support mode cannot be arranged from a unit test.
     * </p>
     */
    public interface Probe
    {
        /**
         * Asks about one object.
         *
         * @param project the project the object belongs to; may be <code>null</code>
         * @param judged the metadata object the support record is asked about; never
         *            <code>null</code>
         * @return what the environment says; never <code>null</code>
         */
        Editability ask(IProject project, MdObject judged);
    }

    /**
     * Resolves the owner part of an address - {@code Type.Name} or the {@code Configuration} root -
     * to the metadata object it names.
     * <p>
     * The production implementation reads the configuration of the project. A test substitutes its
     * own, so the wiring of the guard into the write paths can be asked about without a live 1C:EDT
     * project behind the address.
     * </p>
     */
    public interface OwnerResolver
    {
        /**
         * Resolves the owner an address spells.
         *
         * @param project the project being written to; may be <code>null</code>
         * @param ownerFqn the owner address: {@code Type.Name}, or the one-segment
         *            {@code Configuration}
         * @return the object, or <code>null</code> when the address names nothing this project holds
         */
        MdObject owner(IProject project, String ownerFqn);
    }

    /** Where the answer comes from. Replaced only by a test, and put back by it. */
    private static volatile Probe probe = ModelEditabilityGuard::askSupportService;

    /** Where an owner address resolves. Replaced only by a test, and put back by it. */
    private static volatile OwnerResolver ownerResolver = ModelEditabilityGuard::resolveByConfiguration;

    private ModelEditabilityGuard()
    {
        // utility
    }

    /**
     * Substitutes the source of answers, for a test.
     *
     * @param replacement the answers to use; <code>null</code> puts the support service back
     */
    public static void useProbeForTest(Probe replacement)
    {
        probe = replacement != null ? replacement : ModelEditabilityGuard::askSupportService;
    }

    /**
     * Substitutes the resolution of owner addresses, for a test.
     *
     * @param replacement the resolution to use; <code>null</code> puts the configuration walk back
     */
    public static void useOwnerResolverForTest(OwnerResolver replacement)
    {
        ownerResolver =
            replacement != null ? replacement : ModelEditabilityGuard::resolveByConfiguration;
    }

    /**
     * Judges a write into the object a caller holds.
     * <p>
     * The object judged is the nearest metadata object at or above the one handed in - the way EDT
     * itself judges. An attribute is a metadata object of its own and is judged by its own record,
     * and the configuration root is judged by its record like any object. A form model is held by
     * its {@code BasicForm} through a reference rather than by containment, so the chain from one
     * holds no metadata object and the verdict passes; a form reached by address is judged by that
     * address.
     * </p>
     *
     * @param project the project being written to; may be <code>null</code>
     * @param any the object about to be changed, or any object inside it
     * @return a verdict that blocks the write, or one that passes
     */
    public static MetadataGuards.Verdict checkObject(IProject project, EObject any)
    {
        MdObject judged = nearestMdObject(any);
        if (judged == null)
        {
            // No metadata object to judge: the write is aimed at something the registry has no
            // record for - a form model, which its BasicForm holds by reference, or a root outside
            // the metadata model.
            return MetadataGuards.Verdict.pass();
        }
        return decide(fqnOf(judged), probe.ask(project, judged));
    }

    /**
     * Judges a write into the object an address names.
     * <p>
     * Used where the write path has an address rather than the object - the module writer knows the
     * module path, the form writer the form's address. The address resolves to the nearest metadata
     * object it spells: {@code Catalog.X.Form.Y} asks the form {@code Y}, a child segment that does
     * not resolve falls back to the deepest one that did, and an address that resolves to nothing is
     * not judged at all - the write is refused or accepted further along by the code that does
     * resolve it.
     * </p>
     *
     * @param project the project being written to
     * @param fqn the address of the object or of anything it contains
     * @return a verdict that blocks the write, or one that passes
     */
    public static MetadataGuards.Verdict checkFqn(IProject project, String fqn)
    {
        if (fqn == null || fqn.trim().isEmpty())
        {
            return MetadataGuards.Verdict.pass();
        }
        String trimmed = fqn.trim();
        MdObject judged = resolveAddress(project, trimmed);
        if (judged == null)
        {
            return MetadataGuards.Verdict.pass();
        }
        return decide(trimmed, probe.ask(project, judged));
    }

    /**
     * The metadata object an address belongs to, as the address spells it: the first two segments of
     * {@code Type.Name}.
     * <p>
     * Every address this server takes - {@code Catalog.Goods}, {@code Catalog.Goods.Form.ItemForm},
     * {@code Catalogs/Goods/ObjectModule.bsl}, {@code Subsystem.A.Subsystem.B} - carries the owning
     * object's name in its first two segments. Where an address is one segment long it is returned
     * unchanged, since there is no name in it to shorten.
     * </p>
     *
     * @param fqn the address; may be <code>null</code>
     * @return the owning object's address, or the input when it has no second segment
     */
    public static String ownerFqnOf(String fqn)
    {
        if (fqn == null)
        {
            return null;
        }
        String trimmed = fqn.trim();
        int firstDot = trimmed.indexOf('.');
        if (firstDot <= 0)
        {
            return trimmed;
        }
        int secondDot = trimmed.indexOf('.', firstDot + 1);
        return secondDot < 0 ? trimmed : trimmed.substring(0, secondDot);
    }

    /**
     * The nearest metadata object at or above the one handed in, the object the support registry is
     * asked about.
     * <p>
     * The walk goes up the containment chain and stops at the first metadata object on it: an
     * attribute, a tabular section and a nested subsystem are metadata objects of their own and
     * resolve to themselves, and the configuration root is a metadata object too and is judged by
     * its own record rather than passed by. This mirrors the walk
     * {@code DistributionSupportManager.canEdit(EObject)} makes when EDT decides the same question.
     * A form model is held by its {@code BasicForm} through a reference rather than by containment,
     * so the chain from a form model holds no metadata object and this returns <code>null</code>; a
     * form is judged by the address that names it, which resolves the {@code BasicForm} itself.
     * </p>
     *
     * @param any the object to walk up from; may be <code>null</code>
     * @return the nearest metadata object, or <code>null</code> when the chain holds none
     */
    public static MdObject nearestMdObject(EObject any)
    {
        EObject current = any;
        while (current != null)
        {
            if (current instanceof MdObject)
            {
                return (MdObject)current;
            }
            current = current.eContainer();
        }
        return null;
    }

    /**
     * Names a metadata object as the rest of this server names it.
     *
     * @param object the object; may be <code>null</code>
     * @return the address, or <code>null</code> when there is no object to name
     */
    public static String fqnOf(MdObject object)
    {
        if (object == null)
        {
            return null;
        }
        String name = object.getName();
        return object.eClass().getName() + "." + (name == null ? "?" : name); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Turns what the environment said into a verdict.
     * <p>
     * The refusal names the object as the caller knows it, the mode the registry holds and what
     * lifts it, so that the caller can act without asking the registry tool separately. The check
     * runs only where both answers agree: the registry records the object as closed for changes and
     * the environment confirms it will not edit the object.
     * </p>
     *
     * @param fqn the object, as the answer should name it
     * @param answer what the environment said; may be <code>null</code>
     * @return a verdict that blocks the write, or one that passes
     */
    public static MetadataGuards.Verdict decide(String fqn, Editability answer)
    {
        if (answer == null || !answer.answered || answer.canEdit || !answer.recordedAsNotAllowed())
        {
            return MetadataGuards.Verdict.pass();
        }
        String hint = "Open the object for changes in the support settings of the project, or make " //$NON-NLS-1$
            + "the change in a configuration extension."; //$NON-NLS-1$
        MetadataGuards.ErrorTag tag = new MetadataGuards.ErrorTag(ErrorTags.SUPPORT_LOCK.wire())
            .put("object", fqn) //$NON-NLS-1$
            .put("userSupportMode", answer.userMode) //$NON-NLS-1$
            .put("canEdit", Boolean.valueOf(answer.canEdit)) //$NON-NLS-1$
            .put("guard", NAME) //$NON-NLS-1$
            .put("hint", hint); //$NON-NLS-1$
        return MetadataGuards.Verdict.block(
            "Object '" + fqn + "' is on vendor support and changes to it are not allowed (mode " //$NON-NLS-1$ //$NON-NLS-2$
                + answer.userMode + "). Nothing was changed.", //$NON-NLS-1$
            hint, tag);
    }

    /**
     * The refusal's structured tag as one line, for a path that answers in text.
     * <p>
     * A write path that returns a plain string cannot carry the tag the way a structured answer
     * does; this line keeps the same fields in the same shape, and {@link #parseSupportLockLine}
     * reads them back where the string becomes structured again.
     * </p>
     *
     * @param verdict a blocked verdict carrying a tag; a passing verdict or one without a tag answers
     *            <code>null</code>
     * @return the line {@code supportLock: <fields>}, or <code>null</code>
     */
    public static String supportLockLine(MetadataGuards.Verdict verdict)
    {
        if (verdict == null || !verdict.blocked || verdict.tag == null)
        {
            return null;
        }
        StringBuilder sb = new StringBuilder(ErrorTags.SUPPORT_LOCK.wire()).append(':'); //$NON-NLS-1$
        for (Map.Entry<String, Object> field : verdict.tag.data.entrySet())
        {
            sb.append(' ').append(field.getKey()).append('=').append(field.getValue());
        }
        return sb.toString();
    }

    /**
     * Reads the {@link #supportLockLine} back out of an answer text.
     * <p>
     * A field value may hold spaces - the hint does - so the line splits only before a token that
     * carries an equals sign, and everything after the last equals sign belongs to its own field.
     * </p>
     *
     * @param text the answer text that may carry the line
     * @return the tag fields, or <code>null</code> when the text carries no line
     */
    public static Map<String, Object> parseSupportLockLine(String text)
    {
        if (text == null)
        {
            return null;
        }
        int at = text.indexOf(ErrorTags.SUPPORT_LOCK.wire() + ":"); //$NON-NLS-1$
        if (at < 0)
        {
            return null;
        }
        String line = text.substring(at);
        int end = line.indexOf('\n');
        if (end >= 0)
        {
            line = line.substring(0, end);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String part : line.split(" +(?=[^ =]+\\=)")) //$NON-NLS-1$
        {
            int equals = part.indexOf('=');
            if (equals <= 0 || equals == part.length() - 1)
            {
                continue;
            }
            fields.put(part.substring(0, equals), part.substring(equals + 1));
        }
        return fields.isEmpty() ? null : fields;
    }

    /**
     * Asks the support service about one object.
     *
     * @param project the project the object belongs to
     * @param judged the metadata object carrying the support record
     * @return what the service said; {@link Editability#answered} is <code>false</code> when it could
     *         not be asked
     */
    private static Editability askSupportService(IProject project, MdObject judged)
    {
        Editability answer = new Editability();
        if (project == null || judged == null)
        {
            answer.cannotTell = "no project or no object to ask about"; //$NON-NLS-1$
            return answer;
        }
        try
        {
            BmSupportRegistryHelper.Service service = BmSupportRegistryHelper.findService();
            if (service.manager == null)
            {
                answer.cannotTell = service.failure;
                Activator.logWarning("model_editability_guard: " + answer.cannotTell); //$NON-NLS-1$
                return answer;
            }
            answer.route = service.route;
            DistributionSupport support = service.manager.getDistributionSupport(project);
            if (support == null)
            {
                // Not on support: no object of this project has a mode, so nothing is closed.
                answer.cannotTell = project.getName()
                    + " is not on support, so no object of it is closed for changes"; //$NON-NLS-1$
                return answer;
            }
            UserSupportMode mode = service.manager.getUserSupportMode(judged);
            answer.userMode = mode == null ? null : mode.getName();
            answer.canEdit = service.manager.canEdit(judged);
            answer.answered = true;
        }
        catch (RuntimeException | LinkageError cannotAsk)
        {
            // Named rather than swallowed, but not fatal: a support subsystem this server cannot
            // reach is not evidence that the object is closed. The reason is a warning, not a debug
            // line: a check that quietly did not run reads as a check that passed.
            answer.cannotTell = "the support service could not be asked: " + cannotAsk; //$NON-NLS-1$
            Activator.logWarning("model_editability_guard: " + answer.cannotTell); //$NON-NLS-1$
        }
        return answer;
    }

    /**
     * Resolves an address to the nearest metadata object it spells.
     * <p>
     * The owner part goes through {@link #ownerResolver}; whatever it returns is walked further by
     * the address's remaining {@code Kind.Name} pairs, so an address that names a form, an attribute
     * or a nested subsystem is judged by that child. A pair that does not resolve stops the walk:
     * the deepest object that did resolve is judged, and an address whose owner resolves to nothing
     * is not judged at all. A lone trailing segment - the {@code ObjectModule} marker of a module
     * address, the {@code .Form} suffix of a BM form address - names no object of its own and is
     * ignored.
     * </p>
     *
     * @param project the project holding the object
     * @param fqn the address, already trimmed
     * @return the nearest object the address resolves to, or <code>null</code>
     */
    private static MdObject resolveAddress(IProject project, String fqn)
    {
        MdObject current = ownerResolver.owner(project, ownerFqnOf(fqn));
        if (current == null)
        {
            return null;
        }
        int firstDot = fqn.indexOf('.');
        if (firstDot <= 0)
        {
            return current;
        }
        int secondDot = fqn.indexOf('.', firstDot + 1);
        if (secondDot < 0)
        {
            return current;
        }
        String[] rest = fqn.substring(secondDot + 1).split("\\."); //$NON-NLS-1$
        for (int i = 0; i + 1 < rest.length; i += 2)
        {
            MdObject child = childByKindAndName(current, rest[i], rest[i + 1]);
            if (child == null)
            {
                break;
            }
            current = child;
        }
        return current;
    }

    /**
     * Resolves the owner part of an address against the configuration of the project.
     * <p>
     * The one-segment {@code Configuration} names the configuration root itself - a metadata object
     * with a support record of its own - except in an external-object project, where
     * {@code getConfiguration} answers with a foreign project's configuration and judging by that
     * record would refuse work aimed at the external roots instead.
     * </p>
     *
     * @param project the project holding the object; may be <code>null</code>
     * @param ownerFqn the owner address: {@code Type.Name}, or {@code Configuration}
     * @return the object, or <code>null</code> when the address names nothing this project holds
     */
    private static MdObject resolveByConfiguration(IProject project, String ownerFqn)
    {
        if (project == null || ownerFqn == null || ownerFqn.isEmpty())
        {
            return null;
        }
        try
        {
            if (ownerFqn.indexOf('.') < 0 && "Configuration".equalsIgnoreCase(ownerFqn.trim())) //$NON-NLS-1$
            {
                if (ExternalProjectResolver.isExternalProject(project))
                {
                    return null;
                }
                return SubsystemMembership.configurationOf(project);
            }
            Configuration configuration = SubsystemMembership.configurationOf(project);
            if (configuration == null)
            {
                return null;
            }
            String normalized = MetadataTypeCatalog.normalizeFqn(ownerFqn);
            String[] parts = normalized.split("\\.", 2); //$NON-NLS-1$
            if (parts.length < 2)
            {
                return null;
            }
            return MetadataTypeCatalog.findObject(configuration, parts[0], parts[1]);
        }
        catch (RuntimeException | LinkageError cannotResolve)
        {
            Activator.logWarning("model_editability_guard: could not resolve " + ownerFqn + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + cannotResolve);
            return null;
        }
    }

    /**
     * Finds the direct child of a metadata object a {@code Kind.Name} address pair names.
     * <p>
     * Most children this guard has to reach - a form, an attribute, a tabular section, a command, a
     * template - are direct contained metadata objects. The kind matches the child's class name by
     * suffix ({@code BasicForm} for {@code Form}, {@code CatalogAttribute} for {@code Attribute}),
     * the name matches case-insensitively, the way the rest of this server reads names.
     * </p>
     * <p>
     * A subsystem below the root is not among those: {@code subsystems} holds them by reference, so
     * the containment walk cannot see them and an address like
     * {@code Subsystem.A.Subsystem.B} would be judged by {@code A}. The subsystems the object holds
     * are read from that feature instead - the way the write path finds a nested subsystem.
     * </p>
     *
     * @param owner the object to look inside
     * @param kind the child kind as the address spells it
     * @param name the child name as the address spells it
     * @return the child, or <code>null</code> when no direct child matches both
     */
    private static MdObject childByKindAndName(MdObject owner, String kind, String name)
    {
        if (name == null || name.isEmpty())
        {
            return null;
        }
        String wanted = kind.toLowerCase(Locale.ROOT);
        if (SUBSYSTEM_KIND.equals(wanted))
        {
            MdObject subsystem = referencedChild(owner, SUBSYSTEMS_FEATURE, name);
            if (subsystem != null)
            {
                return subsystem;
            }
        }
        for (EObject child : owner.eContents())
        {
            if (!(child instanceof MdObject))
            {
                continue;
            }
            MdObject candidate = (MdObject)child;
            String childName = candidate.getName();
            if (childName == null || !childName.equalsIgnoreCase(name))
            {
                continue;
            }
            String className = candidate.eClass().getName().toLowerCase(Locale.ROOT);
            if (className.equals(wanted) || className.endsWith(wanted))
            {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Finds a child an object holds by reference, which {@link EObject#eContents()} therefore does
     * not list.
     * <p>
     * The one such child this guard reaches is a nested subsystem: {@code Configuration} and
     * {@code Subsystem} both hold the subsystems below them in a {@code subsystems} feature. The
     * feature is read by name rather than through a typed call, so the guard keeps working where the
     * metadata model it runs against does not carry it.
     * </p>
     *
     * @param owner the object to look inside
     * @param featureName the name of the feature that holds those children
     * @param name the child name, matched case-insensitively
     * @return the child, or <code>null</code> when the object holds none by that name
     */
    private static MdObject referencedChild(MdObject owner, String featureName, String name)
    {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(featureName);
        if (feature == null)
        {
            return null;
        }
        Object held = owner.eGet(feature);
        if (!(held instanceof Collection))
        {
            return null;
        }
        for (Object child : (Collection<?>)held)
        {
            if (!(child instanceof MdObject))
            {
                continue;
            }
            String childName = ((MdObject)child).getName();
            if (childName != null && childName.equalsIgnoreCase(name))
            {
                return (MdObject)child;
            }
        }
        return null;
    }
}
