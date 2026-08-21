/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.ContainedObject;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * Guards the internal information a new configuration must carry.
 * <p>
 * <b>Without it the project looks finished and the platform will not take it.</b> It opens, edits
 * and validates clean - the export validator scans it and reports nothing - and then the infobase
 * update is refused with "Отсутствует внутренняя информация (узел InternalInfo) для объекта
 * Configuration", naming a {@code /Configuration.xml} an EDT project does not have. So the most
 * ordinary path there is - create a project, put it in an infobase - ended at the first step and
 * pointed at the wrong file.
 * </p>
 * <p>
 * The number seven is a census, not a preference: of 60 configurations on one machine, 52 real ones
 * carried exactly this set and the 8 that carried none were all made here.
 * </p>
 */
public class ConfigurationInternalInfoTest
{
    private static Configuration created()
    {
        return BmConfigurationProjectHelper.newConfigurationShell("Probe", null); //$NON-NLS-1$
    }

    @Test
    public void aNewConfigurationCarriesItsInternalInformation()
    {
        assertEquals("the platform refuses a configuration that carries none", 7, //$NON-NLS-1$
            created().getContainedObjects().size());
    }

    @Test
    public void everyEntryIsNamedOnBothHalves()
    {
        for (ContainedObject contained : created().getContainedObjects())
        {
            assertNotNull("a class id is what the platform matches on", contained.getClassId()); //$NON-NLS-1$
            assertNotNull("an object id is what identifies this one", contained.getObjectId()); //$NON-NLS-1$
        }
    }

    @Test
    public void theClassIdsAreDistinct()
    {
        Set<UUID> seen = new HashSet<>();
        for (ContainedObject contained : created().getContainedObjects())
        {
            assertTrue("a class id appearing twice would be one entry short of the seven", //$NON-NLS-1$
                seen.add(contained.getClassId()));
        }
    }

    @Test
    public void twoConfigurationsGetDifferentObjectIds()
    {
        // Copying them from an existing configuration also loads, and hands every project made
        // here the same identity. The ids that identify THIS configuration have to be its own.
        Set<UUID> first = new HashSet<>();
        for (ContainedObject contained : created().getContainedObjects())
        {
            first.add(contained.getObjectId());
        }
        for (ContainedObject contained : created().getContainedObjects())
        {
            assertTrue("an object id was reused between two configurations", //$NON-NLS-1$
                !first.contains(contained.getObjectId()));
        }
    }

    @Test
    public void theShellStillCarriesWhatItCarriedBefore()
    {
        // Seeding is an addition, not a replacement: the uuid, the name and managed locking were
        // each measured into place earlier and each fixed a project that opened with findings.
        Configuration shell = created();
        assertEquals("Probe", shell.getName()); //$NON-NLS-1$
        assertNotNull(shell.getUuid());
        assertNotNull(shell.getDataLockControlMode());
    }

    @Test
    public void anEmptyShellHasNoneOfIt()
    {
        // The state the eight projects on this machine are in, pinned so the difference the seeding
        // makes stays visible.
        assertTrue(MdClassFactory.eINSTANCE.createConfiguration().getContainedObjects().isEmpty());
    }
}
