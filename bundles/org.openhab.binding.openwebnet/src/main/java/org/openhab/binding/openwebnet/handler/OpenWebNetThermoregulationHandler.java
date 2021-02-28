/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.openwebnet.handler;

import static org.openhab.binding.openwebnet.OpenWebNetBindingConstants.CHANNEL_ACTIVE_MODE;
import static org.openhab.binding.openwebnet.OpenWebNetBindingConstants.CHANNEL_ALL_SET_MODE;
import static org.openhab.binding.openwebnet.OpenWebNetBindingConstants.CHANNEL_ALL_TEMP_SETPOINT;
import static org.openhab.binding.openwebnet.OpenWebNetBindingConstants.CHANNEL_HEATING_COOLING_MODE;
import static org.openhab.binding.openwebnet.OpenWebNetBindingConstants.CHANNEL_LOCAL_MODE;
import static org.openhab.binding.openwebnet.OpenWebNetBindingConstants.CHANNEL_SET_MODE;
import static org.openhab.binding.openwebnet.OpenWebNetBindingConstants.CHANNEL_TEMPERATURE;
import static org.openhab.binding.openwebnet.OpenWebNetBindingConstants.CHANNEL_TEMP_SETPOINT;
import static org.openhab.binding.openwebnet.OpenWebNetBindingConstants.CHANNEL_TEMP_TARGET;
import static org.openhab.binding.openwebnet.OpenWebNetBindingConstants.CHANNEL_THERMO_FUNCTION;
import static org.openhab.core.library.unit.SIUnits.CELSIUS;

import java.math.BigDecimal;
import java.util.Set;

import javax.measure.Unit;
import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.openwebnet.OpenWebNetBindingConstants;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.UnDefType;
import org.openwebnet4j.communication.OWNException;
import org.openwebnet4j.message.BaseOpenMessage;
import org.openwebnet4j.message.FrameException;
import org.openwebnet4j.message.MalformedFrameException;
import org.openwebnet4j.message.Thermoregulation;
import org.openwebnet4j.message.Thermoregulation.LOCAL_OFFSET;
import org.openwebnet4j.message.Thermoregulation.MODE;
import org.openwebnet4j.message.Where;
import org.openwebnet4j.message.WhereThermo;
import org.openwebnet4j.message.Who;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link OpenWebNetThermoregulationHandler} is responsible for handling commands/messages for a Thermoregulation
 * OpenWebNet device. It extends the abstract {@link OpenWebNetThingHandler}.
 *
 * @author Massimo Valla - Initial contribution
 * @author Conte Andrea - Thermoregulation (WHO=4)
 */
@NonNullByDefault
public class OpenWebNetThermoregulationHandler extends OpenWebNetThingHandler {

    private final Logger logger = LoggerFactory.getLogger(OpenWebNetThermoregulationHandler.class);

    private enum Mode {
        // TODO make it a single map and integrate it with Thermoregulation.WHAT to have automatic translation
        UNKNOWN("UNKNOWN"),
        AUTO("AUTO"),
        MANUAL("MANUAL"),
        PROTECTION("PROTECTION"),
        OFF("OFF");

        private final String mode;

        Mode(final String mode) {
            this.mode = mode;
        }

        @Override
        public String toString() {
            return mode;
        }
    }

    private enum ThermoFunction {
        UNKNOWN(-1),
        COOL(0),
        HEAT(1),
        GENERIC(3);

        private final int function;

        ThermoFunction(final int f) {
            this.function = f;
        }

        public int getValue() {
            return function;
        }
    }

    public final static Set<ThingTypeUID> SUPPORTED_THING_TYPES = OpenWebNetBindingConstants.THERMOREGULATION_SUPPORTED_THING_TYPES;

    private boolean isCentralUnit = false;
    private Mode currentSetMode = Mode.UNKNOWN;
    private Mode currentActiveMode = Mode.UNKNOWN;
    private ThermoFunction thermoFunction = ThermoFunction.UNKNOWN;
    private Thermoregulation.LOCAL_OFFSET localOffset = Thermoregulation.LOCAL_OFFSET.NORMAL;

