// Copyright (C) 2017 Benoît Moreau (ben.12)
//
// This file is part of MY-HABFX-UI (My openHAB javaFX User Interface).
//
// MY-HABFX-UI is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// MY-HABFX-UI is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with MY-HABFX-UI.  If not, see <http://www.gnu.org/licenses/>.

package com.ben12.openhab.sensor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPinDigitalMultipurpose;
import com.pi4j.io.gpio.Pin;
import com.pi4j.io.gpio.PinMode;
import com.pi4j.io.gpio.RaspiPin;
import com.pi4j.wiringpi.Gpio;

import javafx.application.Platform;

public class DHT22
{
    private static final Logger              LOGGER       = Logger.getLogger(DHT22.class.getName());

    private static final Pin                 PIN          = RaspiPin.GPIO_02;

    // This is the only processor specific magic value, the maximum amount of time to
    // spin in a loop before bailing out and considering the read a timeout. This should
    // be a high value, but if you're running on a much faster platform than a Raspberry
    // Pi or Beaglebone Black then it might need to be increased.
    private static final int                 DHT_MAXCOUNT = 32000;

    // Number of bit pulses to expect from the DHT. Note that this is 41 because
    // the first pulse is a constant 50 microsecond pulse, with 40 pulses to represent
    // the data afterwards.
    private static final int                 DHT_PULSES   = 41;

    private static final int                 BUFFER_SIZE  = DHT_PULSES * 2;

    private static final long                DELAY        = 500;

    private final GpioPinDigitalMultipurpose pin;

    private float                            temperature;

    private float                            humidity;

    private long                             nextMessure;

    public DHT22()
    {
        final GpioController controller = GpioFactory.getInstance();
        pin = controller.provisionDigitalMultipurposePin(PIN, PinMode.DIGITAL_OUTPUT);

        // Set pin to output.
        pin.setMode(PinMode.DIGITAL_OUTPUT);

        // Set pin high for ~500 microseconds.
        pin.high();
        nextMessure = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + DELAY;
    }

    public float getTemperature()
    {
        return temperature;
    }

    public float getHumidity()
    {
        return humidity;
    }

    private int countLowPulse()
    {
        int count = 0;
        while (pin.isLow())
        {
            if (++count >= DHT_MAXCOUNT)
            {
                // Timeout waiting for response.
                return -1;
            }
        }
        return count;
    }

    private int countHighPulse()
    {
        int count = 0;
        while (pin.isHigh())
        {
            if (++count >= DHT_MAXCOUNT)
            {
                // Timeout waiting for response.
                return -1;
            }
        }
        return count;
    }

    private int askForMesure(final AtomicInteger result)
    {
        // Set pin low for ~20 milliseconds.
        pin.low();
        Gpio.delay(20);

        // Set pin at input.
        pin.setMode(PinMode.DIGITAL_INPUT);
        Gpio.delayMicroseconds(10);

        // Wait for DHT to pull pin low.
        final int count = countHighPulse();
        if (count < 0)
        {
            // Timeout waiting for response.
            return 1;
        }
        return 0;
    }

    private int recordPulseWidths(final int[] pulseCounts)
    {
        // Record pulse widths for the expected result bits.
        for (int i = 0; i < BUFFER_SIZE; i += 2)
        {
            // Count how long pin is low and store in pulseCounts[i]
            pulseCounts[i] = countLowPulse();
            if (pulseCounts[i] < 0)
            {
                // Timeout waiting for response.
                return 2;
            }

            // Count how long pin is high and store in pulseCounts[i+1]
            pulseCounts[i + 1] = countHighPulse();
            if (pulseCounts[i + 1] < 0)
            {
                // Timeout waiting for response.
                return 3;
            }
        }
        return 0;
    }

