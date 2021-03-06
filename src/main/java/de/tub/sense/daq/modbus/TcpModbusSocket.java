package de.tub.sense.daq.modbus;

import com.ghgande.j2mod.modbus.ModbusIOException;
import com.ghgande.j2mod.modbus.io.ModbusTransaction;
import com.ghgande.j2mod.modbus.msg.ModbusRequest;
import com.ghgande.j2mod.modbus.msg.ModbusResponse;
import com.ghgande.j2mod.modbus.net.TCPMasterConnection;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class TcpModbusSocket extends ModbusFunctionFactory {

    private final TCPMasterConnection oConnection;

    private final int oUnitID;

    public TcpModbusSocket(String host) throws UnknownHostException {
        this(host, 0);
    }

    public TcpModbusSocket(String host, int unitID) throws UnknownHostException {
        this(host, 502, unitID);
    }

    public TcpModbusSocket(String host, int port, int unitID) throws UnknownHostException {
        this.oConnection = new TCPMasterConnection(InetAddress.getByName(host));
        getConnection().setPort(port);
        this.oUnitID = unitID;
    }

    /**
     * Establish modbus connection
     *
     * @return true if connection success false if not
     * @throws Exception if something goes wrong while connecting
     */
    public boolean connect() throws Exception {
        if (getConnection().isConnected())
            return true;
        getConnection().connect();
        return getConnection().isConnected();
    }

    /**
     * Disconnect gently from ModbusTCP
     */
    public void disconnect() {
        getConnection().close();
    }

    /**
     * Execute a modbus request
     *
     * @param request you want to execute
     * @return response of the request
     * @throws Exception if something goes wrong while executing
     */
    protected synchronized ModbusResponse execute(ModbusRequest request) throws Exception {
        if (!connect())
            throw new ModbusIOException(
                    "Connection failed [" + getConnection().getAddress().toString() + ":" + getConnection().getPort() +
                            "]");
        ModbusTransaction trans = getConnection().getModbusTransport().createTransaction();
        if (this.oUnitID > 0) request.setUnitID(this.oUnitID);
        trans.setRequest(request);
        trans.execute();
        return trans.getResponse();
    }

    /**
     * Get the currently active connection
     *
     * @return the connection object
     */
    protected TCPMasterConnection getConnection() {
        return this.oConnection;
    }
}