    public OpenWebNetThermoregulationHandler(Thing thing) {
        super(thing);
        if (OpenWebNetBindingConstants.THING_TYPE_BUS_THERMO_CENTRAL_UNIT.equals(thing.getThingTypeUID())) {
            isCentralUnit = true;
        }
    }

    @Override
    public void initialize() {
        logger.debug("initialize() thing={}", thing.getUID());
        super.initialize();
    }

    @Override
    public void dispose() {
        logger.debug("dispose() thing={}", thing.getUID());
        super.dispose();
    }

    @Override
    protected void handleChannelCommand(ChannelUID channel, Command command) {
        switch (channel.getId()) {
            case CHANNEL_ALL_TEMP_SETPOINT:
            case CHANNEL_TEMP_SETPOINT:
                handleSetpointCommand(command);
                break;
            case CHANNEL_ALL_SET_MODE:
            case CHANNEL_SET_MODE:
                handleModeCommand(command);
                logger.trace("handleChannelCommand() TODO Unsupported handleModeCommand! {}", channel.getId());
                break;
            default: {
                logger.warn("handleChannelCommand() Unsupported ChannelUID {}", channel.getId());
            }
        }
    }

    @Override
    protected void requestChannelState(ChannelUID channel) {
        logger.debug("requestChannelState() thingUID={} channel={}", thing.getUID(), channel.getId());
        try {
            bridgeHandler.gateway.send(Thermoregulation.requestStatus(deviceWhere.value()));
        } catch (OWNException e) {
            logger.error("requestChannelState() OWNException thingUID={} channel={}: {}", thing.getUID(),
                    channel.getId(), e.getMessage());
        }
    }

    @Override
    protected Where buildBusWhere(String wStr) throws IllegalArgumentException {
        return new WhereThermo(wStr);
    }

    @Override
    protected String ownIdPrefix() {
        return Who.THERMOREGULATION.value().toString();
    }

    private void handleSetpointCommand(Command command) {
        logger.debug("handleSetpointCommand() (command={})", command);

        if (command instanceof QuantityType || command instanceof DecimalType) {
            BigDecimal value = BigDecimal.ZERO;
            if (command instanceof QuantityType) {
                Unit<Temperature> unit = CELSIUS;
                QuantityType<Temperature> quantity = commandToQuantityType(command, unit);
                value = quantity.toBigDecimal();
            } else {
                value = ((DecimalType) command).toBigDecimal();
            }

            try {
                Thermoregulation mm = Thermoregulation.requestWriteSetpointTemperature(deviceWhere.value(),
                        value.floatValue(), MODE.GENERIC);

                bridgeHandler.gateway.send(mm);
            } catch (MalformedFrameException | OWNException e) {
                logger.warn("handleSetpointCommand() {}", e.getMessage());
            }
        } else {
            logger.warn("handleSetpointCommand() Cannot handle command {} for thing {}", command, getThing().getUID());
        }
    }

    private void handleModeCommand(Command command) {
        logger.debug("handleModeCommand() (command={})", command);
        if (command instanceof StringType) {
            Thermoregulation.WHAT modeWhat = null;
            try {
                Mode mode = Mode.valueOf(((StringType) command).toString());
                modeWhat = modeToWhat(mode);
            } catch (IllegalArgumentException e) {
                logger.warn("Cannot handle command {} for thing {}. Exception: {}", command, getThing().getUID(),
                        e.getMessage());
                return;
            }
            logger.debug("handleModeCommand() modeWhat={}", modeWhat);
            if (modeWhat != null) {
                try {
                    bridgeHandler.gateway.send(Thermoregulation.requestWriteSetMode(deviceWhere.value(), modeWhat));
                } catch (MalformedFrameException | OWNException e) {
                    logger.warn("handleModeCommand() {}", e.getMessage());
                }
            } else {
                logger.warn("Cannot handle command {} for thing {}", command, getThing().getUID());
            }
        } else {
            logger.warn("Cannot handle command {} for thing {}", command, getThing().getUID());
        }
    }