    public synchronized int read()
    {
        final AtomicInteger result = new AtomicInteger(10);

        // Store the count that each DHT bit pulse is low and high.
        // Make sure array is initialized to start at zero.
        final int[] pulseCounts = new int[BUFFER_SIZE];

        final int[] data = new int[5];

        final Thread t = new Thread("DHT22")
        {
            @Override
            public void run()
            {
                try
                {
                    final int askResult = askForMesure(result);
                    if (askResult != 0)
                    {
                        result.set(askResult);
                        return;
                    }

                    final int recResult = recordPulseWidths(pulseCounts);
                    if (recResult != 0)
                    {
                        result.set(recResult);
                        return;
                    }

                    // Compute the average low pulse width to use as a 50 microsecond reference threshold.
                    // Ignore the first two readings because they are a constant 80 microsecond pulse.
                    long threshold = 0;
                    for (int i = 2; i < BUFFER_SIZE; i += 2)
                    {
                        threshold += pulseCounts[i];
                    }
                    threshold /= DHT_PULSES - 1;

                    // Interpret each high pulse as a 0 or 1 by comparing it to the 50us reference.
                    // If the count is less than 50us it must be a ~28us 0 pulse, and if it's higher
                    // then it must be a ~70us 1 pulse.
                    for (int i = 3; i < BUFFER_SIZE; i += 2)
                    {
                        final int index = (i - 3) / 16;
                        data[index] <<= 1;
                        if (pulseCounts[i] >= threshold)
                        {
                            // One bit for long pulse.
                            data[index] |= 1;
                        }
                        // Else zero bit for short pulse.
                    }

                    // Verify checksum of received data.
                    if (data[4] == ((data[0] + data[1] + data[2] + data[3]) & 0xFF))
                    {
                        // Calculate humidity and temp for DHT22 sensor.
                        humidity = (data[0] * 256 + data[1]) / 10.0f;
                        temperature = ((data[2] & 0x7F) * 256 + data[3]) / 10.0f;
                        if ((data[2] & 0x80) != 0)
                        {
                            temperature *= -1.0f;
                        }
                        result.set(0);
                        return;
                    }
                    else
                    {
                        result.set(4);
                        return;
                    }
                }
                catch (final Exception e)
                {
                    LOGGER.log(Level.SEVERE, "", e);
                    result.set(5);
                }
                finally
                {
                    // Set pin to output.
                    pin.setMode(PinMode.DIGITAL_OUTPUT);

                    // Set pin high for ~500 milliseconds.
                    pin.high();
                    nextMessure = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + DELAY;
                }
            }
        };

        t.setPriority(Thread.MAX_PRIORITY);

        final long remaining = nextMessure - TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        if (remaining > 0)
        {
            Gpio.delay(remaining);
        }

        try
        {
            try
            {
                if (!Platform.isFxApplicationThread())
                {
                    final CountDownLatch latch = new CountDownLatch(1);

                    // Pause UI treatments
                    Platform.runLater(() -> {
                        t.start();
                        latch.countDown();
                        try
                        {
                            t.join(2000);
                        }
                        catch (final InterruptedException e)
                        {
                            LOGGER.log(Level.SEVERE, "", e);
                        }
                    });

                    latch.await();
                }
                else
                {
                    t.start();
                }
            }
            catch (final IllegalStateException e)
            {
                // No UI
                t.start();
            }

            t.join(10000);
            if (t.isAlive())
            {
                t.interrupt();
                t.join(10000);
            }
        }
        catch (final InterruptedException e)
        {
            LOGGER.log(Level.SEVERE, "", e);
        }

        return result.get();
    }

    public static void main(final String[] args)
    {
        final DHT22 dht22 = new DHT22();

        int mesure = 0;
        int ok = 0;
        while (mesure++ < 100)
        {
            final int rd = dht22.read();
            ok += (rd == 0 ? 1 : 0);
            System.out.print("R=" + rd);
            System.out.print(", T=" + dht22.getTemperature());
            System.out.println(", H=" + dht22.getHumidity());

            Gpio.delay(1000);
        }
        System.out.println("OK=" + ok + "%");
    }
}
