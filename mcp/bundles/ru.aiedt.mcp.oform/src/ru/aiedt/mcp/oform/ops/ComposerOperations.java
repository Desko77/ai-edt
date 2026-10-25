/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import ru.aiedt.mcp.oform.OwnerModel;
import ru.aiedt.mcp.oform.OwnerModelReader;
import ru.aiedt.mcp.oform.OrdinaryFormCreation;
import ru.aiedt.mcp.server.support.DefaultLanguage;
import ru.aiedt.mcp.server.support.MetadataTypeCatalog;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * Creating forms and reading the owner a form is laid out from: {@code describe_owner} and
 * {@code create_form}.
 */
public final class ComposerOperations
{
    private ComposerOperations()
    {
        // static catalog
    }

    /** @return the specs of this catalog */
    public static List<OperationSpec> specs()
    {
        List<OperationSpec> out = new ArrayList<>();
        out.add(OperationSpec.query("describe_owner", //$NON-NLS-1$
            "ownerFqn (Type.Object): the owner's attributes, tabular sections, standard attributes " //$NON-NLS-1$
                + "and list columns, in words, with no uuid", //$NON-NLS-1$
            List.of(ParamSpec.string("ownerFqn", "the owner, Type.Object (Catalog.Products, InformationRegister.Prices).")), //$NON-NLS-1$ //$NON-NLS-2$
            ComposerOperations::describe));
        out.add(OperationSpec.creation("create_form", //$NON-NLS-1$
            "ownerFqn, name, purpose (ItemForm | ListForm | ChoiceForm | FolderForm | RecordForm | " //$NON-NLS-1$
                + "RecordSetForm | None), synonym, title, width, height, setAsDefault, withFields, withTables, " //$NON-NLS-1$
                + "withCommands, columns (1 | 2 | auto), tabularSections (pages | below): the form's entry on its " //$NON-NLS-1$
                + "owner and the container Forms/<name>/Form.oform. The layout is the owner's header, its tabular " //$NON-NLS-1$
                + "sections and its command panels; the file, the model entry and the baseline mark are three steps, " //$NON-NLS-1$
                + "and a failure of the file or the model removes the file", //$NON-NLS-1$
            List.of(ParamSpec.string("ownerFqn", "the owner of the new form, Type.Object (Catalog.Products, DataProcessor.Report1)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("name", "the name of the new form."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("purpose", "ItemForm (default for an object) | ListForm (default for a register) | ChoiceForm | " //$NON-NLS-1$ //$NON-NLS-2$
                    + "FolderForm | RecordForm | RecordSetForm | None (no main attribute)."), //$NON-NLS-1$
                ParamSpec.string("synonym", "the form's synonym (default: derived from the name)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("title", "the form's title (default: the synonym)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.integer("width", "the form's width, from 500 to 1000 (default 500, or 820 with two columns)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.integer("height", "the form's height, from 350 to 700 (default: the content, at least 350)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.bool("setAsDefault", "make it the owner's default form of that purpose (default false)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.bool("withFields", "lay the header out as labels and fields (default true on an object form)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.bool("withTables", "lay a table out for each tabular section (default true only for ItemForm with tabular sections)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.bool("withCommands", "put the form's command panel and a panel on each table (default true except for purpose=None)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("columns", "1, 2 or auto (default auto: 1 when the header has 12 attributes or fewer, else 2)."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("tabularSections", "pages or below (default pages when there are two or more, else below).")), //$NON-NLS-1$ //$NON-NLS-2$
            (project, params) -> ToolResult.toJsonStatic(OrdinaryFormCreation.create(project, params))));
        return out;
    }

    /**
     * The owner in words. A {@code null} model is "not found" once the name is a kind that has
     * ordinary forms; a kind that has none is refused before any model is read.
     *
     * @param ownerFqn {@code Type.Object}
     * @param model the description, or {@code null} when the owner is not there
     * @return the answer
     */
    public static Map<String, Object> describeOwner(String ownerFqn, OwnerModel model)
    {
        String problem = problemWith(ownerFqn);
        if (problem != null)
        {
            return refused(problem);
        }
        if (model == null)
        {
            return refused("Owner not found: " + ownerFqn); //$NON-NLS-1$
        }
        return model.describe();
    }

    private static String describe(IProject project, Map<String, String> params)
    {
        String ownerFqn = text(params, "ownerFqn"); //$NON-NLS-1$
        String problem = problemWith(ownerFqn);
        if (problem != null)
        {
            return ToolResult.toJsonStatic(refused(problem));
        }
        String[] parts = ownerFqn.split("\\."); //$NON-NLS-1$
        String kind = MetadataTypeCatalog.toEnglishSingular(parts[0]);
        String language = text(params, "language"); //$NON-NLS-1$
        if (language == null && project != null)
        {
            language = DefaultLanguage.codeFor(project);
        }
        OwnerModelReader.Read read = OwnerModelReader.read(project, kind, parts[1], language);
        if (read.model == null)
        {
            return ToolResult.toJsonStatic(refused(read.error == null ? "Owner not found: " + ownerFqn : read.error)); //$NON-NLS-1$
        }
        return ToolResult.toJsonStatic(read.model.describe());
    }

    /** @return why this name is not read, or {@code null} when a read should go ahead */
    static String problemWith(String ownerFqn)
    {
        if (ownerFqn == null || ownerFqn.isEmpty())
        {
            return "describe_owner needs ownerFqn (Type.Object)."; //$NON-NLS-1$
        }
        String[] parts = ownerFqn.split("\\."); //$NON-NLS-1$
        if (parts.length != 2 || parts[1].isEmpty())
        {
            return "ownerFqn names the owner as Type.Object, for example Catalog.Products."; //$NON-NLS-1$
        }
        String kind = MetadataTypeCatalog.toEnglishSingular(parts[0]);
        if (kind == null || MetadataTypeCatalog.getDirectoryName(kind) == null)
        {
            return parts[0] + " is not a metadata type that owns forms."; //$NON-NLS-1$
        }
        if (!OwnerModel.admitsOrdinaryForms(kind))
        {
            return "No ordinary form was measured for a " + kind + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    private static Map<String, Object> refused(String why)
    {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("operation", "describe_owner"); //$NON-NLS-1$ //$NON-NLS-2$
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
}
