//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.session.DefaultSessionCache;
import org.eclipse.jetty.server.session.FileSessionDataStore;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.Configurations;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.jupiter.api.Disabled;

@Disabled("Not a test case")
public class TestServer
{
    private static final Logger LOG = Log.getLogger(TestServer.class);

    public static void main(String[] args) throws Exception
    {
        // TODO don't depend on this file structure
        Path jettyRoot = FileSystems.getDefault().getPath(".").toAbsolutePath().normalize();
        if (!Files.exists(jettyRoot.resolve("VERSION.txt")))
            jettyRoot = FileSystems.getDefault().getPath("../../..").toAbsolutePath().normalize();
        if (!Files.exists(jettyRoot.resolve("VERSION.txt")))
            throw new IllegalArgumentException(jettyRoot.toString());

        // Setup Threadpool
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(100);

        // Setup server
        Server server = new Server(threadPool);
        Configurations.setServerDefault(server);
        server.manage(threadPool);

        // Setup JMX
        MBeanContainer mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbContainer);
        server.addBean(Log.getLog());

        // Common HTTP configuration
        HttpConfiguration config = new HttpConfiguration();
        config.setSecurePort(8443);
        config.addCustomizer(new ForwardedRequestCustomizer());
        config.addCustomizer(new SecureRequestCustomizer());
        config.setSendDateHeader(true);
        config.setSendServerVersion(true);

        // Http Connector
        HttpConnectionFactory http = new HttpConnectionFactory(config);
        ServerConnector httpConnector = new ServerConnector(server, http);
        httpConnector.setPort(8080);
        httpConnector.setIdleTimeout(30000);
        server.addConnector(httpConnector);

        // Handlers
        HandlerCollection handlers = new HandlerCollection();
        ContextHandlerCollection contexts = new ContextHandlerCollection();
        handlers.setHandlers(new Handler[]
            {contexts, new DefaultHandler()});

        // Add restart handler to test the ability to save sessions and restart
        RestartHandler restart = new RestartHandler();
        restart.setHandler(handlers);
        server.setHandler(restart);

        // Setup context
        HashLoginService login = new HashLoginService();
        login.setName("Test Realm");
        login.setConfig(jettyRoot.resolve("tests/test-webapps/test-jetty-webapp/src/main/config/demo-base/etc/realm.properties").toString());
        server.addBean(login);

        File log = File.createTempFile("jetty-yyyy_mm_dd", "log");
        CustomRequestLog requestLog = new CustomRequestLog(log.toString());
        server.setRequestLog(requestLog);

        server.setStopAtShutdown(true);

        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/test");
        webapp.setParentLoaderPriority(true);
        webapp.setResourceBase(jettyRoot.resolve("tests/test-webapps/test-jetty-webapp/src/main/webapp").toString());
        webapp.setAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN,
            ".*/test-jetty-webapp/target/classes.*$|" +
                ".*/jetty-servlet-api-[^/]*\\.jar$|.*/javax.servlet.jsp.jstl-.*\\.jar$|.*/org.apache.taglibs.taglibs-standard-impl-.*\\.jar$"
        );

        webapp.setAttribute("testAttribute", "testValue");
        File sessiondir = File.createTempFile("sessions", null);
        if (sessiondir.exists())
            sessiondir.delete();
        sessiondir.mkdir();
        sessiondir.deleteOnExit();
        DefaultSessionCache ss = new DefaultSessionCache(webapp.getSessionHandler());
        FileSessionDataStore sds = new FileSessionDataStore();
        ss.setSessionDataStore(sds);
        sds.setStoreDir(sessiondir);
        webapp.getSessionHandler().setSessionCache(ss);

        contexts.addHandler(webapp);

        ContextHandler srcroot = new ContextHandler();
        srcroot.setResourceBase(jettyRoot.resolve("tests/test-webapps/test-jetty-webapp/src").toString());
        srcroot.setHandler(new ResourceHandler());
        srcroot.setContextPath("/src");
        contexts.addHandler(srcroot);

        server.start();
        server.dumpStdErr();

        server.join();
    }

    private static class RestartHandler extends HandlerWrapper
    {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            super.handle(target, baseRequest, request, response);
            if (Boolean.valueOf(request.getParameter("restart")))
            {
                final Server server = getServer();

                new Thread()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            Thread.sleep(100);
                            server.stop();
                            Thread.sleep(100);
                            server.start();
                        }
                        catch (Exception e)
                        {
                            LOG.warn(e);
                        }
                    }
                }.start();
            }
        }
    }
}
