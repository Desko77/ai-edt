/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.QName;
import com._1c.g5.v8.dt.xdto.model.Form;
import com._1c.g5.v8.dt.xdto.model.ObjectType;
import com._1c.g5.v8.dt.xdto.model.Package;
import com._1c.g5.v8.dt.xdto.model.Property;
import com._1c.g5.v8.dt.xdto.model.ValueType;
import com._1c.g5.v8.dt.xdto.model.Variety;
import com._1c.g5.v8.dt.xdto.model.XdtoFactory;
import com._1c.g5.v8.dt.xdto.model.resource.XdtoResource;

import ru.aiedt.mcp.server.Activator;

/**
 * File-based authoring of XDTO package content (the {@code Package.xdto} schema
 * beside an {@code XDTOPackage} metadata object).
 *
 * <p>An XDTO package's schema (namespaces, object types, value types,
 * properties) lives in {@code src/XDTOPackages/<name>/Package.xdto}, serialized
 * by {@code com._1c.g5.v8.dt.xdto.model.resource.XdtoSerializer}. The
 * {@link XdtoResource} (a plain EMF {@code ResourceImpl}) load/save that file
 * standalone - no BM transaction and no dtProject are needed (unlike the moxel
 * path). We therefore mutate the {@link Package} model directly on a private
 * resource and write the file, mirroring the .dcs disk-save pattern; EDT
 * re-imports the file into the BM on the next workspace refresh.
 *
 * <p>The default XSD namespace is used for primitive property/value types when
 * the caller does not supply one.
 */
public final class BmXdtoHelper
{
    /** Default namespace for primitive types (xs:string, xs:int, ...). */
    public static final String XSD_NS = "http://www.w3.org/2001/XMLSchema"; //$NON-NLS-1$

    /**
     * Sentinel a mutator may return to signal "success, but the model was not
     * changed - skip the file write" (e.g. an idempotent remove that matched
     * nothing). Distinct from {@code null} (success -> write) and an error
     * string (abort). Prevents an idempotent remove from rewriting the file,
     * triggering a needless BM re-import, or materializing an empty Package.xdto
     * when the file did not exist.
     */
    public static final String NO_CHANGE = "__xdto_no_change__"; //$NON-NLS-1$

    /**
     * What {@link #readSchema} and {@link #readSchemaFile} found.
     * <p>
     * Exactly one of the three is set: a loaded {@link Package}, an absent or empty file, or a load
     * error. A load error is never reported as an absent file.
     * </p>
     */
    public static final class SchemaRead
    {
        private final Package model;

        private final String loadError;

        private final boolean absentOrEmpty;

        private SchemaRead(Package model, String loadError, boolean absentOrEmpty)
        {
            this.model = model;
            this.loadError = loadError;
            this.absentOrEmpty = absentOrEmpty;
        }

        /**
         * @return a missing or empty file
         */
        static SchemaRead absent()
        {
            return new SchemaRead(null, null, true);
        }

        /**
         * @param loadError why the file did not parse
         * @return a file that is there but did not load
         */
        static SchemaRead failed(String loadError)
        {
            return new SchemaRead(null, loadError, false);
        }

        /**
         * @param model the loaded package
         * @return a schema that parsed
         */
        static SchemaRead loaded(Package model)
        {
            return new SchemaRead(model, null, false);
        }

        /**
         * @return the package when the file parsed, otherwise {@code null}
         */
        public Package model()
        {
            return model;
        }

        /**
         * @return why the file did not parse, or {@code null} when it parsed or is absent
         */
        public String loadError()
        {
            return loadError;
        }

        /**
         * @return {@code true} only when the file is missing or empty
         */
        public boolean absentOrEmpty()
        {
            return absentOrEmpty;
        }
    }

    private BmXdtoHelper()
    {
        // utility
    }

