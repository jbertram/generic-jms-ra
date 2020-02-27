/*
 * Copyright 2019 JBoss by Red Hat.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.resource.adapter.jms;

import java.lang.reflect.Method;
import javax.jms.CompletionListener;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.JMSProducer;
import javax.jms.JMSRuntimeException;
import javax.jms.Message;
import javax.jms.MessageProducer;

/**
 * Mapper class for JMSProducer and JMSConsumer to the JMS 1.1 equivalent.
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class JMSProducerToMessageProducer implements MessageProducer {

    private JMSProducer jmsProducer;
    private Destination destination;

    public JMSProducerToMessageProducer(JMSProducer jmsProducer, Destination destination) {
        this.jmsProducer = jmsProducer;
        this.destination = destination;
    }

    @Override
    public void setDisableMessageID(boolean value) throws JMSException {
        if (jmsProducer == null) {
            throw new JMSException("JMSProducer is closed");
        }
        try {
            jmsProducer.setDisableMessageID(value);
        } catch (JMSRuntimeException jmsre) {
            throw new JMSException(jmsre.getLocalizedMessage(), jmsre.getErrorCode());
        }
    }

    @Override
    public boolean getDisableMessageID() throws JMSException {
        if (jmsProducer == null) {
            throw new JMSException("JMSProducer is closed");
        }
        try {
            return jmsProducer.getDisableMessageID();
        } catch (JMSRuntimeException jmsre) {
            throw new JMSException(jmsre.getLocalizedMessage(), jmsre.getErrorCode());
        }
    }

    @Override
    public void setDisableMessageTimestamp(boolean value) throws JMSException {
        if (jmsProducer == null) {
            throw new JMSException("JMSProducer is closed");
        }
        try {
            jmsProducer.setDisableMessageTimestamp(value);
        } catch (JMSRuntimeException jmsre) {
            throw new JMSException(jmsre.getLocalizedMessage(), jmsre.getErrorCode());
        }
    }

    @Override
    public boolean getDisableMessageTimestamp() throws JMSException {
        if (jmsProducer == null) {
            throw new JMSException("JMSProducer is closed");
        }
        try {
            return jmsProducer.getDisableMessageTimestamp();
        } catch (JMSRuntimeException jmsre) {
            throw new JMSException(jmsre.getLocalizedMessage(), jmsre.getErrorCode());
        }
    }

    @Override
    public void setDeliveryMode(int deliveryMode) throws JMSException {
        if (jmsProducer == null) {
            throw new JMSException("JMSProducer is closed");
        }
        try {
            jmsProducer.setDeliveryMode(deliveryMode);
        } catch (JMSRuntimeException jmsre) {
            throw new JMSException(jmsre.getLocalizedMessage(), jmsre.getErrorCode());
        }
    }

    @Override
    public int getDeliveryMode() throws JMSException {
        if (jmsProducer == null) {
            throw new JMSException("JMSProducer is closed");
        }
        try {
            return jmsProducer.getDeliveryMode();
        } catch (JMSRuntimeException jmsre) {
            throw new JMSException(jmsre.getLocalizedMessage(), jmsre.getErrorCode());
        }
    }

    @Override
    public void setPriority(int priority) throws JMSException {
        if (jmsProducer == null) {
            throw new JMSException("JMSProducer is closed");
        }
        try {
            jmsProducer.setPriority(priority);
        } catch (JMSRuntimeException jmsre) {
            throw new JMSException(jmsre.getLocalizedMessage(), jmsre.getErrorCode());
        }
    }

    @Override
    public int getPriority() throws JMSException {
        if (jmsProducer == null) {
            throw new JMSException("JMSProducer is closed");
        }
        try {
            return jmsProducer.getPriority();
        } catch (JMSRuntimeException jmsre) {
            throw new JMSException(jmsre.getLocalizedMessage(), jmsre.getErrorCode());
        }
    }

    @Override
    public void setTimeToLive(long timeToLive) throws JMSException {
        if (jmsProducer == null) {
            throw new JMSException("JMSProducer is closed");
        }
        try {
            jmsProducer.setTimeToLive(timeToLive);
        } catch (JMSRuntimeException jmsre) {
            throw new JMSException(jmsre.getLocalizedMessage(), jmsre.getErrorCode());
        }
    }

    @Override
    public long getTimeToLive() throws JMSException {
        if (jmsProducer == null) {
            throw new JMSException("JMSProducer is closed");
        }
        try {
            return jmsProducer.getTimeToLive();
        } catch (JMSRuntimeException jmsre) {
            throw new JMSException(jmsre.getLocalizedMessage(), jmsre.getErrorCode());
        }
    }

    @Override
    public void setDeliveryDelay(long deliveryDelay) throws JMSException {
        if (jmsProducer == null) {
            throw new JMSException("JMSProducer is closed");
        }
        try {
            jmsProducer.setDeliveryDelay(deliveryDelay);
        } catch (JMSRuntimeException jmsre) {
            throw new JMSException(jmsre.getLocalizedMessage(), jmsre.getErrorCode());
        }
    }

    @Override
    public long getDeliveryDelay() throws JMSException {
        if (jmsProducer == null) {
            throw new JMSException("JMSProducer is closed");
        }
        try {
            return jmsProducer.getDeliveryDelay();
        } catch (JMSRuntimeException jmsre) {
            throw new JMSException(jmsre.getLocalizedMessage(), jmsre.getErrorCode());
        }
    }

    @Override
    public Destination getDestination() throws JMSException {
        if (jmsProducer == null) {
            throw new JMSException("JMSProducer is closed");
        }
        return destination;
    }

    @Override
    public void close() throws JMSException {
        //For Tibco
        if (jmsProducer != null) {
            try {
                Method close = jmsProducer.getClass().getMethod("close");
                try {
                    close.invoke(close);
                } catch (Exception ex) {
                    throw new JMSException(ex.getMessage());
                }
            } catch (NoSuchMethodException ex) {
                //do nothing
            }
        }
        jmsProducer = null;
        destination = null;
    }

    @Override
    public void send(Message message) throws JMSException {
        if (jmsProducer == null) {
            throw new JMSException("JMSProducer is closed");
        }
        try {
            jmsProducer.send(destination, message);
        } catch (JMSRuntimeException jmsre) {
            throw new JMSException(jmsre.getLocalizedMessage(), jmsre.getErrorCode());
        }
    }

    @Override
    public void send(Message message, int deliveryMode, int priority, long timeToLive) throws JMSException {
        if (jmsProducer == null) {
            throw new JMSException("JMSProducer is closed");
        }
        try {
            int oldDeliveryMode = jmsProducer.getDeliveryMode();
            int oldPriority = jmsProducer.getPriority();
            long oldTimeToLive = jmsProducer.getTimeToLive();
            jmsProducer.setDeliveryMode(deliveryMode);
            jmsProducer.setPriority(priority);
            jmsProducer.setTimeToLive(timeToLive);
            jmsProducer.send(destination, message);
            jmsProducer.setDeliveryMode(oldDeliveryMode);
            jmsProducer.setPriority(oldPriority);
            jmsProducer.setTimeToLive(oldTimeToLive);
        } catch (JMSRuntimeException jmsre) {
            throw new JMSException(jmsre.getLocalizedMessage(), jmsre.getErrorCode());
        }
    }

    @Override
    public void send(Destination destination, Message message) throws JMSException {
        if (jmsProducer == null) {
            throw new JMSException("JMSProducer is closed");
        }
        try {
            jmsProducer.send(destination, message);
        } catch (JMSRuntimeException jmsre) {
            throw new JMSException(jmsre.getLocalizedMessage(), jmsre.getErrorCode());
        }
    }

    @Override
    public void send(Destination destination, Message message, int deliveryMode, int priority, long timeToLive) throws JMSException {
        if (jmsProducer == null) {
            throw new JMSException("JMSProducer is closed");
        }
        try {
            int oldDeliveryMode = jmsProducer.getDeliveryMode();
            int oldPriority = jmsProducer.getPriority();
            long oldTimeToLive = jmsProducer.getTimeToLive();
            jmsProducer.setDeliveryMode(deliveryMode);
            jmsProducer.setPriority(priority);
            jmsProducer.setTimeToLive(timeToLive);
            jmsProducer.send(destination, message);
            jmsProducer.setDeliveryMode(oldDeliveryMode);
            jmsProducer.setPriority(oldPriority);
            jmsProducer.setTimeToLive(oldTimeToLive);
        } catch (JMSRuntimeException jmsre) {
            throw new JMSException(jmsre.getLocalizedMessage(), jmsre.getErrorCode());
        }
    }

    @Override
    public void send(Message message, CompletionListener completionListener) throws JMSException {
        throw new JMSException("JMSProducer does not support CompletionListener");
    }

    @Override
    public void send(Message message, int deliveryMode, int priority, long timeToLive, CompletionListener completionListener) throws JMSException {
        throw new JMSException("JMSProducer does not support CompletionListener");
    }

    @Override
    public void send(Destination destination, Message message, CompletionListener completionListener) throws JMSException {
        throw new JMSException("JMSProducer does not support CompletionListener");
    }

    @Override
    public void send(Destination destination, Message message, int deliveryMode, int priority, long timeToLive, CompletionListener completionListener) throws JMSException {
        throw new JMSException("JMSProducer does not support CompletionListener");
    }
    
}
