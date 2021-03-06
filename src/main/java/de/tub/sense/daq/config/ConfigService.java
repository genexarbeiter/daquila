package de.tub.sense.daq.config;

import cern.c2mon.client.core.service.ConfigurationService;
import cern.c2mon.shared.client.configuration.ConfigConstants;
import cern.c2mon.shared.client.configuration.ConfigurationElementReport;
import cern.c2mon.shared.client.configuration.ConfigurationReport;
import cern.c2mon.shared.client.configuration.api.equipment.Equipment;
import cern.c2mon.shared.client.configuration.api.tag.AliveTag;
import cern.c2mon.shared.client.configuration.api.tag.StatusTag;
import cern.c2mon.shared.common.datatag.DataTagAddress;
import cern.c2mon.shared.common.datatag.address.impl.SimpleHardwareAddressImpl;
import de.tub.sense.daq.config.file.ConfigurationFile;
import de.tub.sense.daq.config.xml.CommandTag;
import de.tub.sense.daq.config.xml.DataTag;
import de.tub.sense.daq.config.xml.EquipmentAddress;
import de.tub.sense.daq.config.xml.EquipmentUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

/**
 * @author maxmeyer
 * @created 09/01/2021 - 11:32
 * @project DAQConfigLoader
 */

@Service
@Slf4j
@SuppressWarnings(value = "unused")
public class ConfigService {

    private final DAQConfiguration daqConfiguration;
    private final ProcessConfiguration processConfiguration;
    private final ConfigurationService configurationService;

    private final String PROCESS_NAME;

    private boolean c2monConfigurationLoaded = false;

    public ConfigService(DAQConfiguration daqConfiguration, ProcessConfiguration processConfiguration, ConfigurationService configurationService) {
        this.daqConfiguration = daqConfiguration;
        this.processConfiguration = processConfiguration;
        this.configurationService = configurationService;
        PROCESS_NAME = System.getProperty("c2mon.daq.name");
        if (processExists()) {
            reloadC2monConfiguration();
        }
    }


    /**
     * Returns the local configuration for the DAQ
     *
     * @return ConfigurationFile object
     */
    protected ConfigurationFile getConfigurationFile() {
        return daqConfiguration.getConfiguration();
    }

    /**
     * Checks if the configuration for the daq is loaded from the c2mon server yet
     *
     * @return true if already loaded at least once, false if not
     */
    protected boolean isC2monConfigurationLoaded() {
        return c2monConfigurationLoaded;
    }

    /**
     * Downloads the XML configuration file from the C2mon server and parses it into a processConfiguration object
     */
    protected void reloadC2monConfiguration() {
        processConfiguration.init(configurationService.getProcessXml(PROCESS_NAME));
        c2monConfigurationLoaded = true;
    }

    /**
     * Get all equipment units for the DAQ
     *
     * @return list of equipment unit objects
     */
    protected ArrayList<EquipmentUnit> getEquipmentUnits() {
        return processConfiguration.getConfig().getEquipmentUnits();
    }

    /**
     * Update a data tag on the C2mon server without deleting it
     *
     * @param dataTag you want to update
     */
    protected void updateDataTag(DataTag dataTag) {
        if (dataTag.getAddress().getBitNumber() < 0 || dataTag.getAddress().getBitNumber() > 15) {
            throw new IllegalArgumentException("Bitnumber for tag " + dataTag.getName() + " is " + dataTag.getAddress().getBitNumber() + " but it has to be in the interval [0, 15]");
        }
        cern.c2mon.shared.client.configuration.api.tag.DataTag updatedTag = cern.c2mon.shared.client.configuration.api.tag.DataTag.update(dataTag.getId())
                .address(new DataTagAddress(getSimpleHardwareAddress(dataTag.getAddress().getStartAddress(),
                        dataTag.getAddress().getValueCount(), dataTag.getAddress().getType(), dataTag.getAddress().getOffset(),
                        dataTag.getAddress().getMultiplier(), dataTag.getAddress().getThreshold(), dataTag.getAddress().getBitNumber())))
                .dataType(dataTypeClass(dataTag.getDataType()))
                .build();
        configurationService.updateDataTag(updatedTag);
    }

