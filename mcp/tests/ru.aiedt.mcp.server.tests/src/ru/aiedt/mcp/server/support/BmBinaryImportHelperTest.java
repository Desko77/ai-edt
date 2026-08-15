/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Covers what the staging import decides before any Designer is started.
 * <p>
 * Everything past that point needs a platform runtime and writes an infobase, so a real run is
 * verified against a live workspace. What is pinned here is the reasoning a caller depends on: that
 * a file is judged by what it is, that a name the platform would refuse is never handed to it, and
 * that nothing is attempted for input that cannot work.
 * </p>
 */
public class BmBinaryImportHelperTest
{
    @Test
    public void aBinaryIsJudgedByItsExtension()
    {
        assertEquals(BmBinaryImportHelper.BinaryKind.CONFIGURATION,
            BmBinaryImportHelper.kindOf(Paths.get("C:/tmp/ERP.cf"))); //$NON-NLS-1$
        assertEquals(BmBinaryImportHelper.BinaryKind.EXTENSION,
            BmBinaryImportHelper.kindOf(Paths.get("C:/tmp/Доработка.cfe"))); //$NON-NLS-1$
        assertEquals("case is not a distinction the platform makes here", //$NON-NLS-1$
            BmBinaryImportHelper.BinaryKind.CONFIGURATION,
            BmBinaryImportHelper.kindOf(Paths.get("C:/tmp/ERP.CF"))); //$NON-NLS-1$
    }

    @Test
    public void whatIsNotAConfigurationBinaryIsRefusedRatherThanGuessedAt()
    {
        // An .epf is a binary the plugin can read too, by a different route entirely. Treating it
        // as a configuration would stage it into an infobase and produce nothing, minutes later.
        assertNull(BmBinaryImportHelper.kindOf(Paths.get("C:/tmp/Обработка.epf"))); //$NON-NLS-1$
        assertNull(BmBinaryImportHelper.kindOf(Paths.get("C:/tmp/Отчет.erf"))); //$NON-NLS-1$
        assertNull(BmBinaryImportHelper.kindOf(Paths.get("C:/tmp/dump"))); //$NON-NLS-1$
        assertNull(BmBinaryImportHelper.kindOf(Paths.get("C:/tmp/ERP.cf.bak"))); //$NON-NLS-1$
    }

    @Test
    public void anExtensionNameIsTakenFromTheFileName()
    {
        assertEquals("ДоработкаУТ", //$NON-NLS-1$
            BmBinaryImportHelper.extensionNameFrom(Paths.get("C:/tmp/ДоработкаУТ.cfe"))); //$NON-NLS-1$
    }

    @Test
    public void aFileNameThePlatformWouldRefuseIsMadeIntoOneItAccepts()
    {
        // The file name is somebody else's, and a platform name may hold only letters, digits and
        // underscores. Passing the raw name through would fail inside the Designer, where the
        // reason is a transcript line rather than a message.
        assertEquals("Доработка_УТ_1_2", //$NON-NLS-1$
            BmBinaryImportHelper.extensionNameFrom(Paths.get("C:/tmp/Доработка УТ-1.2.cfe"))); //$NON-NLS-1$
        assertEquals("a name may not begin with a digit", "Ext_1С_Доработка", //$NON-NLS-1$ //$NON-NLS-2$
            BmBinaryImportHelper.extensionNameFrom(Paths.get("C:/tmp/1С_Доработка.cfe"))); //$NON-NLS-1$
        assertTrue("an empty name is not a name", //$NON-NLS-1$
            BmBinaryImportHelper.extensionNameFrom(Paths.get("C:/tmp/.cfe")).startsWith("Ext_")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aFileThatIsNotThereStopsBeforeAnInfobaseIsCreated()
    {
        // The staging infobase is the expensive part and the one that leaves traces. A missing
        // input must be caught in front of it, not after.
        Path missing = Paths.get("C:/aiedt-tests-no-such-file.cf"); //$NON-NLS-1$
        BmBinaryImportHelper.XmlResult r =
            BmBinaryImportHelper.toXml(missing, null, null, null, Paths.get("C:/aiedt-tests-out")); //$NON-NLS-1$
        assertNotNull("a missing file must be reported, not thrown", r.error); //$NON-NLS-1$
        assertNull("nothing may be created for input that cannot work", r.stagingInfobaseName); //$NON-NLS-1$
    }

    @Test
    public void anInfobaseThatWasNeverCreatedIsNotReportedAsLeftBehind()
    {
        // Measured on 2026-08-15: a .cf the installed platform will not read fails at creation, and
        // the reply still named a staging infobase to go and delete. There was none - the name is
        // picked before anything exists. A caller acting on that hunts for a ghost.
        BmBinaryImportHelper.XmlResult r = BmBinaryImportHelper.toXml(
            Paths.get("C:/aiedt-tests-no-such-file.cf"), null, null, null, //$NON-NLS-1$
            Paths.get("C:/aiedt-tests-out")); //$NON-NLS-1$
        assertFalse("nothing was created, so nothing can be left behind", r.stagingCreated); //$NON-NLS-1$
        assertFalse(r.stagingRemoved);
    }

    @Test
    public void somethingThatIsNeitherFormatIsRefusedByName()
    {
        BmBinaryImportHelper.XmlResult r = BmBinaryImportHelper.toXml(
            Paths.get("C:/tmp/Обработка.epf"), null, null, null, Paths.get("C:/aiedt-tests-out")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(r.error);
        assertTrue("the answer must name what was expected, got: " + r.error, //$NON-NLS-1$
            r.error.contains(".cf")); //$NON-NLS-1$
        assertNull(r.stagingInfobaseName);
    }
}
