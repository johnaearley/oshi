/**
 * OSHI (https://github.com/oshi/oshi)
 *
 * Copyright (c) 2010 - 2019 The OSHI Project Team:
 * https://github.com/oshi/oshi/graphs/contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package oshi.hardware.platform.mac;

import oshi.hardware.CentralProcessor;
import oshi.hardware.ComputerSystem;
import oshi.hardware.Display;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HWDiskStore;
import oshi.hardware.NetworkIF;
import oshi.hardware.PowerSource;
import oshi.hardware.Sensors;
import oshi.hardware.SoundCard;
import oshi.hardware.UsbDevice;
import oshi.hardware.common.AbstractHardwareAbstractionLayer;

/**
 * <p>
 * MacHardwareAbstractionLayer class.
 * </p>
 */
public class MacHardwareAbstractionLayer extends AbstractHardwareAbstractionLayer {

    private static final long serialVersionUID = 1L;

    /** {@inheritDoc} */
    @Override
    public ComputerSystem getComputerSystem() {
        if (this.computerSystem == null) {
            this.computerSystem = new MacComputerSystem();
        }
        return this.computerSystem;
    }

    /** {@inheritDoc} */
    @Override
    public CentralProcessor getProcessor() {
        if (this.processor == null) {
            this.processor = new MacCentralProcessor();
        }
        return this.processor;
    }

    /** {@inheritDoc} */
    @Override
    public GlobalMemory getMemory() {
        if (this.memory == null) {
            this.memory = new MacGlobalMemory();
        }
        return this.memory;
    }

    /** {@inheritDoc} */
    @Override
    public PowerSource[] getPowerSources() {
        return MacPowerSource.getPowerSources();
    }

    /** {@inheritDoc} */
    @Override
    public HWDiskStore[] getDiskStores() {
        return new MacDisks().getDisks();
    }

    /** {@inheritDoc} */
    @Override
    public Display[] getDisplays() {
        return MacDisplay.getDisplays();
    }

    /** {@inheritDoc} */
    @Override
    public Sensors getSensors() {
        if (this.sensors == null) {
            this.sensors = new MacSensors();
        }
        return this.sensors;
    }

    /** {@inheritDoc} */
    @Override
    public NetworkIF[] getNetworkIFs() {
        return new MacNetworks().getNetworks();
    }

    /** {@inheritDoc} */
    @Override
    public UsbDevice[] getUsbDevices(boolean tree) {
        return MacUsbDevice.getUsbDevices(tree);
    }

    /**
     * {@inheritDoc}
     *
     * Instantiates an array of {@link SoundCard} objects, representing the Sound
     * cards.
     */
    @Override
    public SoundCard[] getSoundCards() {
        return MacSoundCard.getSoundCards().toArray(new SoundCard[0]);
    }
}
