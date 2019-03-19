package org.opennms.tools.mib2conf;

import java.io.File;

import org.junit.Assert;
import org.junit.Test;
import org.opennms.core.xml.JaxbUtils;
import org.opennms.netmgt.config.datacollection.DatacollectionGroup;
import org.opennms.netmgt.xml.eventconf.Events;

import net.percederberg.mibble.Mib;

public class Mib2ConfTest {

    @Test
    public void testDataCollection() throws Exception {
        Mib2Conf mib2Conf = new Mib2Conf();
        Mib mib = mib2Conf.loadMib(new File("mibs/IF-MIB.txt"));
        DatacollectionGroup group = mib2Conf.getDataCollection(mib);
        System.out.println(JaxbUtils.marshal(group));
        Assert.assertEquals(5, group.getResourceTypes().size());
        Assert.assertEquals(7, group.getGroups().size());
    }

    @Test
    public void testEvents() throws Exception {
        Mib2Conf mib2Conf = new Mib2Conf();
        Mib mib = mib2Conf.loadMib(new File("mibs/BATM-DRY-CONTACTS-MIB.my"));
        Events events = mib2Conf.getEvents(mib, "uei.opennms.org/traps/" + mib.getName());
        System.out.println(JaxbUtils.marshal(events));
        Assert.assertEquals(1, events.getEvents().size());
        Assert.assertEquals("uei.opennms.org/traps/BATM-DRY-CONTACTS-MIB/inputStateChangedTrap", events.getEvents().get(0).getUei());
    }
}
