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

package com.ben12.openhab.activity;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.ws.rs.client.InvocationCallback;

import com.ben12.openhab.controller.ContentController;
import com.ben12.openhab.controller.MainViewController;
import com.ben12.openhab.controller.impl.TopItemsController;
import com.ben12.openhab.model.Item;
import com.ben12.openhab.model.Page;
import com.ben12.openhab.plugin.HabApplicationPlugin;
import com.ben12.openhab.plugin.OpenHabRestClientPlugin;
import com.ben12.openhab.rest.OpenHabRestClient;
import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPin;
import com.pi4j.io.gpio.GpioPinPwmOutput;
import com.pi4j.io.gpio.Pin;
import com.pi4j.io.gpio.RaspiPin;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Window;

/**
 * @author Benoît Moreau (ben.12)
 */
public class ActivityHandler implements HabApplicationPlugin, OpenHabRestClientPlugin, EventHandler<Event>, Runnable
{
    private static final int         MINUTES_IDLING = 1;

    private static final int         MINUTES_IDLE   = 9;

    private static final int         IDLING         = 0;

    private static final int         IDLE           = 1;

    private static final int         PRESENT        = 2;

    private static final Pin         PIN            = RaspiPin.GPIO_01;

    private Scene                    mainScene;

    private Parent                   mainRoot;

    private Parent                   idlingRoot;

    private static ActivityHandler   instance;

    private ScheduledExecutorService executor;

    private ScheduledFuture<?>       future;

    private int                      idleState      = PRESENT;

    private OpenHabRestClient        openHabRestClient;

    private final GpioPinPwmOutput   pin;

    public ActivityHandler()
    {
        if (instance == null)
        {
            instance = this;
        }

        final GpioController controller = GpioFactory.getInstance();

        final GpioPin existingPin = controller.getProvisionedPin(PIN);
        if (existingPin == null)
        {
            pin = controller.provisionPwmOutputPin(PIN);
        }
        else if (existingPin instanceof GpioPinPwmOutput)
        {
            pin = (GpioPinPwmOutput) existingPin;
        }
        else
        {
            throw new IllegalStateException("Pin is not a GpioPinPwmOutput");
        }
    }

    @Override
    public void init(final Window window)
    {
        if (instance == this)
        {
            mainScene = window.getScene();
            mainRoot = window.getScene().getRoot();
            window.addEventFilter(Event.ANY, this);

            executor = Executors.newSingleThreadScheduledExecutor();
            future = executor.schedule(this, MINUTES_IDLING, TimeUnit.MINUTES);
        }
        else
        {
            instance.init(window);
        }
    }

    @Override
    public void init(final OpenHabRestClient restClient)
    {
        if (instance == this)
        {
            openHabRestClient = restClient;
            initIdlingView();
        }
        else
        {
            instance.init(restClient);
        }
    }

    private void initIdlingView()
    {
        final Properties configuration = new Properties();
        final String config = System.getProperty("config.file");
        if (Paths.get(config).toFile().isFile())
        {
            try (FileReader reader = new FileReader(config))
            {
                configuration.load(reader);
            }
            catch (final IOException e)
            {
                e.printStackTrace();
            }
        }

        final MainViewController mainViewController = new MainViewController()
        {
            @Override
            public OpenHabRestClient getRestClient()
            {
                return openHabRestClient;
            }

            @Override
            public Page getHomepage()
            {
                return null;
            }

            @Override
            public Region getDefaultInfosView()
            {
                return null;
            }

            @Override
            public Properties getConfig()
            {
                return configuration;
            }

            @Override
            public void display(final ContentController<?> contentController)
            {
            }
        };

        final FXMLLoader loader = new FXMLLoader();
        loader.setLocation(ActivityHandler.class.getResource("TopItems.fxml"));
        try
        {
            idlingRoot = (Parent) loader.load();
            ((TopItemsController) loader.getController()).init(null, mainViewController);

            if (((TopItemsController) loader.getController()).isEmpty())
            {
                idlingRoot = null;
            }
        }
        catch (final IOException e)
        {
            e.printStackTrace();
        }

    }

    private boolean isPresentMode()
    {
        final AtomicBoolean presentMode = new AtomicBoolean(false);

        if (openHabRestClient != null)
        {
            final CountDownLatch latch = new CountDownLatch(1);

            openHabRestClient.item("Maison_mode", new InvocationCallback<Item>()
            {
                @Override
                public void failed(final Throwable throwable)
                {
                    latch.countDown();
                }

                @Override
                public void completed(final Item response)
                {
                    presentMode.set("0".equals(response.getState()));
                    latch.countDown();
                }
            });

            try
            {
                latch.await(10, TimeUnit.SECONDS);
            }
            catch (final InterruptedException e)
            {
                e.printStackTrace();
            }
        }

        return presentMode.get();
    }

    @Override
    public void handle(final Event event)
    {
        synchronized (this)
        {
            if (idleState != PRESENT)
            {
                if (idleState == IDLE)
                {
                    if (event.getEventType() == MouseEvent.MOUSE_CLICKED && ((MouseEvent) event).isStillSincePress())
                    {
                        idleState = PRESENT;
                    }
                    event.consume();
                }
                else
                {
                    idleState = PRESENT;
                }

                if (idleState == PRESENT)
                {
                    Platform.runLater(() -> mainScene.setRoot(mainRoot));

                    pin.setPwm(500);
                }
            }

            if (idleState == PRESENT)
            {
                future.cancel(false);
                future = executor.schedule(this, MINUTES_IDLING, TimeUnit.MINUTES);
            }
        }
    }

    @Override
    public void run()
    {
        synchronized (this)
        {
            switch (idleState)
            {
            case PRESENT:
                idleState = IDLING;

                future.cancel(false);
                future = executor.schedule(this, MINUTES_IDLE, TimeUnit.MINUTES);

                if (idlingRoot != null)
                {
                    Platform.runLater(() -> mainScene.setRoot(idlingRoot));
                }

                pin.setPwm(150);
                break;

            case IDLING:
                if (!isPresentMode())
                {
                    idleState = IDLE;

                    pin.setPwm(0);
                }
                else
                {
                    future.cancel(false);
                    future = executor.schedule(this, MINUTES_IDLE, TimeUnit.MINUTES);
                }
                break;

            default:
                break;
            }
        }
    }
}