    private QuantityType<Temperature> commandToQuantityType(Command command, Unit<Temperature> unit) {
        return new QuantityType<Temperature>(command.toFullString());
    }

    @Override
    protected void handleMessage(BaseOpenMessage msg) {
        super.handleMessage(msg);
        if (msg.isCommand()) {
            updateMode((Thermoregulation) msg);
        } else {
            if (msg.getDim() == Thermoregulation.DIM.TEMPERATURE
                    || msg.getDim() == Thermoregulation.DIM.PROBE_TEMPERATURE) {
                updateTemperature((Thermoregulation) msg);
            } else if (msg.getDim() == Thermoregulation.DIM.TEMP_SETPOINT) {
                updateSetpoint((Thermoregulation) msg);
            } else if (msg.getDim() == Thermoregulation.DIM.OFFSET) {
                updateLocalMode((Thermoregulation) msg);
            } else if (msg.getDim() == Thermoregulation.DIM.ACTUATOR_STATUS) {
                updateActuatorStatus((Thermoregulation) msg);
            } else if (msg.getDim() == Thermoregulation.DIM.TEMP_TARGET) {
                updateTargetTemp((Thermoregulation) msg);
            } else {
                logger.debug("handleMessage() Ignoring unsupported DIM for thing {}. Frame={}", getThing().getUID(),
                        msg);
            }
        }
    }

    private void updateMode(Thermoregulation tmsg) {
        logger.debug("updateMode() for thing: {} msg={}", thing.getUID(), tmsg);
        Thermoregulation.WHAT w = (Thermoregulation.WHAT) tmsg.getWhat();
        Mode newMode = whatToMode(w);
        if (newMode != null) {
            // TODO
            // if (tmsg..isFromCentralUnit()) {
            // updateSetMode(newMode);
            // }
            // else {
            updateActiveMode(newMode);
            // }
        } else {
            logger.debug("updateMode() mode not processed: msg={}", tmsg);
        }
        updateThermoFunction(w);
        updateHeatingCoolingMode();
    }

    private void updateThermoFunction(Thermoregulation.WHAT what) {
        logger.debug("updateThermoFunction() for thing: {}", thing.getUID());

        ThermoFunction newFunction = ThermoFunction.UNKNOWN;
        switch (what) {
            case CONDITIONING:
            case PROGRAM_CONDITIONING:
            case MANUAL_CONDITIONING:
            case PROTECTION_CONDITIONING:
            case OFF_CONDITIONING:
            case HOLIDAY_CONDITIONING:
                newFunction = ThermoFunction.COOL;
                break;
            case HEATING:
            case PROGRAM_HEATING:
            case MANUAL_HEATING:
            case PROTECTION_HEATING:
            case OFF_HEATING:
            case HOLIDAY_HEATING:
                newFunction = ThermoFunction.HEAT;
                break;
            case GENERIC:
            case PROGRAM_GENERIC:
            case MANUAL_GENERIC:
            case PROTECTION_GENERIC:
            case OFF_GENERIC:
            case HOLIDAY_GENERIC:
                newFunction = ThermoFunction.GENERIC;
                break;
        }
        if (thermoFunction != newFunction) {
            thermoFunction = newFunction;
            updateState(CHANNEL_THERMO_FUNCTION, new StringType(thermoFunction.toString()));
        }
    }

