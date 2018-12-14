package org.jboss.shamrock.reactivemessaging.runtime;

import javax.enterprise.context.Dependent;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.jboss.shamrock.runtime.ShutdownEvent;
import org.jboss.shamrock.runtime.StartupEvent;

import io.smallrye.reactive.messaging.extension.MediatorManager;

@Dependent
public class ReactiveMessagingLifecycle {

    @Inject
    MediatorManager mediatorManager;
    
    void onApplicationStart(@Observes StartupEvent event) {
        // mediatorManager.initializeAndRun(beanManager, registry, done);
    }
    
    void onApplicationShutdown(@Observes ShutdownEvent event) {
        mediatorManager.shutdown();
    }
    
}
