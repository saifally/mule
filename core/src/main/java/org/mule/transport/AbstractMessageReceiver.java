/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.transport;

import org.mule.DefaultMuleEvent;
import org.mule.OptimizedRequestContext;
import org.mule.ResponseOutputStream;
import org.mule.VoidMuleEvent;
import org.mule.api.DefaultMuleException;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.MuleSession;
import org.mule.api.config.MuleProperties;
import org.mule.api.construct.FlowConstruct;
import org.mule.api.context.WorkManager;
import org.mule.api.endpoint.EndpointURI;
import org.mule.api.endpoint.InboundEndpoint;
import org.mule.api.execution.ExecutionTemplate;
import org.mule.api.lifecycle.CreateException;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.api.lifecycle.Startable;
import org.mule.api.processor.MessageProcessor;
import org.mule.api.routing.filter.FilterUnacceptedException;
import org.mule.api.transaction.Transaction;
import org.mule.api.transformer.Transformer;
import org.mule.api.transport.Connector;
import org.mule.api.transport.MessageReceiver;
import org.mule.api.transport.PropertyScope;
import org.mule.api.transport.ReplyToHandler;
import org.mule.context.notification.EndpointMessageNotification;
import org.mule.execution.TransactionalErrorHandlingExecutionTemplate;
import org.mule.lifecycle.PrimaryNodeLifecycleNotificationListener;
import org.mule.message.processing.MessageProcessContext;
import org.mule.message.processing.MessageProcessTemplate;
import org.mule.message.processing.MessageProcessingManager;
import org.mule.session.DefaultMuleSession;
import org.mule.session.LegacySessionHandler;
import org.mule.transaction.TransactionCoordination;
import org.mule.util.ClassUtils;
import org.mule.util.ObjectUtils;

import java.io.OutputStream;
import java.util.List;

import org.apache.commons.lang.SerializationException;

/**
 * <code>AbstractMessageReceiver</code> provides common methods for all Message
 * Receivers provided with Mule. A message receiver enables an endpoint to receive a
 * message from an external system.
 */
public abstract class AbstractMessageReceiver extends AbstractTransportMessageHandler implements MessageReceiver
{
    /**
     * The Service with which this receiver is associated with
     */
    protected FlowConstruct flowConstruct;

    /**
     * {@link MessageProcessor} chain used to process messages once the transport
     * specific {@link MessageReceiver} has received transport message and created
     * the {@link MuleEvent}
     */
    protected MessageProcessor listener;

    /**
     * Stores the key to this receiver, as used by the Connector to store the
     * receiver.
     */
    protected String receiverKey = null;

    /**
     * Stores the endpointUri that this receiver listens on. This enpoint can be
     * different to the endpointUri in the endpoint stored on the receiver as
     * endpoint endpointUri may get rewritten if this endpointUri is a wildcard
     * endpointUri such as jms.*
     */
    private EndpointURI endpointUri;

    protected List<Transformer> defaultInboundTransformers;
    protected List<Transformer> defaultResponseTransformers;

    protected ReplyToHandler replyToHandler;
    private PrimaryNodeLifecycleNotificationListener primaryNodeLifecycleNotificationListener;
    private MessageProcessingManager messageProcessingManager;

    /**
     * Creates the Message Receiver
     *
     * @param connector the endpoint that created this listener
     * @param flowConstruct the flow construct to associate with the receiver.
     * @param endpoint the provider contains the endpointUri on which the receiver
     *            will listen on. The endpointUri can be anything and is specific to
     *            the receiver implementation i.e. an email address, a directory, a
     *            jms destination or port address.
     * @see FlowConstruct
     * @see InboundEndpoint
     */
    public AbstractMessageReceiver(Connector connector, FlowConstruct flowConstruct, InboundEndpoint endpoint)
        throws CreateException
    {
        super(endpoint);

        if (flowConstruct == null)
        {
            throw new IllegalArgumentException("FlowConstruct cannot be null");
        }
        this.flowConstruct = flowConstruct;
    }