    /** Whether the EDT XDTO model API is reachable on this runtime. */
    public static boolean xdtoApiAvailable()
    {
        try
        {
            return XdtoFactory.eINSTANCE != null;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * Loads (or creates) the {@link Package} for the given XDTOPackage, runs the
     * mutator, and writes {@code Package.xdto} atomically. Returns {@code null}
     * on success or a descriptive error (the mutator may also return an error
     * string to abort without writing).
     *
     * @param project      the EDT project (must be open)
     * @param packageName  XDTOPackage name (the {@code <name>} folder)
     * @param defaultNsUri namespace assigned when the package is created fresh
     * @param mutator      receives the live {@link Package}; returns null to
     *                     proceed to save, or an error string to abort
     * @param dryRun       when true, run the mutator (validation) but do not
     *                     write the file
     * @return {@code null} on success, or a descriptive error. A package name that
     *         is empty, absolute, or not a single member of {@code src/XDTOPackages}
     *         is an error and nothing is written.
     */
    public static String mutatePackage(IProject project, String packageName, String defaultNsUri,
        Function<Package, String> mutator, boolean dryRun)
    {
        Located located = locate(project, packageName);
        if (located.refusal != null)
        {
            return located.refusal;
        }
        Path xdtoFile = located.file;
        URI uri = URI.createFileURI(xdtoFile.toAbsolutePath().toString());
        XdtoResource resource = new XdtoResource(uri);
        Package pkg;
        try
        {
            if (Files.exists(xdtoFile) && Files.size(xdtoFile) > 0)
            {
                resource.load(new ByteArrayInputStream(Files.readAllBytes(xdtoFile)),
                    Collections.emptyMap());
                EObject root = resource.getContents().isEmpty() ? null : resource.getContents().get(0);
                if (!(root instanceof Package))
                {
                    return "Package.xdto root is not an XDTO Package (" //$NON-NLS-1$
                        + (root == null ? "empty" : root.eClass().getName()) + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                pkg = (Package) root;
            }
            else
            {
                pkg = XdtoFactory.eINSTANCE.createPackage();
                pkg.setNsUri(defaultNsUri != null ? defaultNsUri : ""); //$NON-NLS-1$
                pkg.setElementFormQualified(false);
                pkg.setAttributeFormQualified(false);
                resource.getContents().add(pkg);
            }
        }
        catch (Exception e)
        {
            return "Failed to load Package.xdto: " + e.getClass().getSimpleName() //$NON-NLS-1$
                + ": " + e.getMessage(); //$NON-NLS-1$
        }

        String mutErr;
        try
        {
            mutErr = mutator.apply(pkg);
        }
        catch (RuntimeException re)
        {
            return "mutation failed: " + re.getClass().getSimpleName() + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + re.getMessage();
        }
        if (NO_CHANGE.equals(mutErr))
        {
            return null; // success, but nothing changed - do not write the file
        }
        if (mutErr != null)
        {
            return mutErr;
        }
        if (dryRun)
        {
            return null;
        }

        // Atomic write: serialize to a buffer first (XdtoResource.doSave honours
        // the OutputStream); only replace the file when serialization succeeds.
        try
        {
            Files.createDirectories(xdtoFile.getParent());
            ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
            resource.save(buf, Collections.emptyMap());
            Files.write(xdtoFile, buf.toByteArray());
        }
        catch (Exception e)
        {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            Activator.logWarning("XDTO save failed for " + packageName + ":\n" + sw); //$NON-NLS-1$ //$NON-NLS-2$
            return "Failed to save Package.xdto: " + e.getClass().getSimpleName() //$NON-NLS-1$
                + ": " + e.getMessage(); //$NON-NLS-1$
        }
        refresh(project, packageName);
        return null;
    }

    /**
     * Reads the schema file of one XDTO package.
     * <p>
     * A missing file and an empty file are {@link SchemaRead#absentOrEmpty()}. A file that is there
     * but does not load, or whose root is not a {@link Package}, is {@link SchemaRead#loadError()}
     * and names the reason. A name that is not one member of {@code src/XDTOPackages} is the same
     * kind of error and the file outside the collection is not opened.
     * </p>
     *
     * @param project the EDT project
     * @param packageName the XDTO package name
     * @return the schema, an absent file, or the reason the file could not be read
     */
    public static SchemaRead readSchema(IProject project, String packageName)
    {
        Located located = locate(project, packageName);
        if (located.refusal != null)
        {
            return SchemaRead.failed(located.refusal);
        }
        SchemaRead read = readSchemaFile(located.file);
        if (read.loadError() != null)
        {
            Activator.logWarning("readPackage failed for " + packageName + ": " + read.loadError()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return read;
    }

    /**
     * Reads one {@code Package.xdto} file that has already been resolved.
     *
     * @param xdtoFile the schema file; may name a file that is not there
     * @return the schema, an absent or empty file, or the load failure
     */
    public static SchemaRead readSchemaFile(Path xdtoFile)
    {
        if (xdtoFile == null || Files.notExists(xdtoFile))
        {
            return SchemaRead.absent();
        }
        try
        {
            if (Files.size(xdtoFile) == 0)
            {
                return SchemaRead.absent();
            }
            XdtoResource resource = new XdtoResource(
                URI.createFileURI(xdtoFile.toAbsolutePath().toString()));
            resource.load(new ByteArrayInputStream(Files.readAllBytes(xdtoFile)),
                Collections.emptyMap());
            EObject root = resource.getContents().isEmpty() ? null : resource.getContents().get(0);
            if (!(root instanceof Package))
            {
                return SchemaRead.failed("Package.xdto root is not an XDTO Package (" //$NON-NLS-1$
                    + (root == null ? "empty" : root.eClass().getName()) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return SchemaRead.loaded((Package) root);
        }
        catch (Exception e)
        {
            return SchemaRead.failed("Failed to load Package.xdto: " + e.getClass().getSimpleName() //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Why {@code packageName} may not be joined onto {@code src/XDTOPackages}.
     *
     * @param project the EDT project
     * @param packageName the package name
     * @return the refusal, or {@code null} when the name is one member of that directory
     */
    public static String refusePackagePath(IProject project, String packageName)
    {
        return locate(project, packageName).refusal;
    }

    // ---- model mutation helpers (operate on a live Package) ----

    /** Adds an ObjectType (returns error string or null). */
    public static String addObjectType(Package pkg, String name, Boolean open, Boolean isAbstract,
        Boolean mixed)
    {
        if (name == null || name.isEmpty())
        {
            return "object type name is required"; //$NON-NLS-1$
        }
        if (findObjectType(pkg, name) != null)
        {
            return "object type '" + name + "' already exists"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        ObjectType ot = XdtoFactory.eINSTANCE.createObjectType();
        ot.setName(name);
        if (open != null)
        {
            ot.setOpen(open.booleanValue());
        }
        if (isAbstract != null)
        {
            ot.setAbstract(isAbstract.booleanValue());
        }
        if (mixed != null)
        {
            ot.setMixed(mixed.booleanValue());
        }
        pkg.getObjects().add(ot);
        return null;
    }

    /**
     * Adds a ValueType (name + variety). For a LIST variety an item type
     * (itemTypeLocal/itemTypeNs) may be supplied; restriction facets / union
     * members are a follow-up. Returns error string or null.
     */
    public static String addValueType(Package pkg, String name, String variety, String itemTypeLocal,
        String itemTypeNs)
    {
        if (name == null || name.isEmpty())
        {
            return "value type name is required"; //$NON-NLS-1$
        }
        if (findValueType(pkg, name) != null)
        {
            return "value type '" + name + "' already exists"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        ValueType vt = XdtoFactory.eINSTANCE.createValueType();
        vt.setName(name);
        Variety v = parseVariety(variety);
        if (v != null)
        {
            vt.setVariety(v);
        }
        if (itemTypeLocal != null && !itemTypeLocal.isEmpty())
        {
            vt.setItemType(buildQName(itemTypeLocal, itemTypeNs));
        }
        pkg.getTypes().add(vt);
        return null;
    }

    /**
     * Adds a Property to an object type (when {@code objectTypeName} is given) or
     * to the package itself (package-level property). Returns error or null.
     */
    public static String addProperty(Package pkg, String objectTypeName, String name,
        String typeLocal, String typeNs, Integer lowerBound, Integer upperBound, String form,
        Boolean nillable)
    {
        if (name == null || name.isEmpty())
        {
            return "property name is required"; //$NON-NLS-1$
        }
        if (lowerBound != null && lowerBound.intValue() < 0)
        {
            return "lowerBound must be >= 0 (use upperBound=-1 for unbounded)"; //$NON-NLS-1$
        }
        List<Property> target;
        if (objectTypeName != null && !objectTypeName.isEmpty())
        {
            ObjectType ot = findObjectType(pkg, objectTypeName);
            if (ot == null)
            {
                return "object type '" + objectTypeName + "' not found"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            target = ot.getProperties();
        }
        else
        {
            target = pkg.getProperties();
        }
        for (Property p : target)
        {
            if (name.equals(p.getName()))
            {
                return "property '" + name + "' is already present on " //$NON-NLS-1$ //$NON-NLS-2$
                    + (objectTypeName != null ? objectTypeName : "the package"); //$NON-NLS-1$
            }
        }
        Property prop = XdtoFactory.eINSTANCE.createProperty();
        prop.setName(name);
        if (typeLocal != null && !typeLocal.isEmpty())
        {
            prop.setType(buildQName(typeLocal, typeNs));
        }
        if (lowerBound != null)
        {
            prop.setLowerBound(lowerBound.intValue());
        }
        if (upperBound != null)
        {
            prop.setUpperBound(upperBound.intValue());
        }
        if (nillable != null)
        {
            prop.setNillable(nillable.booleanValue());
        }
        Form f = parseForm(form);
        if (f != null)
        {
            prop.setForm(f);
        }
        target.add(prop);
        return null;
    }

    /** Removes an object type by name (returns true when removed). */
    public static boolean removeObjectType(Package pkg, String name)
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }
        ObjectType ot = findObjectType(pkg, name);
        if (ot == null)
        {
            return false;
        }
        pkg.getObjects().remove(ot);
        return true;
    }

    /** Removes a value type by name (returns true when removed). */
    public static boolean removeValueType(Package pkg, String name)
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }
        ValueType vt = findValueType(pkg, name);
        if (vt == null)
        {
            return false;
        }
        pkg.getTypes().remove(vt);
        return true;
    }

    /**
     * Removes a property from an object type (or the package when objectTypeName
     * is null/empty). Returns true when removed.
     */
    public static boolean removeProperty(Package pkg, String objectTypeName, String name)
    {
        if (name == null || name.isEmpty())
        {
            return false;
        }
        List<Property> target;
        if (objectTypeName != null && !objectTypeName.isEmpty())
        {
            ObjectType ot = findObjectType(pkg, objectTypeName);
            if (ot == null)
            {
                return false;
            }
            target = ot.getProperties();
        }
        else
        {
            target = pkg.getProperties();
        }
        for (int i = 0; i < target.size(); i++)
        {
            if (name.equals(target.get(i).getName()))
            {
                target.remove(i);
                return true;
            }
        }
        return false;
    }

    private static ObjectType findObjectType(Package pkg, String name)
    {
        for (ObjectType ot : pkg.getObjects())
        {
            if (name.equals(ot.getName()))
            {
                return ot;
            }
        }
        return null;
    }

    private static ValueType findValueType(Package pkg, String name)
    {
        for (ValueType vt : pkg.getTypes())
        {
            if (name.equals(vt.getName()))
            {
                return vt;
            }
        }
        return null;
    }

    private static QName buildQName(String localName, String nsUri)
    {
        QName q = McoreFactory.eINSTANCE.createQName();
        q.setName(localName);
        q.setNsUri(nsUri != null && !nsUri.isEmpty() ? nsUri : XSD_NS);
        return q;
    }

    private static Variety parseVariety(String variety)
    {
        if (variety == null || variety.isEmpty())
        {
            return null;
        }
        switch (variety.trim().toLowerCase())
        {
            case "atomic": //$NON-NLS-1$
                return Variety.ATOMIC;
            case "list": //$NON-NLS-1$
                return Variety.LIST;
            case "union": //$NON-NLS-1$
                return Variety.UNION;
            default:
                return null;
        }
    }

    private static Form parseForm(String form)
    {
        if (form == null || form.isEmpty())
        {
            return null;
        }
        switch (form.trim().toLowerCase())
        {
            case "element": //$NON-NLS-1$
                return Form.ELEMENT;
            case "attribute": //$NON-NLS-1$
                return Form.ATTRIBUTE;
            case "text": //$NON-NLS-1$
                return Form.TEXT;
            default:
                return null;
        }
    }

    /**
     * The {@code Package.xdto} path for one package, or why the name cannot be used.
     */
    private static final class Located
    {
        /** The schema file when {@link #refusal} is {@code null}. */
        final Path file;

        /** Why the name was refused, or {@code null} when {@link #file} is set. */
        final String refusal;

        private Located(Path file, String refusal)
        {
            this.file = file;
            this.refusal = refusal;
        }
    }

    /**
     * Resolves {@code src/XDTOPackages/&lt;name&gt;/Package.xdto}, or refuses the name.
     *
     * @param project the EDT project
     * @param packageName the package name
     * @return the schema path, or the refusal
     */
    private static Located locate(IProject project, String packageName)
    {
        if (project == null || packageName == null || packageName.isEmpty())
        {
            return new Located(null, "project and packageName are required"); //$NON-NLS-1$
        }
        if (project.getLocation() == null)
        {
            return new Located(null,
                "Cannot resolve Package.xdto path for XDTOPackage." + packageName); //$NON-NLS-1$
        }
        Path collection = project.getLocation().toFile().toPath().resolve("src") //$NON-NLS-1$
            .resolve("XDTOPackages"); //$NON-NLS-1$
        CollectionMemberPath.Place place = CollectionMemberPath.member(collection, "packageName", //$NON-NLS-1$
            packageName);
        if (place.refusal() != null)
        {
            return new Located(null, place.refusal());
        }
        return new Located(place.path().resolve("Package.xdto"), null); //$NON-NLS-1$
    }

    private static void refresh(IProject project, String packageName)
    {
        try
        {
            IFolder folder = project.getFolder("src").getFolder("XDTOPackages") //$NON-NLS-1$ //$NON-NLS-2$
                .getFolder(packageName);
            if (folder.exists())
            {
                folder.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
            else
            {
                project.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
        }
        catch (CoreException ce)
        {
            Activator.logWarning("XDTO Package.xdto written but workspace refresh failed: " //$NON-NLS-1$
                + ce.getMessage());
        }
    }
}
