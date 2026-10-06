/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;

import ru.aiedt.mcp.server.Activator;

/**
 * The language a configuration's BSL is written in: which spelling of {@code Procedure}, of the
 * region directives and of the conditional-compilation keywords its modules use.
 * <p>
 * One source for every stub writer, so a stub and the module it lands in speak the same language.
 * The configuration's script variant decides it; a configuration that is unavailable or carries no
 * value answers Russian, which is what the platform itself defaults to.
 * </p>
 */
public enum BslScriptLanguage
{
    /** The Russian spelling of the BSL keywords. */
    RUSSIAN,
    /** The English spelling of the BSL keywords. */
    ENGLISH;

    /** The region the event-handler stubs of an object module are collected in. */
    public static final String HANDLER_REGION_RUSSIAN = "ОбработчикиСобытий"; //$NON-NLS-1$

    /** {@link #HANDLER_REGION_RUSSIAN} in English. */
    public static final String HANDLER_REGION_ENGLISH = "EventHandlers"; //$NON-NLS-1$

    /**
     * The language of the configuration's script variant.
     *
     * @param config the configuration, or null when it is not available
     * @return the language, Russian when the configuration carries no readable value
     */
    public static BslScriptLanguage of(Configuration config)
    {
        try
        {
            if (config != null)
            {
                Object variant = config.getScriptVariant();
                if (variant instanceof Enum<?> && "ENGLISH".equalsIgnoreCase(((Enum<?>)variant).name())) //$NON-NLS-1$
                {
                    return ENGLISH;
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("the script variant of the configuration could not be read: " //$NON-NLS-1$
                + e.getMessage());
        }
        return RUSSIAN;
    }

    /**
     * The platform's own {@code ScriptVariant} constant of the configuration, for callers that hand
     * it to an EDT API.
     * <p>
     * Read through the enum's constant name rather than its type, so this bundle resolves on a
     * runtime whose mdclass build moves the enum. The constant is looked up reflectively only for
     * the default: a configuration that carries no value still has to answer the platform's own
     * {@code RUSSIAN} constant, not a spelling of ours.
     * </p>
     *
     * @param config the configuration, or null when it is not available
     * @return the platform constant named {@code ENGLISH}, the platform constant named
     *         {@code RUSSIAN} when the configuration carries no readable value, or null when not
     *         even the default constant resolves
     */
    public static Object platformVariant(Configuration config)
    {
        Object read = null;
        try
        {
            if (config != null)
            {
                read = config.getScriptVariant();
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("the script variant of the configuration could not be read: " //$NON-NLS-1$
                + e.getMessage());
        }
        if (read instanceof Enum<?> && "ENGLISH".equalsIgnoreCase(((Enum<?>)read).name())) //$NON-NLS-1$
        {
            return read;
        }
        try
        {
            Class<?> variantClass = Class.forName("com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant"); //$NON-NLS-1$
            for (Object constant : variantClass.getEnumConstants())
            {
                if (((Enum<?>)constant).name().equalsIgnoreCase("RUSSIAN")) //$NON-NLS-1$
                {
                    return constant;
                }
            }
        }
        catch (Exception e)
        {
            Activator.logWarning("the default script variant could not be resolved: " + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The keyword that opens a procedure.
     *
     * @return the keyword of this language
     */
    public String procedure()
    {
        return this == ENGLISH ? "Procedure" : "Процедура"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The keyword that closes a procedure.
     *
     * @return the keyword of this language
     */
    public String endProcedure()
    {
        return this == ENGLISH ? "EndProcedure" : "КонецПроцедуры"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The keyword that opens a function.
     *
     * @return the keyword of this language
     */
    public String function()
    {
        return this == ENGLISH ? "Function" : "Функция"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The keyword that closes a function.
     *
     * @return the keyword of this language
     */
    public String endFunction()
    {
        return this == ENGLISH ? "EndFunction" : "КонецФункции"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The line that opens a region of this language's name.
     *
     * @param name the region name
     * @return the opening directive line
     */
    public String regionOpen(String name)
    {
        return (this == ENGLISH ? "#Region " : "#Область ") + name; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The line that closes a region.
     *
     * @return the closing directive line
     */
    public String regionEnd()
    {
        return this == ENGLISH ? "#EndRegion" : "#КонецОбласти"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The name of the region the event-handler stubs of an object module are collected in.
     *
     * @return the region name in this language
     */
    public String handlerRegionName()
    {
        return this == ENGLISH ? HANDLER_REGION_ENGLISH : HANDLER_REGION_RUSSIAN;
    }

    /**
     * The conditional-compilation line an object module is framed with: its handlers run on the
     * server, in the thick client and in an external connection.
     *
     * @return the opening directive line
     */
    public String serverFramingOpen()
    {
        return this == ENGLISH
            ? "#If Server Or ThickClientOrdinaryApplication Or ExternalConnection Then" //$NON-NLS-1$
            : "#Если Сервер Или ТолстыйКлиентОбычноеПриложение Или ВнешнееСоединение Тогда"; //$NON-NLS-1$
    }

    /**
     * The line that closes the framing {@link #serverFramingOpen()} opened.
     *
     * @return the closing directive line
     */
    public String framingEnd()
    {
        return this == ENGLISH ? "#EndIf" : "#КонецЕсли"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The marker comment a stub body carries until its handler is written.
     *
     * @param handlerName the handler the stub declares
     * @return the comment line
     */
    public String todoComment(String handlerName)
    {
        return this == ENGLISH
            ? "// TODO: implement the " + handlerName + " handler" //$NON-NLS-1$ //$NON-NLS-2$
            : "// TODO: реализовать обработчик " + handlerName; //$NON-NLS-1$
    }
}