    @Override
    protected ConnectableLifecycleManager createLifecycleManager()
    {
        return new ConnectableLifecycleManager<MessageReceiver>(getReceiverKey(), this);
    }

    /**
     * Method used to perform any initialisation work. If a fatal error occurs during
     * initialisation an <code>InitialisationException</code> should be thrown,
     * causing the Mule instance to shutdown. If the error is recoverable, say by
     * retrying to connect, a <code>RecoverableException</code> should be thrown.
     * There is no guarantee that by throwing a Recoverable exception that the Mule
     * instance will not shut down.
     *
     * @throws org.mule.api.lifecycle.InitialisationException if a fatal error occurs
     *             causing the Mule instance to shutdown
     * @throws org.mule.api.lifecycle.RecoverableException if an error occurs that
     *             can be recovered from
     */
    @Override
    public final void initialise() throws InitialisationException
    {
        endpointUri = endpoint.getEndpointURI();

        defaultInboundTransformers = connector.getDefaultInboundTransformers(endpoint);
        defaultResponseTransformers = connector.getDefaultResponseTransformers(endpoint);

        replyToHandler = getReplyToHandler();

        if (!shouldConsumeInEveryNode() && !flowConstruct.getMuleContext().isPrimaryPollingInstance())
        {
            primaryNodeLifecycleNotificationListener = new PrimaryNodeLifecycleNotificationListener(new Startable()
            {
                @Override
                public void start() throws MuleException
                {
                    if (AbstractMessageReceiver.this.isStarted())
                    {
                        try
                        {
                            AbstractMessageReceiver.this.doConnect();
                        }
                        catch (Exception e)
                        {
                            throw new DefaultMuleException(e);
                        }
                        AbstractMessageReceiver.this.doStart();
                    }
                }
            },flowConstruct.getMuleContext());
            primaryNodeLifecycleNotificationListener.register();
        }

        messageProcessingManager = getConnector().getMuleContext().getRegistry().get(MuleProperties.OBJECT_DEFAULT_MESSAGE_PROCESSING_MANAGER);

        super.initialise();
    }

    public FlowConstruct getFlowConstruct()
    {
        return flowConstruct;
    }

    @Override
    public final MuleEvent routeMessage(MuleMessage message) throws MuleException
    {
        Transaction tx = TransactionCoordination.getInstance().getTransaction();
        return routeMessage(message, tx, null);
    }

    @Override
    public final MuleEvent routeMessage(MuleMessage message, Transaction trans) throws MuleException
    {
        return routeMessage(message, trans, null);
    }

    @Override
    public final MuleEvent routeMessage(MuleMessage message, Transaction trans, OutputStream outputStream)
        throws MuleException
    {
        return routeMessage(message, new DefaultMuleSession(), trans, outputStream);
    }

    public final MuleEvent routeMessage(MuleMessage message,
                                        MuleSession session,
                                        Transaction trans,
                                        OutputStream outputStream) throws MuleException
    {
        return routeMessage(message, session, outputStream);
    }

    public final MuleEvent routeMessage(MuleMessage message, MuleSession session, OutputStream outputStream)
        throws MuleException
    {

        warnIfMuleClientSendUsed(message);
        
        propagateRootMessageIdProperty(message);
        
        MuleEvent muleEvent = createMuleEvent(message, outputStream);

        if (!endpoint.isDisableTransportTransformer())
        {
            applyInboundTransformers(muleEvent);
        }

        return routeEvent(muleEvent);
    }

    protected void propagateRootMessageIdProperty(MuleMessage message)
    {
        String rootId = message.getInboundProperty(MuleProperties.MULE_ROOT_MESSAGE_ID_PROPERTY);
        if (rootId != null)
        {
            message.setMessageRootId(rootId);
            message.removeProperty(MuleProperties.MULE_ROOT_MESSAGE_ID_PROPERTY, PropertyScope.INBOUND);
        }
    }