    /**
     * Update a command tag on the C2mon server without deleting it
     *
     * @param commandTag you want to update
     */
    protected void updateCommandTag(CommandTag commandTag) {
        if (commandTag.getAddress().getMinValue() == commandTag.getAddress().getMaxValue() && commandTag.getAddress().getMaxValue() == 0) {
            throw new IllegalArgumentException("Minimum and maximum for command tag " + commandTag.getName() + " is not set. " +
                    "For a boolean it has to be [0, 1], for any other data type it is up to you. You can specify minimum and maximum for any signal in the config file");
        }
        cern.c2mon.shared.client.configuration.api.tag.CommandTag updatedTag = cern.c2mon.shared.client.configuration.api.tag.CommandTag.update(commandTag.getId())
                .minimum(commandTag.getAddress().getMinValue())
                .maximum(commandTag.getAddress().getMaxValue())
                .hardwareAddress(getSimpleHardwareAddress(commandTag.getAddress().getStartAddress(),
                        commandTag.getAddress().getValueCount(), commandTag.getAddress().getType(),
                        commandTag.getAddress().getMinValue(), commandTag.getAddress().getMaxValue(), commandTag.getAddress().getBitNumber()))
                .dataType(dataTypeClass(commandTag.getDataType()))
                .build();
        configurationService.updateCommandTag(updatedTag);
    }

    /**
     * Update a equipment on the C2mon server without deleting it
     *
     * @param equipmentUnit you want to update
     */
    protected void updateEquipment(EquipmentUnit equipmentUnit) {
        EquipmentAddress address = equipmentUnit.getEquipmentAddress();
        Equipment equipment = Equipment.update(equipmentUnit.getId())
                .address(getEquipmentAddress(address.getHost(), address.getPort(), address.getUnitId(), address.getRefreshInterval()))
                .aliveInterval(equipmentUnit.getAliveTagInterval())
                .build();
        configurationService.updateEquipment(equipment);
    }

    /**
     * Create the process on the C2mon server
     */
    protected void createProcess() {
        if (processExists()) {
            throw new IllegalArgumentException("Process with name " + PROCESS_NAME + " already exists.");
        } else {
            ConfigurationReport report = configurationService.createProcess(PROCESS_NAME);
            if (log.isDebugEnabled()) {
                log.debug("Received configuration report for process {}, with status {}, with status description {}.", report.getName(), report.getStatus().toString(), report.getStatusDescription());
            }
        }
    }

    /**
     * Remove a process from the C2mon server
     */
    protected void removeProcess() {
        if (processExists()) {
            configurationService.removeProcess(PROCESS_NAME);
        } else {
            throw new IllegalArgumentException("No process with name " + PROCESS_NAME + " found.");
        }
    }

    /**
     * Remove the process and its equipments from C2mon
     */
    protected void removeProcessFromC2monEntirely() {
        log.info("Removing process {} entirely...", PROCESS_NAME);
        if (processExists()) {
            reloadC2monConfiguration();
            for (EquipmentUnit equipmentUnit : getEquipmentUnits()) {
                log.info("Removing data tags...");
                for (DataTag dataTag : equipmentUnit.getDataTags()) {
                    log.info("Removing data tag {}...", dataTag.getName());
                    configurationService.removeDataTagById(dataTag.getId());
                }
                log.debug("Removing command tags...");
                for (CommandTag commandTag : equipmentUnit.getCommandTags()) {
                    configurationService.removeCommandTagById(commandTag.getId());
                    log.info("Removing command tag {}...", commandTag.getName());
                }
                log.info("Removing equipment {}...", equipmentUnit.getName());
                configurationService.removeCommandTagById(equipmentUnit.getCommfaultTagId());
                configurationService.removeEquipmentById(equipmentUnit.getId());
            }
            removeProcess();
            log.info("Removed process {} entirely", PROCESS_NAME);
        } else {
            throw new IllegalArgumentException("No process with name " + PROCESS_NAME + " found.");
        }
    }

