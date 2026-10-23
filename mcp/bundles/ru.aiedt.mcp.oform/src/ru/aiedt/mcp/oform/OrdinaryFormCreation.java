/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.oform.layout.MainAttributes;
import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.support.BmObjectHelper;
import ru.aiedt.mcp.server.support.DefaultLanguage;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.support.UiSync;

/**
 * Creates an ordinary form. The write is three steps and nothing is written before all three
 * can be named: the container file, then one transaction of the model (the form and, when asked,
 * the owner's default), then the owner's mark in the sync baselines. A failure of the file or
 * of the transaction removes what the file step created. A failure of the mark leaves the form
 * and says so.
 */
public final class OrdinaryFormCreation
{
    /** Writes the container and removes what it created. */
    public interface ContainerWriter
    {
        /**
         * @param relativeDirectory the form's folder, workspace-relative
         * @return whether that folder is already there
         */
        boolean directoryExists(String relativeDirectory);

        /**
         * Writes the container bytes.
         *
         * @param relativeFile the container, workspace-relative
         * @param bytes the container
         * @throws IOException when the file was not written
         */
        void write(String relativeFile, byte[] bytes) throws IOException;

        /**
         * Removes every folder {@link #write} created.
         *
         * @return the path that could not be removed, or {@code null} when nothing is left
         */
        String removeCreated();
    }

    /** The form's entry on its owner. The name check reads; {@link #commit} is the one transaction. */
    public interface ModelWriter
    {
        /**
         * @param formName the form's name
         * @return why the name is not free, or {@code null} when it is
         */
        String nameTaken(String formName);

        /**
         * Creates the form and, when asked, names it the owner's default.
         *
         * @param formName the form's name
         * @param language the synonym's language
         * @param synonym the synonym
         * @param setAsDefault whether to name it the default
         * @param defaultSetter the owner property, or {@code null}
         * @return why the transaction failed, or {@code null}
         */
        String commit(String formName, String language, String synonym, boolean setAsDefault, String defaultSetter);
    }

    /** Marks the owner so the next update carries the container. */
    public interface DeliveryMarker
    {
        /**
         * @return the delivery statement
         * @throws Exception when the owner was not marked
         */
        String mark() throws Exception;
    }

    private static final String UPDATE_HINT = "update_database fullUpdate=true"; //$NON-NLS-1$

    private OrdinaryFormCreation()
    {
        // static utility
    }

