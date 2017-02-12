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

import javax.ws.rs.client.InvocationCallback;

import com.ben12.openhab.model.Item;
import com.ben12.openhab.plugin.OpenHabRestClientPlugin;
import com.ben12.openhab.rest.OpenHabRestClient;

public class DHT22SensorPlugin implements OpenHabRestClientPlugin, Runnable
{
	private static final String			TEMPERATURE_ITEM	= "Salon_Temperature2";

	private static final String			HUMIDITY_ITEM		= "Salon_Humidity2";

	private static final int			MESURE_DELAY		= 10;

	private OpenHabRestClient			openHabRestClient;

	private ScheduledExecutorService	executor;

	private DHT22						dht22;

	private Item						temperatureItem		= null;

	private Item						humidityItem		= null;

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
				throwable.printStackTrace();
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
				throwable.printStackTrace();
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

	private void sendMesure(final Item item, final float newMesure, final float threshold)
	{
		if (item != null)
		{
			final float diff = newMesure - Float.parseFloat(item.getState());
			if (diff >= threshold || diff <= -threshold)
			{
				final String state = Float.toString((int) (newMesure * 10) / 10.0f);
				openHabRestClient.submit(item, state);
				item.setState(state);
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
		else
		{
			run();
		}
	}
}
