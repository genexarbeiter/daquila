package de.tub.sense.daq.config;

import de.tub.sense.daq.config.file.ConfigurationFile;
import de.tub.sense.daq.config.file.Equipment;
import de.tub.sense.daq.config.file.Signal;
import de.tub.sense.daq.config.xml.*;
import de.tub.sense.daq.module.DAQMessageHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/**
 * @author maxmeyer
 * @created 10/01/2021 - 11:01
 * @project DAQConfigLoader
 */

@Slf4j
@Component
public class ConfigInitializer implements CommandLineRunner {

    private static final String HANDLER_CLASS_NAME = DAQMessageHandler.class.getName();
    private static boolean FORCE_CONFIGURATION;
    private final ConfigService configService;

    public ConfigInitializer(ConfigService configService) {
        this.configService = configService;
        FORCE_CONFIGURATION = Boolean.parseBoolean(System.getProperty("c2mon.daq.forceConfiguration"));
    }

    /**
     * Run by Spring on start up, checks if the C2mon server is configured properly for the DAQ, if not it configures the
     * C2mon server to work with the DAQ. If c2mon.daq.forceConfiguration is true, the DAQ overwrites any settings for
     * the process of the DAQ and configures it with the config file, if false the configuration on the C2mon server is
     * only updated, and nothing is deleted.
     *
     * @param args from start
     */
    @Override
    public void run(String... args) {

        if (loadConfigFromServer()) {
            log.info("Configuration loaded from C2mon. Checking if force configuration is set to true...");
            if (FORCE_CONFIGURATION) {
                log.info("TRUE! Forcing reconfiguration...");
                removeC2monConfiguration();
                configureC2mon();
                log.info("Finished reconfiguration");
            } else {
                log.info("FALSE! Only updating configuration...");
                updateC2monConfiguration();
                log.info("Finished updating configuration.");
            }
        } else {
            log.info("Configuration is not yet on C2mon server. Configuring...");
            configureC2mon();
        }
    }

