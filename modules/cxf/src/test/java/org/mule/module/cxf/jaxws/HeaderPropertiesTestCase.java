/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.module.cxf.jaxws;

import org.mule.api.MuleEventContext;
import org.mule.api.MuleMessage;
import org.mule.module.client.MuleClient;
import org.mule.tck.functional.EventCallback;
import org.mule.tck.functional.FunctionalTestComponent;
import org.mule.tck.junit4.FunctionalTestCase;
import org.mule.tck.junit4.rule.DynamicPort;

import java.util.HashMap;
import java.util.Map;

import org.apache.hello_world_soap_http.GreeterImpl;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class HeaderPropertiesTestCase extends FunctionalTestCase
{

    @Rule
    public DynamicPort dynamicPort = new DynamicPort("port1");

    @Override
    protected String getConfigResources()
    {
        return "header-conf.xml";
    }

    private GreeterImpl getGreeter() throws Exception
    {
        Object instance = getComponent("greeterService");

        return (GreeterImpl) instance;
    }

    @Test
    public void testClientWithMuleClient() throws Exception
    {
        FunctionalTestComponent testComponent = getFunctionalTestComponent("testService");
        assertNotNull(testComponent);

        EventCallback callback = new EventCallback()
        {
            public void eventReceived(final MuleEventContext context, final Object component) throws Exception
            {
                MuleMessage msg = context.getMessage();
                assertEquals("BAR", msg.getInboundProperty("FOO"));
            }
        };
        testComponent.setEventCallback(callback);

        MuleClient client = new MuleClient(muleContext);
        Map<String, Object> props = new HashMap<String, Object>();
        props.put("operation", "greetMe");
        props.put("FOO", "BAR");
        MuleMessage result = client.send("clientEndpoint", "Dan", props);
        assertEquals("Hello Dan Received", result.getPayload());

        GreeterImpl impl = getGreeter();
        assertEquals(1, impl.getInvocationCount());
    }
}