    private void updateHeatingCoolingMode() {
        logger.debug("updateHeatingCoolingMode() for thing: {}", thing.getUID());
        if (currentActiveMode == Mode.OFF) {
            updateState(CHANNEL_HEATING_COOLING_MODE, new StringType("off"));
        } else {
            switch (thermoFunction) {
                case HEAT:
                    updateState(CHANNEL_HEATING_COOLING_MODE, new StringType("heat"));
                    break;
                case COOL:
                    updateState(CHANNEL_HEATING_COOLING_MODE, new StringType("cool"));
                    break;
                case GENERIC:
                    updateState(CHANNEL_HEATING_COOLING_MODE, new StringType("heatcool"));
                    break;
                case UNKNOWN:
                default:
                    updateState(CHANNEL_HEATING_COOLING_MODE, UnDefType.NULL);
                    break;
            }
        }
    }

    private void updateSetMode(Mode mode) {
        logger.debug("updateSetMode() for thing: {}", thing.getUID());
        if (currentSetMode != mode) {
            currentSetMode = mode;
            String channelID;
            if (isCentralUnit) {
                channelID = CHANNEL_ALL_SET_MODE;
            } else {
                channelID = CHANNEL_SET_MODE;
            }
            updateState(channelID, new StringType(currentSetMode.toString()));
        }
    }

    private void updateActiveMode(Mode mode) {
        logger.debug("updateActiveMode() for thing: {}", thing.getUID());
        if (currentActiveMode != mode) {
            currentActiveMode = mode;
            updateState(CHANNEL_ACTIVE_MODE, new StringType(currentActiveMode.toString()));
        }
    }

    private void updateTemperature(Thermoregulation tmsg) {
        logger.debug("updateTemperature() for thing: {}", thing.getUID());
        Double temp;
        try {
            temp = Thermoregulation.parseTemperature(tmsg);
            updateState(CHANNEL_TEMPERATURE, new DecimalType(temp));
        } catch (FrameException e) {
            logger.warn("updateTemperature() FrameException on frame {}: {}", tmsg, e.getMessage());
            updateState(CHANNEL_TEMPERATURE, UnDefType.UNDEF);
        }
    }

    private void updateSetpoint(Thermoregulation tmsg) {
        logger.debug("updateSetpoint() for thing: {}", thing.getUID());
        String channelID;
        if (isCentralUnit) {
            channelID = CHANNEL_ALL_TEMP_SETPOINT;
        } else {
            channelID = CHANNEL_TEMP_SETPOINT;
        }
        Double temp;
        try {
            temp = Thermoregulation.parseTemperature(tmsg);
            updateState(channelID, new DecimalType(temp));
        } catch (FrameException e) {
            logger.warn("updateSetpoint() FrameException on frame {}: {}", tmsg, e.getMessage());
            updateState(channelID, UnDefType.UNDEF);
        }
    }

    private void updateLocalMode(Thermoregulation msg) {
        logger.debug("updateLocalMode() for thing: {}", thing.getUID());
        LOCAL_OFFSET newOffset;
        try {
            newOffset = msg.getLocalOffset();
            if (newOffset != null) {
                localOffset = newOffset;
                logger.debug("updateLocalMode() new localMode={}", localOffset);
                updateState(CHANNEL_LOCAL_MODE, new StringType(localOffset.getLabel()));
            } else {
                logger.warn("updateLocalMode() unrecognized local offset: {}", msg);
            }
        } catch (FrameException e) {
            logger.warn("updateSetpoint() FrameException on frame {}: {}", msg, e.getMessage());
        }
    }

    private void updateActuatorStatus(Thermoregulation msg) {
        logger.debug("updateActuatorStatus() for thing: {}", thing.getUID());
        // int actuator = msg.getActuator();
        // if (actuator == 1) {
        // updateState(CHANNEL_HEATING,
        // (msg.getActuatorStatus(actuator) == Thermoregulation.ACTUATOR_STATUS_ON ? OnOffType.ON
        // : OnOffType.OFF));
        // } else if (actuator == 2) {
        // updateState(CHANNEL_COOLING,
        // (msg.getActuatorStatus(actuator) == Thermoregulation.ACTUATOR_STATUS_ON ? OnOffType.ON
        // : OnOffType.OFF));
        // } else {
        // logger.warn("==OWN:ThermoHandler== actuator number {} is not handled for thing: {}", actuator,
        // thing.getUID());
        // }
    }