    /**
     * Create a equipment for an existing process
     *
     * @param equipmentName    of the equipment
     * @param handlerClassName of the message handler for the equipment
     * @param aliveTagInterval to send an alive tag in millis
     * @param host             of the equipment
     * @param port             of the equipment
     * @param unitId           of the equipment
     */
    protected void createEquipment(String equipmentName, String handlerClassName, int refreshInterval, int aliveTagInterval, String host, long port, int unitId) {
        if (log.isDebugEnabled()) {
            log.debug("Creating equipment {} for process {} with handlerClass {}", equipmentName, PROCESS_NAME, handlerClassName);
        }
        Equipment equipmentToCreate = Equipment.create(equipmentName, handlerClassName)
                .aliveTag(AliveTag.create(equipmentName + ":ALIVE").build(), aliveTagInterval)
                .statusTag(StatusTag.create(equipmentName + ":STATUS").build())
                .address(getEquipmentAddress(host, port, unitId, refreshInterval))
                .build();

        ConfigurationReport report = configurationService.createEquipment(PROCESS_NAME, equipmentToCreate);
        for (ConfigurationElementReport elementReport : report.getElementReports()) {
            if (elementReport.isFailure()) {
                log.warn("Action {} of entity {} failed with: {}", elementReport.getAction(), elementReport.getEntity(), elementReport.getStatusMessage());
            } else if (elementReport.isSuccess() && log.isDebugEnabled()) {
                log.debug("Action {} of entity {} succeeded", elementReport.getAction(), elementReport.getEntity());
            }
        }
        if (report.getStatus().equals(ConfigConstants.Status.FAILURE)) {
            log.warn("Creating equipment failed with status description: {}", report.getStatusDescription());
        } else if (report.getStatus().equals(ConfigConstants.Status.RESTART) && log.isDebugEnabled()) {
            log.debug("Creating equipment success with status description: {}", report.getStatusDescription());
        }
    }

    /**
     * Create a equipment for an existing process
     *
     * @param equipmentName    of the equipment
     * @param handlerClassName of the message handler for the equipment
     * @param host             of the equipment
     * @param port             of the equipment
     * @param unitId           of the equipment
     */
    protected void createEquipment(String equipmentName, String handlerClassName, String host, int refreshInterval, long port, int unitId) {
        createEquipment(equipmentName, handlerClassName, refreshInterval, 100000, host, port, unitId);
    }

    /**
     * Checks if a process already exists on the C2mon server
     *
     * @return true if process exists false if not
     */
    protected boolean processExists() {
        return configurationService.getProcessNames().stream().anyMatch(process -> process.getProcessName().equals(PROCESS_NAME));
    }

    /**
     * Get the XML configuration for a process name
     *
     * @return XML configuration as String
     */
    protected String getProcessConfigurationXML() {
        return configurationService.getProcessXml(PROCESS_NAME);
    }

    /**
     * Create a data tag for a given equipment
     *
     * @param equipmentName of the equipment
     * @param tagName       of the tag
     * @param datatype      of the tag
     * @param startAddress  of the related register
     * @param registerType  of the related register
     * @param valueCount    of the related register
     */
    protected void createDataTag(String equipmentName, String tagName, String datatype, int startAddress,
                                 String registerType, int valueCount, double offset, double multiplier, double threshold, int bitNumber) {
        if (bitNumber < 0 || bitNumber > 15) {
            throw new IllegalArgumentException("Bitnumber for tag " + tagName + " is " + bitNumber + " but it has to be in the interval [0, 15]");
        }
        configurationService.createDataTag(equipmentName, equipmentName + "/" + tagName, dataTypeClass(datatype),
                new DataTagAddress(getSimpleHardwareAddress(startAddress, valueCount, registerType, offset, multiplier, threshold, bitNumber)));
    }

    /**
     * Create a command tag for a given equipment
     *
     * @param equipmentName of the equipment
     * @param tagName       of the tag
     * @param datatype      of the tag
     * @param startAddress  of the related register
     * @param registerType  of the related register
     * @param valueCount    of the related register
     * @param min           of the related register
     * @param max           of the related register
     */
    protected void createCommandTag(String equipmentName, String tagName, String datatype, int startAddress,
                                    String registerType, int valueCount, double min, double max, int bitNumber) {
        if (min == max && max == 0) {
            throw new IllegalArgumentException("Minimum and maximum for command tag " + tagName + " is not set." +
                    " For a boolean it has to be [0, 1], for any other data type it is up to you. You can specify minimum and maximum for any signal in the config file");
        }
        int clientTimeout = 5001; // Client timeout: must be >= 5000
        int execTimeout = 5000; // Execution timeout: must > (source retries + 1) * source timeout
        int sourceTimeout = 1001; // Source timeout: must be >= 1000
        int sourceRetries = 2; // Source retries
        String rbacClass = "foo"; // Must at least contain one non white space character, not used at SENSE
        String rbacDevice = "foo"; // Must at least contain one non white space character, not used at SENSE
        String rbacProperty = "foo"; // Must at least contain one non white space character, not used at SENSE
        cern.c2mon.shared.client.configuration.api.tag.CommandTag commandTag = cern.c2mon.shared.client.configuration.api.tag.CommandTag.create(equipmentName + "/" + tagName,
                dataTypeClass(datatype), getSimpleHardwareAddress(startAddress, valueCount, registerType, min, max, bitNumber),
                clientTimeout, execTimeout, sourceTimeout, sourceRetries, rbacClass, rbacDevice, rbacProperty)
                .minimum(min)
                .maximum(max)
                .build();

        configurationService.createCommandTag(equipmentName, commandTag);
    }