    /**
     * Creates the form.
     *
     * @param project the project
     * @param params the tool's arguments
     * @return the answer
     */
    public static Map<String, Object> create(IProject project, Map<String, String> params)
    {
        Map<String, Object> answer = base(project == null ? null : project.getName());
        String ownerFqn = text(params, "ownerFqn"); //$NON-NLS-1$
        String name = text(params, "name") != null ? text(params, "name") : text(params, "formName"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (ownerFqn == null || name == null)
        {
            return refused(answer, "create_form needs ownerFqn (Type.Object) and name."); //$NON-NLS-1$
        }
        String[] parts = ownerFqn.split("\\."); //$NON-NLS-1$
        if (parts.length != 2)
        {
            return refused(answer,
                "ownerFqn names the owner as Type.Object, for example Catalog.Products; a common form is not created here."); //$NON-NLS-1$
        }
        String kind = MetadataTypeCatalog.toEnglishSingular(parts[0]);
        String directory = kind == null ? null : MetadataTypeCatalog.getDirectoryName(kind);
        if (kind == null || directory == null)
        {
            return refused(answer, parts[0] + " is not a metadata type that owns forms."); //$NON-NLS-1$
        }
        if (!name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) //$NON-NLS-1$
        {
            return refused(answer, "The name must be an identifier."); //$NON-NLS-1$
        }
        MainAttributes.Purpose purpose = MainAttributes.purposeOf(text(params, "purpose"), kind); //$NON-NLS-1$
        if (purpose == null)
        {
            return refused(answer, "Unknown purpose " + text(params, "purpose") //$NON-NLS-1$ //$NON-NLS-2$
                + "; known: ItemForm, ListForm, ChoiceForm, FolderForm, RecordForm, RecordSetForm, None."); //$NON-NLS-1$
        }
        MainAttributes.Spec main = purpose == MainAttributes.Purpose.NONE ? null : MainAttributes.of(kind, purpose);
        if (purpose != MainAttributes.Purpose.NONE && main == null)
        {
            return refused(answer, "No " + purpose.name().toLowerCase(Locale.ROOT) + " form was measured for a " + kind //$NON-NLS-1$ //$NON-NLS-2$
                + "; purposes: " + MainAttributes.purposesOf(kind) + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String language = text(params, "language"); //$NON-NLS-1$
        if (language == null)
        {
            language = DefaultLanguage.codeFor(project);
        }
        OwnerModelReader.Read read = OwnerModelReader.read(project, kind, parts[1], language);
        if (read.model == null)
        {
            return refused(answer, read.error == null ? "Owner not found: " + ownerFqn : read.error); //$NON-NLS-1$
        }
        Map<String, String> args = new LinkedHashMap<>(params);
        args.put("language", language); //$NON-NLS-1$
        if (text(args, "purpose") == null) //$NON-NLS-1$
        {
            args.put("purpose", purpose.name()); //$NON-NLS-1$
        }
        answer.putAll(create(read.model, args, new ProjectFiles(project), new ProjectModel(project, ownerFqn),
            new ProjectMarker(project, directory, parts[1], name)));
        return answer;
    }

    /**
     * Creates the form from a description already read, through the three seams. A test substitutes
     * the seams and passes no project.
     *
     * @param owner the description
     * @param params the tool's arguments, {@code name} among them
     * @param files the container
     * @param model the form's entry
     * @param marker the baseline mark
     * @return the answer
     */
    static Map<String, Object> create(OwnerModel owner, Map<String, String> params, ContainerWriter files, ModelWriter model,
        DeliveryMarker marker)
    {
        Map<String, Object> answer = base(null);
        String name = text(params, "name") != null ? text(params, "name") : text(params, "formName"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (name == null)
        {
            return refused(answer, "create_form needs ownerFqn (Type.Object) and name."); //$NON-NLS-1$
        }
        if (!name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) //$NON-NLS-1$
        {
            return refused(answer, "The name must be an identifier."); //$NON-NLS-1$
        }
        String directory = MetadataTypeCatalog.getDirectoryName(owner.kind);
        if (directory == null)
        {
            return refused(answer, owner.kind + " is not a metadata type that owns forms."); //$NON-NLS-1$
        }
        MainAttributes.Purpose purpose = MainAttributes.purposeOf(text(params, "purpose"), owner.kind); //$NON-NLS-1$
        if (purpose == null)
        {
            return refused(answer, "Unknown purpose " + text(params, "purpose") //$NON-NLS-1$ //$NON-NLS-2$
                + "; known: ItemForm, ListForm, ChoiceForm, FolderForm, RecordForm, RecordSetForm, None."); //$NON-NLS-1$
        }
        boolean setAsDefault = "true".equalsIgnoreCase(text(params, "setAsDefault")); //$NON-NLS-1$ //$NON-NLS-2$
        String defaultSetter = setAsDefault ? MainAttributes.defaultSetter(owner.kind, purpose) : null;
        if (setAsDefault && defaultSetter == null)
        {
            return refused(answer, "A " + purpose.name().toLowerCase(Locale.ROOT) + " form of a " + owner.kind //$NON-NLS-1$ //$NON-NLS-2$
                + " is not a default form of its owner."); //$NON-NLS-1$
        }
        boolean dryRun = "true".equalsIgnoreCase(text(params, "dryRun")); //$NON-NLS-1$ //$NON-NLS-2$
        String language = text(params, "language"); //$NON-NLS-1$
        if (language == null)
        {
            language = "ru"; //$NON-NLS-1$
        }
        String synonym = text(params, "synonym"); //$NON-NLS-1$
        if (synonym == null)
        {
            synonym = synonymOf(name);
        }
        Map<String, String> args = new LinkedHashMap<>(params);
        args.put("language", language); //$NON-NLS-1$
        args.put("synonym", synonym); //$NON-NLS-1$
        if (text(args, "title") == null) //$NON-NLS-1$
        {
            args.put("title", synonym); //$NON-NLS-1$
        }
        String formFqn = owner.kind + "." + owner.name + ".Form." + name; //$NON-NLS-1$ //$NON-NLS-2$
        answer.put("formFqn", formFqn); //$NON-NLS-1$
        answer.put("purpose", purpose.name()); //$NON-NLS-1$
        answer.put("ownerFqn", owner.fqn); //$NON-NLS-1$
        answer.put("dryRun", Boolean.valueOf(dryRun)); //$NON-NLS-1$
        FormComposer.Result laid = FormComposer.compose(owner, args);
        if (!laid.ok)
        {
            return refused(answer, laid.error);
        }
        String relativeDir = "src/" + directory + "/" + owner.name + "/Forms/" + name; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String relative = relativeDir + "/" + OrdinaryFormLocator.FILE_NAME; //$NON-NLS-1$
        answer.put("container", relative); //$NON-NLS-1$
        byte[] bytes;
        try
        {
            OrdinaryFormFile container = OrdinaryFormFile.of(null, V8Container.empty());
            container.setFormText(BraceTree.serialize(laid.layout));
            container.setModuleText(""); //$NON-NLS-1$
            bytes = container.toBytes();
        }
        catch (RuntimeException e)
        {
            return refused(answer, "The layout was not serialized: " + e.getMessage()); //$NON-NLS-1$
        }
        String taken = model.nameTaken(name);
        if (taken != null)
        {
            return refused(answer, taken);
        }
        if (files.directoryExists(relativeDir))
        {
            return refused(answer, "The form's directory already exists: " + relativeDir); //$NON-NLS-1$
        }
        fill(answer, owner, purpose, synonym, defaultSetter, laid, args.get("title"), bytes.length); //$NON-NLS-1$
        if (dryRun)
        {
            answer.put("success", Boolean.TRUE); //$NON-NLS-1$
            answer.put("written", Boolean.FALSE); //$NON-NLS-1$
            answer.put("message", "dry run: the layout and the model entry were checked and nothing was written"); //$NON-NLS-1$ //$NON-NLS-2$
            return answer;
        }
        try
        {
            files.write(relative, bytes);
        }
        catch (IOException e)
        {
            String orphan = files.removeCreated();
            if (orphan != null)
            {
                return refused(answer, "orphaned " + orphan + ": the container was not written and could not be removed"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return refused(answer, "The container was not written and the model was not changed: " + e.getMessage()); //$NON-NLS-1$
        }
        String committed = model.commit(name, language, synonym, setAsDefault, defaultSetter);
        if (committed != null)
        {
            String orphan = files.removeCreated();
            if (orphan != null)
            {
                return refused(answer, "orphaned " + orphan + ": the model rejected the form and the container could not be removed. " //$NON-NLS-1$ //$NON-NLS-2$
                    + committed);
            }
            return refused(answer, "The model was not changed and the container was removed: " + committed); //$NON-NLS-1$
        }
        answer.put("success", Boolean.TRUE); //$NON-NLS-1$
        answer.put("written", Boolean.TRUE); //$NON-NLS-1$
        try
        {
            answer.put("delivery", marker.mark()); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            String reason = e.getMessage() == null ? "the owner was not marked" : e.getMessage(); //$NON-NLS-1$
            answer.put("delivery", reason); //$NON-NLS-1$
            answer.put("notes", List.of(reason, UPDATE_HINT)); //$NON-NLS-1$
        }
        return answer;
    }

    private static void fill(Map<String, Object> answer, OwnerModel owner, MainAttributes.Purpose purpose, String synonym,
        String defaultSetter, FormComposer.Result laid, String title, int layoutChars)
    {
        MainAttributes.Spec spec = purpose == MainAttributes.Purpose.NONE ? null : MainAttributes.of(owner.kind, purpose);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("formFqn", answer.get("formFqn")); //$NON-NLS-1$ //$NON-NLS-2$
        model.put("purpose", purpose.name()); //$NON-NLS-1$
        model.put("synonym", synonym); //$NON-NLS-1$
        if (spec != null)
        {
            Map<String, Object> attribute = new LinkedHashMap<>();
            attribute.put("name", spec.name); //$NON-NLS-1$
            attribute.put("type", owner.producedName(spec.producedType)); //$NON-NLS-1$
            model.put("mainAttribute", attribute); //$NON-NLS-1$
        }
        if (defaultSetter != null)
        {
            model.put("defaultForm", defaultSetter); //$NON-NLS-1$
            answer.put("defaultForm", defaultSetter); //$NON-NLS-1$
        }
        answer.put("model", model); //$NON-NLS-1$
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("title", title); //$NON-NLS-1$
        plan.put("size", laid.width + "x" + laid.height); //$NON-NLS-1$ //$NON-NLS-2$
        plan.put("columns", Integer.valueOf(laid.columns)); //$NON-NLS-1$
        plan.put("tabularSections", laid.tabularSections); //$NON-NLS-1$
        plan.put("fields", laid.fields); //$NON-NLS-1$
        plan.put("tables", laid.tables); //$NON-NLS-1$
        plan.put("plainColumns", laid.plainColumns); //$NON-NLS-1$
        plan.put("layoutChars", Integer.valueOf(layoutChars)); //$NON-NLS-1$
        if (laid.note != null)
        {
            plan.put("note", laid.note); //$NON-NLS-1$
            answer.put("note", laid.note); //$NON-NLS-1$
        }
        answer.put("layoutPlan", plan); //$NON-NLS-1$
        answer.put("title", title); //$NON-NLS-1$
        answer.put("size", laid.width + "x" + laid.height); //$NON-NLS-1$ //$NON-NLS-2$
        answer.put("fields", laid.fields); //$NON-NLS-1$
        answer.put("tables", laid.tables); //$NON-NLS-1$
        answer.put("layoutChars", Integer.valueOf(layoutChars)); //$NON-NLS-1$
    }

    /**
     * The synonym the Designer derives from a name: a space before every capital that follows a
     * small letter or a digit, the rest in small letters.
     *
     * @param name the name
     * @return the synonym
     */
    static String synonymOf(String name)
    {
        StringBuilder out = new StringBuilder();
        char previous = 0;
        for (int i = 0; i < name.length(); i++)
        {
            char c = name.charAt(i);
            if (c == '_')
            {
                c = ' ';
            }
            if (i > 0 && Character.isUpperCase(c) && (Character.isLowerCase(previous) || Character.isDigit(previous)))
            {
                out.append(' ');
                out.append(Character.toLowerCase(c));
            }
            else
            {
                out.append(i == 0 ? c : Character.isUpperCase(c) && Character.isUpperCase(previous) ? c : Character.toLowerCase(c));
            }
            previous = c;
        }
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static EList<MdObject> formsOf(MdObject owner)
    {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature("forms"); //$NON-NLS-1$
        Object value = feature == null ? null : owner.eGet(feature);
        return value instanceof EList ? (EList<MdObject>)value : null;
    }

    /** Gives the form the use purposes of the configuration. */
    private static String copyUsePurposes(IProject project, MdObject form)
    {
        try
        {
            IConfigurationProvider provider = Activator.getDefault().getConfigurationProvider();
            Configuration configuration = provider == null ? null : provider.getConfiguration(project);
            Object purposes = form.getClass().getMethod("getUsePurposes").invoke(form); //$NON-NLS-1$
            if (configuration == null || !(purposes instanceof EList))
            {
                return "usePurposes: not set, the configuration or the form's list is not reachable"; //$NON-NLS-1$
            }
            @SuppressWarnings("unchecked")
            EList<Object> list = (EList<Object>)purposes;
            list.clear();
            list.addAll(configuration.getUsePurposes());
            return null;
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return "usePurposes: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    private static Map<String, Object> base(String projectName)
    {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("operation", "create_form"); //$NON-NLS-1$ //$NON-NLS-2$
        if (projectName != null)
        {
            answer.put("projectName", projectName); //$NON-NLS-1$
        }
        return answer;
    }

    private static Map<String, Object> refused(Map<String, Object> answer, String why)
    {
        answer.put("success", Boolean.FALSE); //$NON-NLS-1$
        answer.put("error", why); //$NON-NLS-1$
        return answer;
    }

    private static String text(Map<String, String> params, String key)
    {
        if (params == null)
        {
            return null;
        }
        String value = params.get(key);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /** The container in the workspace. */
    private static final class ProjectFiles implements ContainerWriter
    {
        private final IProject project;

        private final List<String> created = new ArrayList<>();

        ProjectFiles(IProject project)
        {
            this.project = project;
        }

        @Override
        public boolean directoryExists(String relativeDirectory)
        {
            return project.getFolder(new org.eclipse.core.runtime.Path(relativeDirectory)).exists();
        }

        /**
         * Writes the created container after checking its connected editor buffer.
         *
         * @param relativeFile the container path relative to the project
         * @param bytes the composed container bytes
         * @throws IOException when an editor has unsaved changes or the workspace rejects the write
         */
        @Override
        public void write(String relativeFile, byte[] bytes) throws IOException
        {
            try
            {
                IFile file = project.getFile(new org.eclipse.core.runtime.Path(relativeFile));
                OrdinaryFormModule.refuseWhileAnEditorHoldsUnsavedChanges(file);
                int slash = relativeFile.lastIndexOf('/');
                if (slash > 0)
                {
                    createFolders(relativeFile.substring(0, slash));
                }
                try (InputStream stream = new ByteArrayInputStream(bytes))
                {
                    if (file.exists())
                    {
                        file.setContents(stream, IResource.FORCE | IResource.KEEP_HISTORY, null);
                    }
                    else
                    {
                        file.create(stream, IResource.FORCE, null);
                    }
                }
            }
            catch (CoreException e)
            {
                throw new IOException(e.getMessage(), e);
            }
        }

        @Override
        public String removeCreated()
        {
            for (int i = created.size() - 1; i >= 0; i--)
            {
                IFolder folder = project.getFolder(new org.eclipse.core.runtime.Path(created.get(i)));
                try
                {
                    if (folder.exists())
                    {
                        folder.delete(true, null);
                    }
                }
                catch (CoreException e)
                {
                    return created.get(i);
                }
            }
            created.clear();
            return null;
        }

        private void createFolders(String relative) throws CoreException
        {
            IFolder folder = project.getFolder(new org.eclipse.core.runtime.Path(relative));
            if (folder.exists())
            {
                return;
            }
            int slash = relative.lastIndexOf('/');
            if (slash > 0)
            {
                createFolders(relative.substring(0, slash));
            }
            folder.create(IResource.FORCE, true, null);
            created.add(relative);
        }
    }

    /** The form's entry, read and written on the owner's transaction. */
    private static final class ProjectModel implements ModelWriter
    {
        private final IProject project;

        private final String ownerFqn;

        ProjectModel(IProject project, String ownerFqn)
        {
            this.project = project;
            this.ownerFqn = ownerFqn;
        }

        @Override
        public String nameTaken(String formName)
        {
            try
            {
                return UiSync.call(() -> {
                    Activator activator = Activator.getDefault();
                    IConfigurationProvider provider = activator == null ? null : activator.getConfigurationProvider();
                    Configuration configuration = provider == null ? null : provider.getConfiguration(project);
                    if (configuration == null)
                    {
                        return "No configuration is loaded."; //$NON-NLS-1$
                    }
                    String[] parts = ownerFqn.split("\\."); //$NON-NLS-1$
                    MdObject owner = MetadataTypeCatalog.findObject(configuration, parts[0], parts[1]);
                    if (owner == null)
                    {
                        return "Owner not found: " + ownerFqn; //$NON-NLS-1$
                    }
                    EList<MdObject> forms = formsOf(owner);
                    if (forms == null)
                    {
                        return owner.eClass().getName() + " has no forms collection."; //$NON-NLS-1$
                    }
                    if (BmObjectHelper.findByName(forms, formName) != null)
                    {
                        return "A form named " + formName + " already exists on " + ownerFqn + "."; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    }
                    return null;
                });
            }
            catch (RuntimeException e)
            {
                return e.getMessage();
            }
        }

        @Override
        public String commit(String formName, String language, String synonym, boolean setAsDefault, String defaultSetter)
        {
            BmObjectHelper.Result result = BmObjectHelper.executeWriteOnObject(project, ownerFqn, false, (tx, owner) -> {
                EList<MdObject> forms = formsOf(owner);
                if (forms == null)
                {
                    throw new RuntimeException(owner.eClass().getName() + " has no forms collection."); //$NON-NLS-1$
                }
                if (BmObjectHelper.findByName(forms, formName) != null)
                {
                    throw BmObjectHelper.alreadyExists(formName, ownerFqn, "form"); //$NON-NLS-1$
                }
                MdObject form = BmObjectHelper.createOwnerScopedObject(owner, "Form"); //$NON-NLS-1$
                if (form == null)
                {
                    throw new RuntimeException("No form can be created under " + owner.eClass().getName() + "."); //$NON-NLS-1$ //$NON-NLS-2$
                }
                form.setName(formName);
                String typeError = BmObjectHelper.setProperty(form, "formType", "ORDINARY"); //$NON-NLS-1$ //$NON-NLS-2$
                if (typeError != null)
                {
                    throw new RuntimeException("formType: " + typeError); //$NON-NLS-1$
                }
                form.getSynonym().put(language, synonym);
                // a missing use-purpose list does not undo the form: the platform still opens it
                copyUsePurposes(project, form);
                forms.add(form);
                if (defaultSetter != null)
                {
                    String defaultError = BmObjectHelper.setProperty(owner, defaultSetter, form);
                    if (defaultError != null)
                    {
                        throw new RuntimeException(defaultSetter + ": " + defaultError); //$NON-NLS-1$
                    }
                }
                return formName;
            });
            return result.ok ? null : result.error != null ? result.error : "The form was not created."; //$NON-NLS-1$
        }
    }

    /** The baseline mark after the container and the model entry both exist. */
    private static final class ProjectMarker implements DeliveryMarker
    {
        private final IProject project;

        private final String moduleAddress;

        ProjectMarker(IProject project, String directory, String ownerName, String formName)
        {
            this.project = project;
            this.moduleAddress = directory + "/" + ownerName + "/Forms/" + formName + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        @Override
        public String mark() throws Exception
        {
            OrdinaryFormModule module = OrdinaryFormModule.locate(project, moduleAddress);
            if (module == null)
            {
                throw new IOException("the container is not reachable through the workspace"); //$NON-NLS-1$
            }
            return module.afterWrite(true);
        }
    }
}