    protected void warnIfMuleClientSendUsed(MuleMessage message)
    {
        final Object remoteSyncProperty = message.removeProperty(MuleProperties.MULE_REMOTE_SYNC_PROPERTY,
            PropertyScope.INBOUND);
        if (ObjectUtils.getBoolean(remoteSyncProperty, false) && !endpoint.getExchangePattern().hasResponse())
        {
            logger.warn("MuleClient.send() was used but inbound endpoint "
                        + endpoint.getEndpointURI().getUri().toString()
                        + " is not 'request-response'.  No response will be returned.");
        }

        message.removeProperty(MuleProperties.MULE_REMOTE_SYNC_PROPERTY, PropertyScope.INBOUND);
    }

    protected void applyInboundTransformers(MuleEvent event) throws MuleException
    {
        event.getMessage().applyTransformers(event, defaultInboundTransformers);
    }

    protected void applyResponseTransformers(MuleEvent event) throws MuleException
    {
        event.getMessage().applyTransformers(event, defaultResponseTransformers);
    }

    protected MuleMessage handleUnacceptedFilter(MuleMessage message)
    {
        if (logger.isDebugEnabled())
        {
            String messageId;
            messageId = message.getUniqueId();
            logger.debug("Message " + messageId + " failed to pass filter on endpoint: " + endpoint
                         + ". Message is being ignored");
        }
        return message;
    }

    protected MuleEvent createMuleEvent(MuleMessage message, OutputStream outputStream)
        throws MuleException
    {
        MuleEvent event;
        ResponseOutputStream ros = null;
        if (outputStream != null)
        {
            if (outputStream instanceof ResponseOutputStream)
            {
                ros = (ResponseOutputStream) outputStream;
            }
            else
            {
                ros = new ResponseOutputStream(outputStream);
            }
        }
        MuleSession session;
        try
        {
            session = connector.getSessionHandler().retrieveSessionInfoFromMessage(message);
        }
        catch (SerializationException se)
        {
            try
            {
                // EE-1820 Support message headers generated by previous Mule versions
                session = new LegacySessionHandler().retrieveSessionInfoFromMessage(message);
            }
            catch (Exception e)
            {
                // If the LegacySessionHandler didn't work either, just bubble up the original SerializationException (see MULE-5487)  
                throw se;
            }
        }
        if (session == null)
        {
            session = new DefaultMuleSession();
        }
        if (message.getReplyTo() != null)
        {
            event = new DefaultMuleEvent(message, getEndpoint(), flowConstruct, session, replyToHandler, ros);
        }
        else
        {
            event = new DefaultMuleEvent(message, getEndpoint(), flowConstruct, session, null, ros);
        }
        event = OptimizedRequestContext.unsafeSetEvent(event);
        if (session.getSecurityContext() != null && session.getSecurityContext().getAuthentication() != null)
        {
            session.getSecurityContext().getAuthentication().setEvent(event);
        }
        return event;
    }

    public EndpointURI getEndpointURI()
    {
        return endpointUri;
    }

    @Override
    public String getConnectionDescription()
    {
        return endpoint.getEndpointURI().toString();
    }

    protected String getConnectEventId()
    {
        return connector.getName() + ".receiver (" + endpoint.getEndpointURI() + ")";
    }

    // TODO MULE-4871 Receiver key should not be mutable
    public void setReceiverKey(String receiverKey)
    {
        this.receiverKey = receiverKey;
    }

    public String getReceiverKey()
    {
        return receiverKey;
    }

    @Override
    public InboundEndpoint getEndpoint()
    {
        return (InboundEndpoint) super.getEndpoint();
    }

    // TODO MULE-4871 Endpoint should not be mutable
    public void setEndpoint(InboundEndpoint endpoint)
    {
        super.setEndpoint(endpoint);
    }

    @Override
    protected WorkManager getWorkManager()
    {
        try
        {
            return connector.getReceiverWorkManager();
        }
        catch (MuleException e)
        {
            logger.error(e);
            return null;
        }
    }