    /**
     * Checks if there is a configuration for the DAQ on the C2mon server. If there is one it loads it if the
     * configuration is not already loaded
     *
     * @return true if the configuration is available, false if not
     */
    private boolean loadConfigFromServer() {
        log.info("Loading config from C2mon...");
        log.debug("Checking if process already exists...");
        if (configService.processExists()) {
            if (!configService.isC2monConfigurationLoaded()) {
                configService.reloadC2monConfiguration();
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * Configures the C2mon from config file. If this method is started, no configuration may yet be performed on the C2mon server!
     */
    private void configureC2mon() {
        ConfigurationFile configurationFile = configService.getConfigurationFile();
        if(configurationFile.getEquipments() == null) {
            return;
        }
        configService.createProcess();
        for (Equipment equipment : configurationFile.getEquipments()) {
            createEquipment(equipment);
        }
    }

    /**
     * Updates the C2mon configuration from config file. The method can only be run,
     * if there is already a process for this DAQ configured on the C2mon server.
     */
    private void updateC2monConfiguration() {
        ConfigurationFile configurationFile = configService.getConfigurationFile();
        if(configurationFile.getEquipments() == null) {
            return;
        }
        ArrayList<EquipmentUnit> equipmentUnits = configService.getEquipmentUnits();
        for (Equipment equipment : configurationFile.getEquipments()) {
            boolean equipmentExists = false;
            for (EquipmentUnit equipmentUnit : equipmentUnits) {
                if (equipmentUnit.getName().equals(equipment.getName())) {
                    equipmentExists = true;
                    updateEquipment(equipment, equipmentUnit);
                }
            }
            if (!equipmentExists) {
                createEquipment(equipment);
            }
        }
    }

    /**
     * Creates an equipment for a given equipment object from configuration file
     *
     * @param equipment from the config file to create on the C2mon server
     */
    private void createEquipment(Equipment equipment) {
        if (log.isInfoEnabled()) {
            log.info("Equipment {} not found. Creating a new equipment...", equipment.getName());
        }
        int aliveTagInterval = equipment.getAliveTagInterval() == 0 ? 100000 : equipment.getAliveTagInterval();
        int refreshInterval = equipment.getRefreshInterval() == 0 ? 10000 : equipment.getRefreshInterval();
        configService.createEquipment(
                equipment.getName(),
                HANDLER_CLASS_NAME,
                refreshInterval,
                aliveTagInterval,
                equipment.getConnectionSettings().getAddress(),
                equipment.getConnectionSettings().getPort(),
                equipment.getConnectionSettings().getUnitID());
        for(Signal signal : equipment.getSignals()) {
            createTagFromSignal(signal, equipment.getName());
        }
    }

    /**
     * Updates an equipment. Needs the updated equipment from config file, and the current corresponding
     * equipment unit from the C2mon server
     *
     * @param updatedEquipment     from the config file
     * @param currentEquipmentUnit from the C2mon server
     */
    private void updateEquipment(Equipment updatedEquipment, EquipmentUnit currentEquipmentUnit) {
        if (log.isInfoEnabled()) {
            log.info("Updating equipment {} with id {}...", updatedEquipment.getName(), currentEquipmentUnit.getId());
        }
        currentEquipmentUnit.setEquipmentAddress(new EquipmentAddress(
                updatedEquipment.getConnectionSettings().getAddress(),
                updatedEquipment.getConnectionSettings().getPort(),
                updatedEquipment.getConnectionSettings().getUnitID(),
                updatedEquipment.getRefreshInterval()));
        currentEquipmentUnit.setAliveTagInterval(updatedEquipment.getAliveTagInterval() != 0 ?
                updatedEquipment.getAliveTagInterval() : currentEquipmentUnit.getAliveTagInterval());
        configService.updateEquipment(currentEquipmentUnit);
        for (Signal signal : updatedEquipment.getSignals()) {
            boolean signalExists = false;
            for (DataTag dataTag : currentEquipmentUnit.getDataTags()) {
                if (dataTag.getName().equals(updatedEquipment.getName() + "/" + signal.getName())) {
                    signalExists = true;
                    updateSignal(signal, dataTag);
                }
            }
            for (CommandTag commandTag : currentEquipmentUnit.getCommandTags()) {
                if (commandTag.getName().equals(updatedEquipment.getName() + "/" + signal.getName())) {
                    signalExists = true;
                    updateSignal(signal, commandTag);
                }
            }
            if (!signalExists) {
                if (log.isInfoEnabled()) {
                    log.info("Signal {} not found. Creating a new tag...", signal.getName());
                }
                createTagFromSignal(signal, currentEquipmentUnit.getName());
            }
        }
    }

    /**
     * Updates a read signal. Needs the updated signal from config file, and the current corresponding
     * data tag from the C2mon server
     *
     * @param signal  from the config file
     * @param dataTag from the C2mon server
     */
    private void updateSignal(Signal signal, DataTag dataTag) {
        if (log.isInfoEnabled()) {
            log.info("Updating data tag {} with id {}...", signal.getName(), dataTag.getId());
        }
        dataTag.setDataType(signal.getType());

        dataTag.setAddress(new HardwareAddress(signal.getModbus().getStartAddress(), getCount(signal.getType()),
                signal.getModbus().getRegister(), signal.getOffset(), signal.getMultiplier(), signal.getThreshold(), signal.getModbus().getBitNumber()));
        configService.updateDataTag(dataTag);
    }

    /**
     * Get value count from signal type
     * @param signalType of the signal
     * @return count of registers in modbus
     */
    private int getCount(String signalType) {
        int count = 1;
        switch (signalType) {
            case "bool":
            case "u8":
            case "s8":
            case "u16":
            case "s16":
                count = 1;
                break;
            case "u32":
            case "s32":
            case "float32":
                count = 2;
                break;
            case "u64":
            case "s64":
            case "float64":
                count = 4;
                break;
            default:
                log.warn("Unrecognized signal type {}", signalType);
                break;
        }
        return count;
    }

    /**
     * Creates a data or command tag from a signal. Needs the signal from the config file and the equipments name
     *
     * @param signal        from the config file
     * @param equipmentName of the signal
     */
    private void createTagFromSignal(Signal signal, String equipmentName) {
        if (log.isInfoEnabled()) {
            log.info("Creating signal for tag {}...", signal.getName());
        }
        if (signal.getModbus().getType().equalsIgnoreCase("read")) {
            configService.createDataTag(equipmentName, signal.getName(), signal.getType(),
                    signal.getModbus().getStartAddress(), signal.getModbus().getRegister(), getCount(signal.getType()),
                    signal.getOffset(), signal.getMultiplier(), signal.getThreshold(), signal.getModbus().getBitNumber());
        } else if (signal.getModbus().getType().equalsIgnoreCase("write")) {
            configService.createCommandTag(equipmentName, signal.getName(), signal.getType(),
                    signal.getModbus().getStartAddress(), signal.getModbus().getRegister(), getCount(signal.getType()),
                    signal.getMin(), signal.getMax(), signal.getModbus().getBitNumber());
        } else {
            log.error("Unrecognized modbus type {} for signal {}", signal.getModbus().getType(), signal.getName());
        }
    }

    /**
     * Updates a write signal. Needs the updated signal from config file, and the corresponding
     * command tag from the C2mon Server
     *
     * @param signal     from the config file
     * @param commandTag from the C2mon server
     */
    private void updateSignal(Signal signal, CommandTag commandTag) {
        if (log.isInfoEnabled()) {
            log.info("Updating command tag {} with id {}...", signal.getName(), commandTag.getId());
        }
        commandTag.setDataType(signal.getType());
        commandTag.setAddress(new HardwareAddress(signal.getModbus().getStartAddress(), getCount(signal.getType()),
                signal.getModbus().getRegister(), signal.getMin(), signal.getMax(), signal.getModbus().getBitNumber()));
        configService.updateCommandTag(commandTag);
    }

    /**
     * Removes the configuration for the DAQ entirely from the C2mon server
     */
    private void removeC2monConfiguration() {
        configService.removeProcessFromC2monEntirely();
    }
}