    private void updateTargetTemp(Thermoregulation tmsg) {
        logger.debug("updateTargetTemp() for thing: {}", thing.getUID());
        Double temp;
        try {
            temp = Thermoregulation.parseTemperature(tmsg);
            updateState(CHANNEL_TEMP_TARGET, new DecimalType(temp));
        } catch (FrameException e) {
            logger.warn("updateTargetTemp() FrameException on frame {}: {}", tmsg, e.getMessage());
            updateState(CHANNEL_TEMP_TARGET, UnDefType.UNDEF);
        }
    }

    private static Mode whatToMode(Thermoregulation.WHAT w) {
        Mode m = Mode.UNKNOWN;
        switch (w) {
            case PROGRAM_HEATING:
            case PROGRAM_CONDITIONING:
            case PROGRAM_GENERIC:
                m = Mode.AUTO;
                break;
            case MANUAL_HEATING:
            case MANUAL_CONDITIONING:
            case MANUAL_GENERIC:
                m = Mode.MANUAL;
                break;
            case PROTECTION_HEATING:
            case PROTECTION_CONDITIONING:
            case PROTECTION_GENERIC:
                m = Mode.PROTECTION;
                break;
            case OFF_HEATING:
            case OFF_CONDITIONING:
            case OFF_GENERIC:
                m = Mode.OFF;
                break;
            case CONDITIONING:
                break;
            case GENERIC:
                break;
            case HEATING:
                break;
            case HOLIDAY_CONDITIONING:
            case HOLIDAY_GENERIC:
            case HOLIDAY_HEATING:
            default:
                break;
        }
        return m;
    }

    private Thermoregulation.WHAT modeToWhat(Mode m) {
        Thermoregulation.WHAT newWhat = Thermoregulation.WHAT.GENERIC;
        switch (m) {
            case AUTO:
                if (thermoFunction == ThermoFunction.GENERIC) {
                    newWhat = Thermoregulation.WHAT.PROGRAM_GENERIC;
                } else if (thermoFunction == ThermoFunction.COOL) {
                    newWhat = Thermoregulation.WHAT.PROGRAM_CONDITIONING;
                } else {
                    newWhat = Thermoregulation.WHAT.PROGRAM_HEATING;
                }
                break;
            case MANUAL:
                if (thermoFunction == ThermoFunction.GENERIC) {
                    newWhat = Thermoregulation.WHAT.MANUAL_GENERIC;
                } else if (thermoFunction == ThermoFunction.COOL) {
                    newWhat = Thermoregulation.WHAT.MANUAL_CONDITIONING;
                } else {
                    newWhat = Thermoregulation.WHAT.MANUAL_HEATING;
                }
                break;
            case PROTECTION:
                if (thermoFunction == ThermoFunction.GENERIC) {
                    newWhat = Thermoregulation.WHAT.PROTECTION_GENERIC;
                } else if (thermoFunction == ThermoFunction.COOL) {
                    newWhat = Thermoregulation.WHAT.PROTECTION_CONDITIONING;
                } else {
                    newWhat = Thermoregulation.WHAT.PROTECTION_HEATING;
                }
                break;
            case OFF:
                if (thermoFunction == ThermoFunction.GENERIC) {
                    newWhat = Thermoregulation.WHAT.OFF_GENERIC;
                } else if (thermoFunction == ThermoFunction.COOL) {
                    newWhat = Thermoregulation.WHAT.OFF_CONDITIONING;
                } else {
                    newWhat = Thermoregulation.WHAT.OFF_HEATING;
                }
                break;
        }
        return newWhat;
    }

    // @Override
    // public void thingUpdated(Thing thing) {
    // super.thingUpdated(thing);
    // logger.debug("thingUpdated()");
    // }
} /* class */
