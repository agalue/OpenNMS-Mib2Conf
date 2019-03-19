/*
 * 
 */
package org.opennms.tools.mib2conf;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.opennms.core.xml.JaxbUtils;
import org.opennms.netmgt.collection.support.IndexStorageStrategy;
import org.opennms.netmgt.config.datacollection.DatacollectionGroup;
import org.opennms.netmgt.config.datacollection.Group;
import org.opennms.netmgt.config.datacollection.MibObj;
import org.opennms.netmgt.config.datacollection.PersistenceSelectorStrategy;
import org.opennms.netmgt.config.datacollection.ResourceType;
import org.opennms.netmgt.config.datacollection.StorageStrategy;
import org.opennms.netmgt.model.OnmsSeverity;
import org.opennms.netmgt.xml.eventconf.Decode;
import org.opennms.netmgt.xml.eventconf.Event;
import org.opennms.netmgt.xml.eventconf.Events;
import org.opennms.netmgt.xml.eventconf.LogDestType;
import org.opennms.netmgt.xml.eventconf.Logmsg;
import org.opennms.netmgt.xml.eventconf.Mask;
import org.opennms.netmgt.xml.eventconf.Maskelement;
import org.opennms.netmgt.xml.eventconf.Varbindsdecode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.percederberg.mibble.Mib;
import net.percederberg.mibble.MibLoader;
import net.percederberg.mibble.MibLoaderException;
import net.percederberg.mibble.MibSymbol;
import net.percederberg.mibble.MibType;
import net.percederberg.mibble.MibTypeTag;
import net.percederberg.mibble.MibValue;
import net.percederberg.mibble.MibValueSymbol;
import net.percederberg.mibble.snmp.SnmpNotificationType;
import net.percederberg.mibble.snmp.SnmpObjectType;
import net.percederberg.mibble.snmp.SnmpTrapType;
import net.percederberg.mibble.snmp.SnmpType;
import net.percederberg.mibble.type.IntegerType;
import net.percederberg.mibble.value.ObjectIdentifierValue;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The Class Mib2Conf.
 * https://www.mibble.org/doc/faq-java-api.html
 *
 */
@Command(name="mib2conf", mixinStandardHelpOptions=true, version="1.0.0")
public class Mib2Conf implements Runnable {

    enum ConfTarget { events, dataCollection };

    /** The Constant LOG. */
    private static final Logger LOG = LoggerFactory.getLogger(Mib2Conf.class);

    private static final Pattern TRAP_OID_PATTERN = Pattern.compile("(.*)\\.(\\d+)$");

    @Option(names={"-m","--mibFile"}, paramLabel="mib", description="Path to the MIB file\nDependencies should be on the same directory", required=true)
    String mibFile;

    @Option(names={"-t","--target"}, paramLabel="target", description="Target Configuration: events, dataCollection\nDefault: events")
    ConfTarget target = ConfTarget.events;

    private MibLoader loader = new MibLoader();

    /**
     * The main method.
     *
     * @param args the arguments
     */
    public static void main(String[] args) throws Exception {
        Mib2Conf app = CommandLine.populateCommand(new Mib2Conf(), args);
        CommandLine.run(app, args);
    }

