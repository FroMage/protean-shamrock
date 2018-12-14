/*
 * Copyright 2018 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.shamrock.reactivemessaging.deployment;

import static org.jboss.shamrock.annotations.ExecutionTime.STATIC_INIT;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.DotName;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;
import org.jboss.protean.arc.Arc;
import org.jboss.protean.arc.ArcContainer;
import org.jboss.protean.arc.InstanceHandle;
import org.jboss.protean.arc.processor.AnnotationStore;
import org.jboss.protean.arc.processor.BeanDeploymentValidator;
import org.jboss.protean.arc.processor.BeanInfo;
import org.jboss.protean.arc.processor.DotNames;
import org.jboss.protean.arc.processor.ScopeInfo;
import org.jboss.protean.gizmo.ClassCreator;
import org.jboss.protean.gizmo.ClassOutput;
import org.jboss.protean.gizmo.MethodCreator;
import org.jboss.protean.gizmo.MethodDescriptor;
import org.jboss.protean.gizmo.ResultHandle;
import org.jboss.shamrock.annotations.BuildProducer;
import org.jboss.shamrock.annotations.BuildStep;
import org.jboss.shamrock.annotations.Record;
import org.jboss.shamrock.arc.deployment.BeanDeploymentValidatorBuildItem;
import org.jboss.shamrock.deployment.builditem.BeanContainerBuildItem;
import org.jboss.shamrock.deployment.builditem.GeneratedClassBuildItem;
import org.jboss.shamrock.deployment.builditem.substrate.ReflectiveClassBuildItem;
import org.jboss.shamrock.reactivemessaging.runtime.ReactiveMessagingTemplate;

/**
 *
 * @author Martin Kouba
 */
public class ReactiveMessaingProcessor {

    private static final Logger LOGGER = Logger.getLogger("org.jboss.shamrock.scheduler.deployment.processor");

    static final DotName NAME_INCOMING = DotName.createSimple(Incoming.class.getName());
    static final DotName NAME_OUTGOING = DotName.createSimple(Outgoing.class.getName());

    static final String INVOKER_SUFFIX = "_MediatorInvoker";

    private static final AtomicInteger INVOKER_INDEX = new AtomicInteger();

    @BuildStep
    List<ReflectiveClassBuildItem> reflectiveClasses() {
        List<ReflectiveClassBuildItem> reflectiveClasses = new ArrayList<>();
        // TODO
        return reflectiveClasses;
    }

    @BuildStep
    BeanDeploymentValidatorBuildItem beanDeploymentValidator(BuildProducer<MediatorMethodItem> mediatorMethods) {

        return new BeanDeploymentValidatorBuildItem(new BeanDeploymentValidator() {

            @Override
            public void validate(ValidationContext validationContext) {

                AnnotationStore annotationStore = validationContext.get(Key.ANNOTATION_STORE);

                // We need to collect all business methods annotated with @Incoming/@Outgoing first
                for (BeanInfo bean : validationContext.get(Key.BEANS)) {
                    if (bean.isClassBean()) {
                        // TODO: inherited business methods?
                        for (MethodInfo method : bean.getTarget()
                                .get()
                                .asClass()
                                .methods()) {

                            AnnotationInstance incomingAnnotation = annotationStore.getAnnotation(method, NAME_INCOMING);
                            AnnotationInstance outgoingAnnotation = annotationStore.getAnnotation(method, NAME_OUTGOING);

                            if (incomingAnnotation != null || outgoingAnnotation != null) {
                                // TODO: validate method params and return type?
                                mediatorMethods.produce(new MediatorMethodItem(bean, method, incomingAnnotation, outgoingAnnotation));
                                LOGGER.debugf("Found mediator business method %s declared on %s", method, bean);
                            }
                        }

                    }
                }
            }
        });
    }

