/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.Group;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com._1c.g5.v8.dt.platform.services.model.Section;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What the list of registered infobases shows: groups by path, each infobase with its type and
 * connection string, passwords masked, and the projects bound to it.
 */
public class RegisteredInfobasesReaderTest
{
    private static InfobaseReference fileBase(String name, String path)
    {
        InfobaseReference ref = InfobaseReferences.newFileInfobaseReference(path);
        ref.setName(name);
        ref.setUuid(UUID.randomUUID());
        return ref;
    }

    private static InfobaseReference serverBase(String name, String server, String reference)
    {
        InfobaseReference ref = InfobaseReferences.newServerInfobaseReference(server, reference);
        ref.setName(name);
        ref.setUuid(UUID.randomUUID());
        return ref;
    }

    private static Group group(String name, Section... members)
    {
        Group group = ModelFactory.eINSTANCE.createGroup();
        group.setName(name);
        group.setUuid(UUID.randomUUID());
        for (Section member : members)
        {
            group.getSubsections().add(member);
        }
        return group;
    }

    private static JsonObject answer(List<Section> sections, Map<InfobaseReference, List<String>> bound)
    {
        return JsonParser.parseString(RegisteredInfobasesReader.render(sections, bound).toJson())
            .getAsJsonObject();
    }

    private static JsonObject row(JsonObject answer, String name)
    {
        for (var element : answer.getAsJsonArray("infobases")) //$NON-NLS-1$
        {
            JsonObject row = element.getAsJsonObject();
            if (name.equals(row.get("name").getAsString())) //$NON-NLS-1$
            {
                return row;
            }
        }
        return null;
    }

    @Test
    public void groupsAreListedByPathAndEachInfobaseNamesItsGroup()
    {
        InfobaseReference top = fileBase("Верхняя", "C:/bases/top"); //$NON-NLS-1$ //$NON-NLS-2$
        InfobaseReference inner = fileBase("Внутренняя", "C:/bases/inner"); //$NON-NLS-1$ //$NON-NLS-2$
        InfobaseReference nested = serverBase("Вложенная", "srv", "erp"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<Section> sections = new ArrayList<>();
        sections.add(top);
        sections.add(group("Разработка", inner, group("Сервер", nested))); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject answer = answer(sections, Map.of());

        assertEquals(3, answer.get("count").getAsInt()); //$NON-NLS-1$
        JsonArray groups = answer.getAsJsonArray("groups"); //$NON-NLS-1$
        assertEquals("Разработка", groups.get(0).getAsString()); //$NON-NLS-1$
        assertEquals("Разработка/Сервер", groups.get(1).getAsString()); //$NON-NLS-1$
        assertEquals("", row(answer, "Верхняя").get("group").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Разработка", row(answer, "Внутренняя").get("group").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Разработка/Сервер", row(answer, "Вложенная").get("group").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void eachInfobaseCarriesItsTypeAndConnectionString()
    {
        List<Section> sections = List.of(fileBase("Файловая", "C:/bases/file"), //$NON-NLS-1$ //$NON-NLS-2$
            serverBase("Серверная", "srv", "erp")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject answer = answer(sections, Map.of());

        JsonObject file = row(answer, "Файловая"); //$NON-NLS-1$
        assertEquals("FILE", file.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(file.toString(), file.get("connectionString").getAsString().contains("C:/bases/file")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject server = row(answer, "Серверная"); //$NON-NLS-1$
        assertEquals("SERVER", server.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(server.toString(), server.get("connectionString").getAsString().contains("erp")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(server.get("uuid").getAsString().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void passwordsNeverLeave()
    {
        assertEquals("Srvr=\"srv\";Ref=\"erp\";Usr=\"admin\";Pwd=\"***\";", //$NON-NLS-1$
            RegisteredInfobasesReader.maskPasswords("Srvr=\"srv\";Ref=\"erp\";Usr=\"admin\";Pwd=\"secret\";")); //$NON-NLS-1$
        assertEquals("DBSrvr=db;DBPwd=\"***\";Ref=erp", //$NON-NLS-1$
            RegisteredInfobasesReader.maskPasswords("DBSrvr=db;DBPwd=secret;Ref=erp")); //$NON-NLS-1$
        assertEquals("/N admin /P *** /UseHwLicenses+", //$NON-NLS-1$
            RegisteredInfobasesReader.maskPasswords("/N admin /P secret /UseHwLicenses+")); //$NON-NLS-1$
        assertEquals("pwd=\"***\"", RegisteredInfobasesReader.maskPasswords("pwd=\"a\"\"b\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(RegisteredInfobasesReader.maskPasswords(null));
    }

    @Test
    public void additionalParametersAreShownWithTheirPasswordMasked()
    {
        InfobaseReference base = fileBase("СПараметрами", "C:/bases/params"); //$NON-NLS-1$ //$NON-NLS-2$
        base.setAdditionalParameters("/N admin /P secret"); //$NON-NLS-1$

        JsonObject row = row(answer(List.of(base), Map.of()), "СПараметрами"); //$NON-NLS-1$

        assertEquals("/N admin /P ***", row.get("additionalParameters").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(row.toString(), row.toString().contains("secret")); //$NON-NLS-1$
    }

    @Test
    public void theProjectsBoundToAnInfobaseAreNamed()
    {
        InfobaseReference bound = fileBase("Привязанная", "C:/bases/bound"); //$NON-NLS-1$ //$NON-NLS-2$
        InfobaseReference free = fileBase("Свободная", "C:/bases/free"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<InfobaseReference, List<String>> projects = new LinkedHashMap<>();
        projects.put(bound, List.of("Конфигурация", "Конфигурация.Расширение")); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject answer = answer(List.of(bound, free), projects);

        assertEquals(2, row(answer, "Привязанная").getAsJsonArray("projects").size()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, row(answer, "Свободная").getAsJsonArray("projects").size()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theSameInfobaseIsKnownByIdOrByAddress()
    {
        InfobaseReference listed = fileBase("База", "C:/bases/same"); //$NON-NLS-1$ //$NON-NLS-2$
        InfobaseReference byAddress = InfobaseReferences.newFileInfobaseReference("C:/bases/same"); //$NON-NLS-1$
        InfobaseReference other = fileBase("Другая", "C:/bases/other"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(RegisteredInfobasesReader.sameInfobase(listed, byAddress));
        assertFalse(RegisteredInfobasesReader.sameInfobase(listed, other));
        assertFalse(RegisteredInfobasesReader.sameInfobase(listed, null));
    }

    @Test
    public void theToolReadsAndDeclaresNothing()
    {
        RegisteredInfobasesReader reader = new RegisteredInfobasesReader();
        assertEquals("list_registered_infobases", reader.getName()); //$NON-NLS-1$
        assertFalse(reader.getInputSchema().contains("\"required\":[\"")); //$NON-NLS-1$
    }
}
