/*
 * Copyright 2014-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.boot.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.OrderComparator;

import grails.boot.Grails;
import grails.core.GrailsApplication;
import grails.core.GrailsApplicationLifeCycle;
import grails.plugins.GrailsPluginManager;
import grails.util.Environment;
import grails.util.Holders;

import org.grails.core.lifecycle.ShutdownOperations;
import org.grails.datastore.mapping.model.MappingContext;

/**
 * A {@link ApplicationListener} to initialize Grails with Plugins and GrailsApplicationLifeCycles
 * when an {@link ApplicationContext} gets refreshed or closed.
 *
 * @author Graeme Rocher
 * @author Michael Yan
 * @since 3.0
 *
 * @see GrailsApplicationPostProcessor
 */
public class GrailsApplicationEventListener implements ApplicationListener<ApplicationContextEvent> {

    @Override
    public void onApplicationEvent(ApplicationContextEvent event) {
        ApplicationContext applicationContext = event.getApplicationContext();
        GrailsApplication grailsApplication = applicationContext.getBean(GrailsApplication.APPLICATION_ID, GrailsApplication.class);
        GrailsPluginManager pluginManager = applicationContext.getBean(GrailsPluginManager.BEAN_NAME, GrailsPluginManager.class);

        List<GrailsApplicationLifeCycle> lifeCycleBeans =
                applicationContext.getBeansOfType(GrailsApplicationLifeCycle.class)
                        .values()
                        .stream().sorted(OrderComparator.INSTANCE)
                        .collect(Collectors.toList());

        if (event instanceof ContextRefreshedEvent) {
            if (applicationContext.containsBean("grailsDomainClassMappingContext")) {
                grailsApplication.setMappingContext(
                        applicationContext.getBean("grailsDomainClassMappingContext", MappingContext.class)
                );
            }
            Environment.setInitializing(false);

            pluginManager.setApplicationContext(applicationContext);
            pluginManager.doDynamicMethods();
            for (GrailsApplicationLifeCycle lifeCycle : lifeCycleBeans) {
                lifeCycle.doWithDynamicMethods();
            }

            pluginManager.doPostProcessing(applicationContext);
            for (GrailsApplicationLifeCycle lifeCycle : lifeCycleBeans) {
                lifeCycle.doWithApplicationContext();
            }

            Holders.setPluginManager(pluginManager);

            Map<String, Object> eventMap = new HashMap<>();
            eventMap.put("source", pluginManager);
            pluginManager.onStartup(eventMap);
            for (GrailsApplicationLifeCycle lifeCycle : lifeCycleBeans) {
                lifeCycle.onStartup(eventMap);
            }
        }
        else if (event instanceof ContextClosedEvent) {
            Map<String, Object> eventMap = new HashMap<>();
            eventMap.put("source", pluginManager);

            List<GrailsApplicationLifeCycle> reversedLifeCycleBeans = new ArrayList<>(lifeCycleBeans);
            Collections.reverse(reversedLifeCycleBeans);
            for (GrailsApplicationLifeCycle lifeCycle : reversedLifeCycleBeans) {
                lifeCycle.onShutdown(eventMap);
            }

            pluginManager.shutdown();
            ShutdownOperations.runOperations();
            Holders.clear();
            Grails.setDevelopmentModeActive(false);
        }
    }

}