    @Override
    public void run() {
        try {
            Mib mib = loadMib(new File(mibFile));
            if (target == ConfTarget.events) {
                Events events = getEvents(mib, "uei.opennms.org/traps/" + mib.getName()); // FIXME
                if (events.getEvents().isEmpty()) {
                    LOG.warn("No traps were found on {}", mib.getName());
                } else {
                    System.out.println(JaxbUtils.marshal(events)); // FIXME
                }
            }
            if (target == ConfTarget.dataCollection) {
                DatacollectionGroup group = getDataCollection(mib);
                System.out.println(JaxbUtils.marshal(group)); // FIXME
            }
        } catch (MibLoaderException e) {
            e.getLog().printTo(System.err);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Mib loadMib(File file) throws MibLoaderException, IOException {
        loader.addDir(file.getParentFile()); // The MIB file may import other MIBs (often in same directory)
        Mib mib = loader.load(file);
        if (mib.getLog().warningCount() > 0) {
            mib.getLog().printTo(System.err);
        }
        return mib;
    }

    public Events getEvents(Mib mib, String baseUei) {
        LOG.info("Generating event configuration for {}...", mib.getName());
        Events events = new Events();
        for (MibSymbol symbol : mib.getAllSymbols()) {
            if (!(symbol instanceof MibValueSymbol)) {
                continue;
            }
            MibValueSymbol valueSymbol = (MibValueSymbol) symbol;
            if ((!(valueSymbol.getType() instanceof SnmpNotificationType)) && (!(valueSymbol.getType() instanceof SnmpTrapType))) {
                continue;
            }
            LOG.info("Processing trap {}...", valueSymbol.getName());
            events.addEvent(getTrapEvent(valueSymbol, baseUei));
        }
        return events;
    }

    public DatacollectionGroup getDataCollection(Mib mib) {
        LOG.info("Generating data collection configuration for {}", mib.getName());
        DatacollectionGroup dcGroup = new DatacollectionGroup();
        dcGroup.setName(mib.getName());
        extractOids(mib).forEach((k,v) -> {
            for (ObjectIdentifierValue child : v.getAllChildren()) {
                SnmpObjectType containerType = getSnmpObjectType(v.getParent().getSymbol());
                String groupName = containerType == null ? v.getName() : v.getParent().getName();
                String resourceType = containerType == null ? null : v.getName();
                SnmpObjectType objectType = getSnmpObjectType(child.getSymbol());
                if (child.getChildCount() == 0 && objectType != null) {
                    Group group = getGroup(dcGroup, groupName, resourceType);
                    MibObj mibObj = new MibObj();
                    mibObj.setOid("." + child);
                    mibObj.setInstance(resourceType == null ? "0" : resourceType);
                    mibObj.setAlias(child.getName());
                    mibObj.setType(getType(objectType.getSyntax()));
                    group.addMibObj(mibObj);
                }
            }
        });
        return dcGroup;
    }

    private HashMap<String,ObjectIdentifierValue> extractOids(Mib mib) {
        HashMap<String,ObjectIdentifierValue> map = new HashMap<>();
        for (MibSymbol symbol : mib.getAllSymbols()) {
            ObjectIdentifierValue oid = extractOid(symbol);
            if (oid != null) {
                map.put(symbol.getName(), oid);
            }
        }
        return map;
    }

    private ObjectIdentifierValue extractOid(MibSymbol symbol) {
        if (symbol instanceof MibValueSymbol) {
            MibValue value = ((MibValueSymbol) symbol).getValue();
            if (value instanceof ObjectIdentifierValue) {
                return (ObjectIdentifierValue) value;
            }
        }
        return null;
    }

    private SnmpObjectType getSnmpObjectType(MibSymbol symbol) {
        if (symbol instanceof MibValueSymbol) {
            MibType type = ((MibValueSymbol) symbol).getType();
            if (type instanceof SnmpObjectType) {
                return (SnmpObjectType) type;
            }
        }
        return null;
    }

    private Event getTrapEvent(MibValueSymbol trapValueSymbol, String ueibase) {
        Event event = new Event();
        event.setUei(getTrapEventUEI(trapValueSymbol, ueibase));
        event.setEventLabel(getTrapEventLabel(trapValueSymbol));
        event.setLogmsg(getTrapEventLogmsg(trapValueSymbol));
        event.setSeverity(OnmsSeverity.INDETERMINATE.getLabel());
        event.setDescr(getTrapEventDescr(trapValueSymbol));
        List<Varbindsdecode> decodes = getTrapVarbindsDecode(trapValueSymbol);
        if (!decodes.isEmpty()) {
            event.setVarbindsdecodes(decodes);
        }
        event.setMask(new Mask());
        // The "ID" mask element (trap enterprise)
        addMaskElement(event, "id", getTrapEnterprise(trapValueSymbol));
        // The "generic" mask element: hard-wired to enterprise-specific(6)
        addMaskElement(event, "generic", "6");
        // The "specific" mask element (trap specific-type)
        addMaskElement(event, "specific", getTrapSpecificType(trapValueSymbol));
        return event;
    }

    private String getTrapEnterprise(MibValueSymbol trapValueSymbol) {
        return getMatcherForOid(getTrapOid(trapValueSymbol)).group(1);
    }

    private String getTrapSpecificType(MibValueSymbol trapValueSymbol) {
        return getMatcherForOid(getTrapOid(trapValueSymbol)).group(2);
    }

    private String getTrapOid(MibValueSymbol trapValueSymbol) {
        if (trapValueSymbol.getType() instanceof SnmpNotificationType) {
            return "." + trapValueSymbol.getValue().toString();
        } else if (trapValueSymbol.getType() instanceof SnmpTrapType) {
            SnmpTrapType v1trap = (SnmpTrapType) trapValueSymbol.getType();
            return "." + v1trap.getEnterprise().toString() + "." + trapValueSymbol.getValue().toString();
        } else {
            throw new IllegalStateException("Trying to get trap information from an object that's not a trap and not a notification");
        }
    }

    private Matcher getMatcherForOid(String trapOid) {
        Matcher matcher = TRAP_OID_PATTERN.matcher(trapOid);
        if (!matcher.matches()) {
            throw new IllegalStateException("Could not match the trap OID '" + trapOid + "' against '" + matcher.pattern().pattern() + "'");
        }
        return matcher;
    }

    private String getTrapEventLabel(MibValueSymbol trapValueSymbol) {
        StringBuffer buffer = new StringBuffer();
        buffer.append(trapValueSymbol.getMib());
        buffer.append(" defined trap event: ");
        buffer.append(trapValueSymbol.getName());
        return buffer.toString();
    }

    private String getTrapEventUEI(MibValueSymbol trapValueSymbol, String ueibase) {
        StringBuffer buf = new StringBuffer(ueibase);
        if (! ueibase.endsWith("/")) {
            buf.append("/");
        }
        buf.append(trapValueSymbol.getName());
        return buf.toString();
    }

    private List<MibValue> getTrapVars(MibValueSymbol trapValueSymbol) {
        if (trapValueSymbol.getType() instanceof SnmpNotificationType) {
            SnmpNotificationType v2notif = (SnmpNotificationType) trapValueSymbol.getType();
            return getV2NotificationObjects(v2notif);
        } else if (trapValueSymbol.getType() instanceof SnmpTrapType) {
            SnmpTrapType v1trap = (SnmpTrapType) trapValueSymbol.getType();
            return getV1TrapVariables(v1trap);
        } else {
            throw new IllegalStateException("trap type is not an SNMP v1 Trap or v2 Notification");      
        }
    }

    private List<MibValue> getV1TrapVariables(SnmpTrapType v1trap) {
        return v1trap.getVariables();
    }

    private List<MibValue> getV2NotificationObjects(SnmpNotificationType v2notif) {
        return v2notif.getObjects();
    }

    private Logmsg getTrapEventLogmsg(MibValueSymbol trapValueSymbol) {
        Logmsg msg = new Logmsg();
        msg.setDest(LogDestType.LOGNDISPLAY);
        final StringBuffer dbuf = new StringBuffer();
        dbuf.append(trapValueSymbol.getName()).append(" trap received\n");
        int vbNum = 1;
        for (MibValue vb : getTrapVars(trapValueSymbol)) {
            dbuf.append("\t").append(vb.getName()).append("=%parm[#").append(vbNum).append("]%\n");
            vbNum++;
        }
        if (dbuf.charAt(dbuf.length() - 1) == '\n') {
            dbuf.deleteCharAt(dbuf.length() - 1); // delete the \n at the end
        }
        msg.setContent(dbuf.toString());
        return msg;
    }

    private String getTrapEventDescr(MibValueSymbol trapValueSymbol) {
        String description = ((SnmpType) trapValueSymbol.getType()).getDescription();
        final StringBuffer buffer = new StringBuffer(description);
        buffer.append("\n\t<table>\n");
        int vbNum = 1;
        for (MibValue vb : getTrapVars(trapValueSymbol)) {
            buffer.append("\t<tr><td><b>").append(vb.getName());
            buffer.append("</b></td><td>%parm[#").append(vbNum).append("]%;</td><td><p>");
            SnmpObjectType snmpObjectType = ((SnmpObjectType) ((ObjectIdentifierValue) vb).getSymbol().getType());
            if (snmpObjectType.getSyntax().getClass().equals(IntegerType.class)) {
                IntegerType integerType = (IntegerType) snmpObjectType.getSyntax();
                if (integerType.getAllSymbols().length > 0) {
                    SortedMap<Integer, String> map = new TreeMap<Integer, String>();
                    for (MibValueSymbol sym : integerType.getAllSymbols()) {
                        map.put(new Integer(sym.getValue().toString()), sym.getName());
                    }
                    for (Entry<Integer, String> entry : map.entrySet()) {
                        buffer.append(entry.getValue()).append("(").append(entry.getKey()).append("), ");
                    }
                    buffer.delete(buffer.length()-2, buffer.length());
                }
            }
            buffer.append("</p></td></tr>\n");
            vbNum++;
        }
        buffer.append("\t</table>");
        return buffer.toString();
    }

    private void addMaskElement(Event event, String name, String value) {
        if (event.getMask() == null) {
            throw new IllegalStateException("Event mask is null, must have been set before this method was called");
        }
        Maskelement me = new Maskelement();
        me.setMename(name);
        me.addMevalue(value);
        event.getMask().addMaskelement(me);
    }

    private List<Varbindsdecode> getTrapVarbindsDecode(MibValueSymbol trapValueSymbol) {
        Map<String, Varbindsdecode> decode = new LinkedHashMap<String, Varbindsdecode>();
        int vbNum = 1;
        for (MibValue vb : getTrapVars(trapValueSymbol)) {
            String parmName = "parm[#" + vbNum + "]";
            SnmpObjectType snmpObjectType = ((SnmpObjectType) ((ObjectIdentifierValue) vb).getSymbol().getType());
            if (snmpObjectType.getSyntax().getClass().equals(IntegerType.class)) {
                IntegerType integerType = (IntegerType) snmpObjectType.getSyntax();
                if (integerType.getAllSymbols().length > 0) {
                    SortedMap<Integer, String> map = new TreeMap<Integer, String>();
                    for (MibValueSymbol sym : integerType.getAllSymbols()) {
                        map.put(new Integer(sym.getValue().toString()), sym.getName());
                    }
                    for (Entry<Integer, String> entry : map.entrySet()) {
                        if (!decode.containsKey(parmName)) {
                            Varbindsdecode newVarbind = new Varbindsdecode();
                            newVarbind.setParmid(parmName);
                            decode.put(newVarbind.getParmid(), newVarbind);
                        }
                        Decode d = new Decode();
                        d.setVarbinddecodedstring(entry.getValue());
                        d.setVarbindvalue(entry.getKey().toString());
                        decode.get(parmName).addDecode(d);
                    }
                }
            }
            vbNum++;
        }
        return new ArrayList<Varbindsdecode>(decode.values());
    }

    public String getType(MibType type) {
        if (type.hasTag(MibTypeTag.APPLICATION_CATEGORY, 1)) {
            return "counter";
        }
        if (type.hasTag(MibTypeTag.APPLICATION_CATEGORY, 2)) {
            return "integer";
        }
        if (type.hasTag(MibTypeTag.APPLICATION_CATEGORY, 3)) {
            return "timeticks";
        }
        if (type.hasTag(MibTypeTag.APPLICATION_CATEGORY, 6)) {
            return "counter64";
        }
        return "string";
    }

    protected Group getGroup(DatacollectionGroup data, String groupName, String resourceType) {
        for (Group group : data.getGroups()) {
            if (group.getName().equals(groupName))
                return group;
        }
        Group group = new Group();
        group.setName(groupName);
        group.setIfType(resourceType == null ? "ignore" : "all");
        if (resourceType != null) {
            ResourceType type = new ResourceType();
            type.setName(resourceType);
            type.setLabel(resourceType);
            type.setResourceLabel("${index}");
            type.setPersistenceSelectorStrategy(new PersistenceSelectorStrategy("org.opennms.netmgt.collection.support.PersistAllSelectorStrategy")); // To avoid requires opennms-services
            type.setStorageStrategy(new StorageStrategy(IndexStorageStrategy.class.getName()));
            data.addResourceType(type);
        }
        data.addGroup(group);
        return group;
    }

}
