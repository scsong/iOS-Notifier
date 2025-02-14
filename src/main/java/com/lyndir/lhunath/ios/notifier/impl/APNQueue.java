/*
 *   Copyright 2009, Maarten Billemont
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.lyndir.lhunath.ios.notifier.impl;

import com.lyndir.lhunath.opal.system.logging.Logger;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;


/**
 * <h2>{@link APNQueue}<br> <sub>A queue which manages dispatching of notifications to the APNs.</sub></h2>
 * <p/>
 * <p> This queue collects notifications and dispatches them to the Apple Push Notification server using an {@link APNClient}. It is
 * designed to gather multiple notifications and dispatch them on the same connection to the APNs. </p>
 * <p/>
 * <p> As soon as a notification arrives, a connection to the APNs is established by the {@link APNClient}. The {@link APNQueue} then waits
 * for more notifications to arrive and dispatches each over this existing connection. After a configurable timeout (which defaults to
 * {@value #DEFAULT_TIMEOUT} milliseconds) of not receiving any additional notifications, the {@link APNQueue} shuts down {@link APNClient}
 * 's connection to the APNs and waits for more notifications in silence. When more arrive, this cycle begins anew. </p>
 * <p/>
 * <p> <i>Jun 30, 2009</i> </p>
 *
 * @author lhunath
 */
public class APNQueue extends LinkedBlockingQueue<ByteBuffer> implements Runnable {

    private static final long serialVersionUID = 1L;

    private static final Logger logger = Logger.get( APNQueue.class );

    protected static final long DEFAULT_TIMEOUT = 10 * 60 * 1000 /* By default, wait 10 min before closing the APNs link. */;

    private final APNClient apnClient;

    private long timeout = DEFAULT_TIMEOUT;
    private boolean running;
    private Thread  apnQueueThread;

    /**
     * Create a new {@link APNQueue} instance.
     *
     * @param apnClient The Apple Push Notification client interface for sending the queued notifications with.
     */
    public APNQueue(final APNClient apnClient) {

        this.apnClient = apnClient;
    }

    /**
     * @return The amount of milliseconds after which the connection to the APNs is shut down.
     */
    public long getTimeout() {

        return timeout;
    }

    /**
     * @param timeout The amount of milliseconds after which the connection to the APNs is shut down.
     */
    public void setTimeout(final long timeout) {

        this.timeout = timeout;
    }

    @Override
    public void run() {

        synchronized (this) {
            running = true;
        }
        Thread.currentThread().setName( "APN Dispatch Queue" );
        logger.inf( "APNQueue is running." );

        while (true)
            try {
                synchronized (this) {
                    if (!running)
                        break;
                }

                ByteBuffer dataBuffer = take();
                apnClient.dispatch( dataBuffer );

                while ((dataBuffer = poll( timeout, TimeUnit.MILLISECONDS )) != null)
                    apnClient.dispatch( dataBuffer );

                // No new notifications have been received in a period of 'timeout' ms; shut down the connection.
                logger.inf( "APNQueue idle; disconnecting from APNs." );
                apnClient.closeAPNs();
            }

            catch (InterruptedException e) {
                logger.wrn( e, "Operation was interrupted." );
            }
            catch (IOException e) {
                logger.wrn( e, "Network error while dispatching notifications." );
            }
            catch (KeyManagementException e) {
                logger.err( e, "Could not initialize transport security: keys unavailable?" );
            }
            catch (NoSuchAlgorithmException e) {
                logger.bug( e, "Could not initialize transport security: keys algorithms unsupported?" );
            }
            catch (Throwable t) {
                logger.err( t, "Caught unexpected throwable to save the APN queue thread." );
            }

        logger.inf( "APNQueue has been shut down." );
    }

    /**
     * Start the Apple Push Notification Queue so that it will start processing queued buffers and dispatching them to the {@link
     * APNClient}
     * for submission.
     */
    public synchronized void start() {

        if (apnQueueThread != null && apnQueueThread.isAlive()) {
            logger.wrn( "Tried to start an already running APNQueue." );
            return;
        }

        apnQueueThread = new Thread( this );
        apnQueueThread.start();
    }

    /**
     * Stop the Apple Push Notification Queue. This will cancel any pending notifications and shut down the queue processing thread.
     */
    public synchronized void stop() {

        running = false;
        apnQueueThread.interrupt();
    }
}
