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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.client.InvocationCallback;

import com.ben12.openhab.model.Item;
import com.ben12.openhab.plugin.OpenHabRestClientPlugin;
import com.ben12.openhab.rest.OpenHabRestClient;

public class DHT22SensorPlugin implements OpenHabRestClientPlugin, Runnable
{
    private static final Logger      LOGGER           = Logger.getLogger(DHT22SensorPlugin.class.getName());

    private static final String      TEMPERATURE_ITEM = "Salon_Temperature2";

    private static final String      HUMIDITY_ITEM    = "Salon_Humidity2";

    private static final int         MESURE_DELAY     = 30;

    private OpenHabRestClient        openHabRestClient;

    private ScheduledExecutorService executor;

    private DHT22                    dht22;

    private Item                     temperatureItem  = null;

    private Item                     humidityItem     = null;

    private int                      tryCount         = 0;

    @Override
    public void init(final OpenHabRestClient restClient)
    {
        openHabRestClient = restClient;

        dht22 = new DHT22();

        openHabRestClient.item(TEMPERATURE_ITEM, new InvocationCallback<Item>()
        {
            @Override
            public void failed(final Throwable throwable)
            {
                LOGGER.log(Level.SEVERE, "Unresolved item: " + TEMPERATURE_ITEM, throwable);

                temperatureItem = new Item();
                temperatureItem.setName(TEMPERATURE_ITEM);
                temperatureItem.setState("0");
            }

            @Override
            public void completed(final Item response)
            {
                temperatureItem = response;
            }
        });
        openHabRestClient.item(HUMIDITY_ITEM, new InvocationCallback<Item>()
        {
            @Override
            public void failed(final Throwable throwable)
            {
                LOGGER.log(Level.SEVERE, "Unresolved item: " + HUMIDITY_ITEM, throwable);

                humidityItem = new Item();
                humidityItem.setName(HUMIDITY_ITEM);
                humidityItem.setState("0");
            }

            @Override
            public void completed(final Item response)
            {
                humidityItem = response;
            }
        });

        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this, MESURE_DELAY, MESURE_DELAY, TimeUnit.SECONDS);
    }

    private float getState(final Item item)
    {
        float value;
        try
        {
            value = Float.parseFloat(item.getState());
        }
        catch (final Exception e)
        {
            value = 0.0F;
            LOGGER.log(Level.WARNING, "Bad item state: " + item.getName() + "=" + item.getState(), e);
        }
        return value;
    }

    private void sendMesure(final Item item, final float newMesure, final float threshold)
    {
        if (item != null)
        {
            final float diff = newMesure - getState(item);
            if (diff >= threshold || diff <= -threshold)
            {
                final String state = Float.toString((int) (newMesure * 10) / 10.0f);
                openHabRestClient.submit(item, state);
                item.setState(state);

                LOGGER.fine(() -> "Item state sent: " + item.getName() + "=" + item.getState());
            }
            else
            {
                LOGGER.fine(() -> "Item state not sent for the new mesure " + newMesure + ". Current state: "
                        + item.getName() + "=" + item.getState());
            }
        }
    }

    @Override
    public void run()
    {
        if (dht22.read() == 0)
        {
            sendMesure(temperatureItem, dht22.getTemperature(), 0.1f);
            sendMesure(humidityItem, dht22.getHumidity(), 0.5f);
        }
        else if (tryCount < 10)
        {
            tryCount++;
            run();
        }
        else
        {
            LOGGER.warning("To many try for get DHT22 sensor mesures. Retry later.");
        }

        tryCount = 0;
    }
}