    @Override
    public String toString()
    {
        final StringBuffer sb = new StringBuffer(80);
        sb.append(ClassUtils.getSimpleName(this.getClass()));
        sb.append("{this=").append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(", receiverKey=").append(receiverKey);
        sb.append(", endpoint=").append(endpoint.getEndpointURI());
        sb.append('}');
        return sb.toString();
    }

    public void setListener(MessageProcessor processor)
    {
        this.listener = processor;
    }

    @Override
    protected void doDispose()
    {
        this.listener = null;
        this.flowConstruct = null;
        if (primaryNodeLifecycleNotificationListener != null)
        {
            primaryNodeLifecycleNotificationListener.unregister();
        }
        super.doDispose();
    }

    protected ReplyToHandler getReplyToHandler()
    {
        return ((AbstractConnector) endpoint.getConnector()).getReplyToHandler(endpoint);
    }

    protected ExecutionTemplate<MuleEvent> createExecutionTemplate()
    {
        return TransactionalErrorHandlingExecutionTemplate.createMainExecutionTemplate(endpoint.getMuleContext(), endpoint.getTransactionConfig());
    }

    /**
     * Determines whether to start or not the MessageSource base on the running node state.
     *
     * @return false if this MessageSource should be stated only in the primary node, true if it should be started in every node.
     */
    public boolean shouldConsumeInEveryNode()
    {
        return true;
    }

    @Override
    final protected void connectHandler() throws Exception
    {
        if (shouldConsumeInEveryNode() || getFlowConstruct().getMuleContext().isPrimaryPollingInstance())
        {
            if (logger.isInfoEnabled())
            {
                logger.info("Connecting clusterizable message receiver");
            }
            doConnect();
        }
        else
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Clusterizable message receiver not connected on this node");
            }
        }
    }

    @Override
    final protected void doStartHandler() throws MuleException
    {
        if (shouldConsumeInEveryNode() || getFlowConstruct().getMuleContext().isPrimaryPollingInstance())
        {
            if (logger.isInfoEnabled())
            {
                logger.info("Starting clusterizable message receiver");
            }
            doStart();
        }
        else
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Clusterizable message receiver not started on this node");
            }
        }
    }

    public MuleEvent routeEvent(MuleEvent muleEvent) throws MuleException
    {
        MuleEvent resultEvent = listener.process(muleEvent);
        if (resultEvent != null
            && !VoidMuleEvent.getInstance().equals(resultEvent)
            && resultEvent.getMessage() != null
            && resultEvent.getMessage().getExceptionPayload() != null
            && resultEvent.getMessage().getExceptionPayload().getException() instanceof FilterUnacceptedException)
        {
            handleUnacceptedFilter(muleEvent.getMessage());
            return muleEvent;
        }

        if (endpoint.getExchangePattern().hasResponse() && resultEvent != null
            && !VoidMuleEvent.getInstance().equals(resultEvent))
        {
            // Do not propagate security context back to caller
            MuleSession resultSession = new DefaultMuleSession(resultEvent.getSession());
            resultSession.setSecurityContext(null);
            connector.getSessionHandler().storeSessionInfoToMessage(resultSession, resultEvent.getMessage());

            if (resultEvent.getMessage() != null && !endpoint.isDisableTransportTransformer())
            {
                applyResponseTransformers(resultEvent);
            }

            if (connector.isEnableMessageEvents())
            {
                connector.fireNotification(new EndpointMessageNotification(resultEvent.getMessage(),
                                                                           endpoint, resultEvent.getFlowConstruct(), EndpointMessageNotification.MESSAGE_RESPONSE));
            }
            return resultEvent;
        }
        else
        {
            return null;
        }
    }

    protected void processMessage(final MessageProcessTemplate messageProcessTemplate, final MessageProcessContext messageProcessContext)
    {
        messageProcessingManager.processMessage(messageProcessTemplate,messageProcessContext);
    }

}