    @BuildStep
    @Record(STATIC_INIT)
    public void build(ReactiveMessagingTemplate template, BeanContainerBuildItem beanContainer, List<MediatorMethodItem> mediatorMethods,
            BuildProducer<GeneratedClassBuildItem> generatedResource, BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {

        List<Map<String, Object>> configurations = new ArrayList<>();
        ProcessorClassOutput processorClassOutput = new ProcessorClassOutput(generatedResource);

        for (MediatorMethodItem mediatorMethod : mediatorMethods) {
            // For every method annotated with @Incoming/@Outgoing record the bytecode representing the configuration
            Map<String, Object> config = new HashMap<>();
            String invokerClass = generateInvoker(mediatorMethod.getBean(), mediatorMethod.getMethod(), processorClassOutput);
            reflectiveClass.produce(new ReflectiveClassBuildItem(false, false, invokerClass));
            config.put(ReactiveMessagingTemplate.KEY_INVOKER, invokerClass);
            if (mediatorMethod.getIncoming() != null) {
                Map<String, Object> incoming = new HashMap<>();
                incoming.put(ReactiveMessagingTemplate.KEY_NAME, getValue(mediatorMethod.getIncoming()
                        .value("name")));
                incoming.put(ReactiveMessagingTemplate.KEY_PROVIDER, getValue(mediatorMethod.getIncoming()
                        .value("provider")));
                config.put(ReactiveMessagingTemplate.KEY_INCOMING, incoming);
            }
            if (mediatorMethod.getOutgoing() != null) {
                Map<String, Object> outgoing = new HashMap<>();
                outgoing.put(ReactiveMessagingTemplate.KEY_NAME, getValue(mediatorMethod.getOutgoing()
                        .value("name")));
                outgoing.put(ReactiveMessagingTemplate.KEY_PROVIDER, getValue(mediatorMethod.getOutgoing()
                        .value("provider")));
                config.put(ReactiveMessagingTemplate.KEY_OUTGOING, outgoing);
            }
            // TODO: other stuff needed for MediatorConfiguration
            configurations.add(config);
        }
        template.registerMediators(configurations, beanContainer.getValue());
    }

    private String generateInvoker(BeanInfo bean, MethodInfo method, ProcessorClassOutput processorClassOutput) {

        String baseName;
        if (bean.getImplClazz()
                .enclosingClass() != null) {
            baseName = DotNames.simpleName(bean.getImplClazz()
                    .enclosingClass()) + "_" + DotNames.simpleName(
                            bean.getImplClazz()
                                    .name());
        } else {
            baseName = DotNames.simpleName(bean.getImplClazz()
                    .name());
        }
        String targetPackage = DotNames.packageName(bean.getImplClazz()
                .name());
        String generatedName = targetPackage.replace('.', '/') + "/" + baseName + INVOKER_SUFFIX + INVOKER_INDEX.incrementAndGet();

        ClassCreator invokerCreator = ClassCreator.builder()
                .classOutput(processorClassOutput)
                .className(generatedName)
                .build();
        // TODO: .interfaces(MediatorInvoker.class)

        MethodCreator invoke = invokerCreator.getMethodCreator("invoke", void.class, Object[].class);
        // InstanceHandle<Foo> handle = Arc.container().instance("1");
        // handle.get().ping();
        ResultHandle containerHandle = invoke.invokeStaticMethod(MethodDescriptor.ofMethod(Arc.class, "container", ArcContainer.class));
        ResultHandle instanceHandle = invoke.invokeInterfaceMethod(
                MethodDescriptor.ofMethod(ArcContainer.class, "instance", InstanceHandle.class, String.class), containerHandle,
                invoke.load(bean.getIdentifier()));
        ResultHandle beanHandle = invoke.invokeInterfaceMethod(MethodDescriptor.ofMethod(InstanceHandle.class, "get", Object.class), instanceHandle);
        if (method.parameters()
                .isEmpty()) {
            invoke.invokeVirtualMethod(MethodDescriptor.ofMethod(bean.getImplClazz()
                    .name()
                    .toString(), method.name(), void.class), beanHandle);
        } else {
            // TODO: handle param types correctly
            ResultHandle paramTypesArray = invoke.newArray(Class.class, invoke.load(method.parameters()
                    .size()));
            ResultHandle argsArray = invoke.newArray(Object.class, invoke.load(method.parameters()
                    .size()));
            int idx = 0;
            for (Type param : method.parameters()) {
                invoke.writeArrayValue(paramTypesArray, idx++, invoke.loadClass(param.name()
                        .toString()));
                invoke.writeArrayValue(argsArray, idx++, invoke.getMethodParam(idx));
            }

            invoke.invokeVirtualMethod(MethodDescriptor.ofMethod(bean.getImplClazz()
                    .name()
                    .toString(), method.name(),
                    method.returnType()
                            .name()
                            .toString(),
                    paramTypesArray), beanHandle, argsArray);
        }
        // handle.destroy() - destroy dependent instance afterwards
        if (bean.getScope() == ScopeInfo.DEPENDENT) {
            invoke.invokeInterfaceMethod(MethodDescriptor.ofMethod(InstanceHandle.class, "destroy", void.class), instanceHandle);
        }
        invoke.returnValue(null);

        invokerCreator.close();
        return generatedName.replace('/', '.');
    }

    private Object getValue(AnnotationValue annotationValue) {
        switch (annotationValue.kind()) {
            case STRING:
                return annotationValue.asString();
            case LONG:
                return annotationValue.asLong();
            case ENUM:
                return annotationValue.asEnum();
            case CLASS:
                return annotationValue.asClass();
            default:
                throw new IllegalArgumentException("Unsupported annotation value: " + annotationValue);
        }
    }

    static final class ProcessorClassOutput implements ClassOutput {
        private final BuildProducer<GeneratedClassBuildItem> producer;

        ProcessorClassOutput(BuildProducer<GeneratedClassBuildItem> producer) {
            this.producer = producer;
        }

        public void write(final String name, final byte[] data) {
            producer.produce(new GeneratedClassBuildItem(true, name, data));
        }

    }

}