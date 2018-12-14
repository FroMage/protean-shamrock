package org.jboss.shamrock.reactivemessaging.runtime;

import java.util.List;
import java.util.Map;

import org.jboss.shamrock.runtime.Template;
import org.jboss.shamrock.runtime.cdi.BeanContainer;

import io.smallrye.reactive.messaging.extension.MediatorManager;

/**
*
* @author Martin Kouba
*/
@Template
public class ReactiveMessagingTemplate {
    
    public static final String KEY_INVOKER = "invoker";
    public static final String KEY_INCOMING = "incoming";
    public static final String KEY_OUTGOING = "outgoing";
    public static final String KEY_NAME = "name";
    public static final String KEY_PROVIDER = "provider";

    public void registerMediators(List<Map<String, Object>> configurations, BeanContainer container) {
        // Extract the configuration and register mediators
        MediatorManager mediatorManager = container.instance(MediatorManager.class);
        for (Map<String, Object> config : configurations) {
            // TODO: build and register MediatorConfiguration
        }
        
    }
    
}