    /**
     * Parses a register with startAddress, valueCount and registerType to a SimpleHardwareAddressImplementation
     *
     * @param startAddress of the register
     * @param valueCount   of the register
     * @param registerType of the register (e.g. holding32)
     * @param offset       of the value
     * @param multiplier   of the value
     * @param threshold    of the value
     * @return SimpleHardwareAddressImpl object for the given arguments, which can be sent to the C2mon server
     */
    private SimpleHardwareAddressImpl getSimpleHardwareAddress(int startAddress, int valueCount, String registerType, double offset, double multiplier, double threshold, int bitNumber) {
        return new SimpleHardwareAddressImpl("{\"startAddress\":" + startAddress + ",\"readValueCount\":"
                + valueCount + ",\"readingType\":\"" + registerType + "\", \"value_offset\":" + offset + ",\"value_multiplier\":" + multiplier + ",\"value_threshold\":" + threshold + ",\"bitNumber\":" + bitNumber + "}");
    }

    /**
     * Parses a register with start address, value count, register type, minimum and maximum to a SimpleHardwareAddressImplementation
     *
     * @param startAddress of the register
     * @param valueCount   of the register
     * @param registerType of the register
     * @param min          of the value
     * @param max          of the value
     * @return SimpleHardwareAddressImpl object for the given arguments, which can be sent to the C2mon server
     */
    private SimpleHardwareAddressImpl getSimpleHardwareAddress(int startAddress, int valueCount, String registerType, double min, double max, int bitNumber) {
        return new SimpleHardwareAddressImpl("{\"startAddress\":" + startAddress + ",\"writeValueCount\":"
                + valueCount + ",\"writingType\":\"" + registerType + "\", \"minimum\":" + min + ",\"maximum\":" + max + ",\"bitNumber\":" + bitNumber + "}");
    }

    /**
     * Parses a equipment address with host port unit id and refresh interval to a valid equipment address string
     *
     * @param host            of the equipment
     * @param port            of the equipment
     * @param unitId          of the equipment
     * @param refreshInterval of the equipment
     * @return valid equipment address hashmap string
     */
    private String getEquipmentAddress(String host, long port, int unitId, int refreshInterval) {
        return "{\"host\":\"" + host + "\",\"port\":" + port + ",\"unitID\":" + unitId + ",\"refreshInterval\":" + refreshInterval + "}";
    }

    /**
     * Get the names of all running processes on the C2mon
     *
     * @return list of process names
     */
    protected ArrayList<String> getAllProcesses() {
        ArrayList<String> processes = new ArrayList<>();
        configurationService.getProcessNames().forEach(processNameResponse -> processes.add(processNameResponse.getProcessName()));
        return processes;
    }

    /**
     * Convert a data type string to the corresponding data type class
     *
     * @param datatype as string
     * @return datatype as class
     */
    private Class<?> dataTypeClass(String datatype) {
        switch (datatype) {
            case "bool":
            case "java.lang.Boolean":
                return Boolean.class;
            case "s8":
            case "u8":
            case "java.lang.Byte":
                return Byte.class;
            case "s16":
            case "u16":
            case "java.lang.Short":
                return Short.class;
            case "u32":
            case "s32":
            case "java.lang.Integer":
                return Integer.class;
            case "s64":
            case "u64":
            case "java.lang.Long":
                return Long.class;
            case "float32":
            case "java.lang.Float":
                return Float.class;
            case "float64":
            case "java.lang.Double":
                return Double.class;
            default:
                throw new IllegalArgumentException("Datatype " + datatype + " could not be converted to class");
        }
    }

}
