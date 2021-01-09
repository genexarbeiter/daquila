package de.tub.sense.daq.modbus;

import com.ghgande.j2mod.modbus.msg.ReadCoilsResponse;
import com.ghgande.j2mod.modbus.msg.ReadMultipleRegistersResponse;
import de.tub.sense.daq.config.xml.HardwareAddress;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * @author maxmeyer
 * @created 14/12/2020 - 15:15
 * @project DAQConfigLoader
 */

@Slf4j
@NoArgsConstructor
public class ModbusTCPService {

    private TcpModbusSocket tcpModbusSocket;

    /**
     * Establish the connection to the ModbusTCPEndpoint. If it fails, it logs the exception.
     */
    public void connect(String host, int port, int unitId) {
        try {
            tcpModbusSocket = new TcpModbusSocket(host, port, unitId);
            tcpModbusSocket.connect();
            log.info("Connection established with modbus host {} port {} and unitId {}",
                    host, port, unitId);
        } catch (Exception e) {
            log.warn("Failed to initialize modbus tcp connection.", e);
        }
    }

    //TODO handle more holding types than just 'holding32' holding32 is most common and therefor used for development
    public Optional<Object> getValue(long dataTagId, HardwareAddress hardwareAddress, String dataType) {
        switch (hardwareAddress.getType()) {
            case "holding32":
                try {
                    return Optional.of(parseHoldingResponse(tcpModbusSocket.readHoldingRegisters(hardwareAddress.getStartAddress(),
                            hardwareAddress.getValueCount()), dataType));
                } catch (Exception e) {
                    log.warn("Could not read holding register with tagId " + dataTagId, e);
                    return Optional.empty();
                }
            case "coil":
                try {
                    return Optional.of(parseCoilResponse(tcpModbusSocket.readCoils(hardwareAddress.getStartAddress(), hardwareAddress.getValueCount())));
                } catch (Exception e) {
                    log.warn("Could not read coil register with tagName " + dataTagId, e);
                    return Optional.empty();
                }
            /*
            case "input":
                    try {
                        ReadInputRegistersResponse response = tcpModbusSocket.readInputRegisters(modbus.getStartAddress(), modbus.getCount());
                    } catch (Exception e) {
                        log.warn("Could not read input register with tagName " + tagName, e);
                    }
                case "discrete":
                    try {
                        ReadInputDiscretesResponse response = tcpModbusSocket.readDiscreteInputs(modbus.getStartAddress(), modbus.getCount());
                    } catch (Exception e) {
                        log.warn("Could not read discrete input register with tagName " + tagName, e);
                    }
                 */
            default:
                log.warn("Modbus type {} not valid.", hardwareAddress.getType());
                return Optional.empty();
        }
    }

    public void disconnect() {
        tcpModbusSocket.disconnect();
    }

    /**
     * Parse a holding register response to a object of the given data type
     *
     * @param response to parse
     * @param dataType expected from the response
     * @return object instance of the given data type parsed from the holding register response
     */
    private Object parseHoldingResponse(ReadMultipleRegistersResponse response, String dataType) {
        final Integer[] respValues = new Integer[response.getByteCount()];
        final ByteBuffer bb = ByteBuffer.allocate(2 * response.getByteCount());
        for (int i = 0; i < response.getWordCount(); i++) {
            respValues[i] = response.getRegisterValue(i);
            bb.putShort(respValues[i].shortValue());
        }
        bb.rewind();
        switch (dataType) {
            case "s8":
            case "u8":
                return bb.get();
            case "s16":
            case "u16":
                return bb.getShort();
            case "u32":
            case "s32":
                return bb.getInt();
            case "s64":
            case "java.lang.Long": //"u64" (switched to XML Config)
                return bb.getLong();
            case "float32":
                return bb.getFloat();
            case "float64":
                return bb.getDouble();
            default:
                throw new IllegalArgumentException("Datatype " + dataType + " could not be converted");
        }
    }

    /**
     * Parses a coil response to a single boolean or a boolean array
     *
     * @param response to parse
     * @return single boolean or boolean array depending on the response
     */
    private Object parseCoilResponse(@NonNull ReadCoilsResponse response) {
        if (response.getBitCount() == 1) {
            return response.getCoilStatus(0);
        } else {
            final Boolean[] respValues = new Boolean[response.getBitCount()];
            for (int i = 0; i < response.getBitCount(); i++) {
                respValues[i] = response.getCoilStatus(i);
            }
            return respValues;
        }
    }
}