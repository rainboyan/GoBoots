/*
 * Copyright 2004-2022 the original author or authors.
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
package grails.plugins;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovySystem;
import groovy.lang.MetaClassRegistry;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.IOGroovyMethods;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.Assert;

import grails.core.GrailsApplication;
import grails.core.support.ParentApplicationContextAware;
import grails.plugins.exceptions.PluginException;
import grails.util.Environment;
import grails.util.GrailsClassUtils;

import org.grails.core.exceptions.GrailsConfigurationException;
import org.grails.core.io.CachingPathMatchingResourcePatternResolver;
import org.grails.io.support.GrailsResourceUtils;
import org.grails.plugins.AbstractGrailsPluginManager;
import org.grails.plugins.BinaryGrailsPlugin;
import org.grails.plugins.BinaryGrailsPluginDescriptor;
import org.grails.plugins.CorePluginFinder;
import org.grails.plugins.DefaultGrailsPlugin;
import org.grails.plugins.IdentityPluginFilter;
import org.grails.plugins.PluginFilterRetriever;
import org.grails.spring.DefaultRuntimeSpringConfiguration;
import org.grails.spring.RuntimeSpringConfiguration;

/**
 * <p>Handles the loading and management of plugins in the Grails system.
 * A plugin is just like a normal Grails application except that it contains a file ending
 * in *Plugin.groovy in the root of the directory.
 * <p/>
 * <p>A Plugin class is a Groovy class that has a version and optionally closures
 * called doWithSpring, doWithContext and doWithWebDescriptor
 * <p/>
 * <p>The doWithSpring closure uses the BeanBuilder syntax (@see grails.spring.BeanBuilder) to
 * provide runtime configuration of Grails via Spring
 * <p/>
 * <p>The doWithContext closure is called after the Spring ApplicationContext is built and accepts
 * a single argument (the ApplicationContext)
 * <p/>
 * <p>The doWithWebDescriptor uses mark-up building to provide additional functionality to the web.xml
 * file
 * <p/>
 * <p> Example:
 * <pre>
 * class ClassEditorGrailsPlugin {
 *      def version = '1.1'
 *      def doWithSpring = { application ->
 *          classEditor(org.springframework.beans.propertyeditors.ClassEditor, application.classLoader)
 *      }
 * }
 * </pre>
 * <p/>
 * <p>A plugin can also define "dependsOn" and "evict" properties that specify what plugins the plugin
 * depends on and which ones it is incompatible with and should evict
 *
 * @author Graeme Rocher
 * @since 0.4
 */
public class DefaultGrailsPluginManager extends AbstractGrailsPluginManager {

    private static final Log logger = LogFactory.getLog(DefaultGrailsPluginManager.class);

    protected static final Class<?>[] COMMON_CLASSES = {
            Boolean.class, Byte.class, Character.class, Class.class, Double.class, Float.class,
            Integer.class, Long.class, Number.class, Short.class, String.class, BigInteger.class,
            BigDecimal.class, URL.class, URI.class };

    private static final String GRAILS_VERSION = "grailsVersion";

    private static final String GRAILS_PLUGIN_SUFFIX = "GrailsPlugin";

    private final List<GrailsPlugin> delayedLoadPlugins = new LinkedList<>();

    private ApplicationContext parentCtx;

    private PathMatchingResourcePatternResolver resolver;

    private final Map<GrailsPlugin, String[]> delayedEvictions = new HashMap<>();

    private final Map<String, Set<GrailsPlugin>> pluginToObserverMap = new HashMap<>();

    private PluginFilter pluginFilter;

    private List<GrailsPlugin> userPlugins = new ArrayList<>();

    public DefaultGrailsPluginManager(String resourcePath, GrailsApplication application) {
        super(application);
        Assert.notNull(application, "Argument [application] cannot be null!");

        this.resolver = CachingPathMatchingResourcePatternResolver.INSTANCE;
        try {
            this.pluginResources = this.resolver.getResources(resourcePath);
        }
        catch (IOException ioe) {
            logger.debug("Unable to load plugins for resource path " + resourcePath, ioe);
        }
        this.application = application;
        setPluginFilter();
    }

    public DefaultGrailsPluginManager(String[] pluginResources, GrailsApplication application) {
        super(application);
        this.resolver = CachingPathMatchingResourcePatternResolver.INSTANCE;

        List<Resource> resourceList = new ArrayList<>();
        for (String resourcePath : pluginResources) {
            try {
                resourceList.addAll(Arrays.asList(this.resolver.getResources(resourcePath)));
            }
            catch (IOException ioe) {
                logger.debug("Unable to load plugins for resource path " + resourcePath, ioe);
            }
        }

        this.pluginResources = resourceList.toArray(new Resource[0]);
        this.application = application;
        setPluginFilter();
    }

    public DefaultGrailsPluginManager(Class<?>[] plugins, GrailsApplication application) {
        super(application);
        this.pluginClasses = plugins;
        this.resolver = CachingPathMatchingResourcePatternResolver.INSTANCE;
        this.application = application;
        setPluginFilter();
    }

    public DefaultGrailsPluginManager(Resource[] pluginFiles, GrailsApplication application) {
        super(application);
        this.resolver = CachingPathMatchingResourcePatternResolver.INSTANCE;
        this.pluginResources = pluginFiles;
        this.application = application;
        setPluginFilter();
    }

    public DefaultGrailsPluginManager(GrailsApplication application) {
        super(application);
    }

    public GrailsPlugin[] getUserPlugins() {
        return this.userPlugins.toArray(new GrailsPlugin[0]);
    }

    private void setPluginFilter() {
        this.pluginFilter = new PluginFilterRetriever().getPluginFilter(this.application.getConfig());
    }

    public void refreshPlugin(String name) {
        if (hasGrailsPlugin(name)) {
            getGrailsPlugin(name).refresh();
        }
    }

    public Collection<GrailsPlugin> getPluginObservers(GrailsPlugin plugin) {
        Assert.notNull(plugin, "Argument [plugin] cannot be null");

        Collection<GrailsPlugin> c = this.pluginToObserverMap.get(plugin.getName());

        // Add any wildcard observers.
        Collection<GrailsPlugin> wildcardObservers = this.pluginToObserverMap.get("*");
        if (wildcardObservers != null) {
            if (c != null) {
                c.addAll(wildcardObservers);
            }
            else {
                c = wildcardObservers;
            }
        }

        if (c != null) {
            // Make sure this plugin is not observing itself!
            c.remove(plugin);
            return c;
        }

        return Collections.emptySet();
    }

    public void informObservers(String pluginName, Map<String, Object> event) {
        GrailsPlugin plugin = getGrailsPlugin(pluginName);
        if (plugin == null) {
            return;
        }
        if (!plugin.isEnabled(this.applicationContext.getEnvironment().getActiveProfiles())) {
            return;
        }

        for (GrailsPlugin observingPlugin : getPluginObservers(plugin)) {
            if (!observingPlugin.isEnabled(this.applicationContext.getEnvironment().getActiveProfiles())) {
                continue;
            }

            observingPlugin.notifyOfEvent(event);
        }
    }

    /* (non-Javadoc)
     * @see grails.plugins.GrailsPluginManager#loadPlugins()
     */
    public void loadPlugins() throws PluginException {
        long time = System.currentTimeMillis();
        if (this.initialised) {
            return;
        }

        ClassLoader gcl = this.application.getClassLoader();

        attemptLoadPlugins(gcl);

        if (!this.delayedLoadPlugins.isEmpty()) {
            loadDelayedPlugins();
        }
        if (!this.delayedEvictions.isEmpty()) {
            processDelayedEvictions();
        }

        this.pluginList = sortPlugins(this.pluginList);
        initializePlugins();
        this.initialised = true;
        logger.info(String.format("Total %d plugins loaded successfully, take in %dms.", this.pluginList.size(),
                (System.currentTimeMillis() - time)));
    }

    protected List<GrailsPlugin> sortPlugins(List<GrailsPlugin> toSort) {
        /* http://en.wikipedia.org/wiki/Topological_sorting
         *
        * L ← Empty list that will contain the sorted nodes
         S ← Set of all nodes

        function visit(node n)
            if n has not been visited yet then
                mark n as visited
                for each node m with an edge from n to m do
                    visit(m)
                add n to L

        for each node n in S do
            visit(n)

         */
        List<GrailsPlugin> sortedPlugins = new ArrayList<>(toSort.size());
        Set<GrailsPlugin> visitedPlugins = new HashSet<>();
        Map<GrailsPlugin, List<GrailsPlugin>> loadOrderDependencies = resolveLoadDependencies(toSort);

        for (GrailsPlugin plugin : toSort) {
            visitTopologicalSort(plugin, sortedPlugins, visitedPlugins, loadOrderDependencies);
        }

        return sortedPlugins;
    }

    protected Map<GrailsPlugin, List<GrailsPlugin>> resolveLoadDependencies(List<GrailsPlugin> plugins) {
        Map<GrailsPlugin, List<GrailsPlugin>> loadOrderDependencies = new HashMap<>();

        for (GrailsPlugin plugin : plugins) {
            if (plugin.getLoadAfterNames() != null) {
                List<GrailsPlugin> loadDepsForPlugin = loadOrderDependencies.computeIfAbsent(plugin, k -> new ArrayList<>());
                for (String pluginName : plugin.getLoadAfterNames()) {
                    GrailsPlugin loadAfterPlugin = getGrailsPlugin(pluginName);
                    if (loadAfterPlugin != null) {
                        loadDepsForPlugin.add(loadAfterPlugin);
                    }
                }
            }
            for (String loadBefore : plugin.getLoadBeforeNames()) {
                GrailsPlugin loadBeforePlugin = getGrailsPlugin(loadBefore);
                if (loadBeforePlugin != null) {
                    List<GrailsPlugin> loadDepsForPlugin = loadOrderDependencies.computeIfAbsent(loadBeforePlugin, k -> new ArrayList<>());
                    loadDepsForPlugin.add(plugin);
                }
            }
        }
        return loadOrderDependencies;
    }

    private void visitTopologicalSort(GrailsPlugin plugin, List<GrailsPlugin> sortedPlugins,
            Set<GrailsPlugin> visitedPlugins, Map<GrailsPlugin, List<GrailsPlugin>> loadOrderDependencies) {
        if (plugin != null && !visitedPlugins.contains(plugin)) {
            visitedPlugins.add(plugin);
            List<GrailsPlugin> loadDepsForPlugin = loadOrderDependencies.get(plugin);
            if (loadDepsForPlugin != null) {
                for (GrailsPlugin dependentPlugin : loadDepsForPlugin) {
                    visitTopologicalSort(dependentPlugin, sortedPlugins, visitedPlugins, loadOrderDependencies);
                }
            }
            sortedPlugins.add(plugin);
        }
    }

    private void attemptLoadPlugins(ClassLoader gcl) {
        // retrieve load core plugins first
        List<GrailsPlugin> grailsCorePlugins = this.loadCorePlugins ? findCorePlugins() : new ArrayList<>();

        List<GrailsPlugin> grailsUserPlugins = findUserPlugins(gcl);
        this.userPlugins = grailsUserPlugins;

        List<GrailsPlugin> allPlugins = new ArrayList<>(grailsCorePlugins);
        allPlugins.addAll(grailsUserPlugins);

        //filtering applies to user as well as core plugins
        List<GrailsPlugin> filteredPlugins = getPluginFilter().filterPluginList(allPlugins);

        //make sure core plugins are loaded first
        List<GrailsPlugin> orderedCorePlugins = new ArrayList<>();
        List<GrailsPlugin> orderedUserPlugins = new ArrayList<>();

        for (GrailsPlugin plugin : filteredPlugins) {
            if (grailsCorePlugins.contains(plugin)) {
                orderedCorePlugins.add(plugin);
            }
            else {
                orderedUserPlugins.add(plugin);
            }
        }

        List<GrailsPlugin> orderedPlugins = new ArrayList<>();
        orderedPlugins.addAll(orderedCorePlugins);
        orderedPlugins.addAll(orderedUserPlugins);

        for (GrailsPlugin plugin : orderedPlugins) {
            attemptPluginLoad(plugin);
        }
    }

    private List<GrailsPlugin> findCorePlugins() {
        CorePluginFinder finder = new CorePluginFinder(this.application);
        finder.setParentApplicationContext(this.parentCtx);

        List<GrailsPlugin> grailsCorePlugins = new ArrayList<>();

        final Class<?>[] corePluginClasses = finder.getPluginClasses();

        logger.info("Attempting to load [" + corePluginClasses.length + "] core plugins");

        for (Class<?> pluginClass : corePluginClasses) {
            if (pluginClass != null && !Modifier.isAbstract(pluginClass.getModifiers()) && pluginClass != DefaultGrailsPlugin.class) {
                final BinaryGrailsPluginDescriptor binaryDescriptor = finder.getBinaryDescriptor(pluginClass);
                GrailsPlugin plugin;
                if (binaryDescriptor != null) {
                    plugin = createBinaryGrailsPlugin(pluginClass, binaryDescriptor);
                }
                else {
                    plugin = createGrailsPlugin(pluginClass);
                }
                plugin.setApplicationContext(this.applicationContext);

                isCompatiblePlugin(plugin);

                grailsCorePlugins.add(plugin);
            }
        }
        return grailsCorePlugins;
    }

    private String getPluginGrailsVersion(GrailsPlugin plugin) {
        final Object grailsVersionValue = GrailsClassUtils.getPropertyOrStaticPropertyOrFieldValue(plugin.getInstance(), GRAILS_VERSION);
        return grailsVersionValue != null ? grailsVersionValue.toString() : null;
    }

    /**
     * Checks plugin compatibility against used Grails version
     *
     * @param plugin the plugin to check
     * @return true only in case plugin is compatible or impossible to determine, false otherwise
     */
    private boolean isCompatiblePlugin(GrailsPlugin plugin) {
        final String pluginGrailsVersion = getPluginGrailsVersion(plugin);

        if (pluginGrailsVersion == null || pluginGrailsVersion.contains("@")) {
            logger.debug("Plugin grails version is null or containing '@'. Compatibility check skipped.");
            return true;
        }

        final String appGrailsVersion = this.application.getMetadata().getGrailsVersion();
        final String pluginMinGrailsVersion = GrailsVersionUtils.getLowerVersion(pluginGrailsVersion);
        final String pluginMaxGrailsVersion = GrailsVersionUtils.getUpperVersion(pluginGrailsVersion);

        if (appGrailsVersion == null) {
            return true;
        }

        if (pluginMinGrailsVersion.equals("*")) {
            logger.error("grailsVersion not formatted as expected, unable to determine compatibility.");
            return false;
        }

        VersionComparator comparator = new VersionComparator();

        if (pluginMinGrailsVersion.equals(pluginMaxGrailsVersion)) {
            //exact version compatibility required
            if (!appGrailsVersion.equals(pluginMinGrailsVersion)) {
                logger.warn("Plugin [" + plugin.getName() + ":" + plugin.getVersion() +
                        "] may not be compatible with this application as the application Grails version is not equal" +
                        " to the one that plugin requires. Plugin is compatible with Grails version " +
                        pluginGrailsVersion + " but app is " + appGrailsVersion);
                return false;
            }
        }
        if (!pluginMaxGrailsVersion.equals("*")) {
            // Case 1: max version not specified. Forward compatibility expected

            // minimum version required by plugin cannot be greater than grails app version
            if (comparator.compare(pluginMinGrailsVersion, appGrailsVersion) > 0) {
                logger.warn("Plugin [" + plugin.getName() + ":" + plugin.getVersion() +
                        "] may not be compatible with this application as the application Grails version is less" +
                        " than the plugin requires. Plugin is compatible with Grails version " +
                        pluginGrailsVersion + " but app is " + appGrailsVersion);
                return false;
            }
        }
        else {
            // Case 2: both max and min version specified. Strict compatibility expected

            // minimum version required by plugin cannot be greater than grails app version
            if (comparator.compare(pluginMinGrailsVersion, appGrailsVersion) > 0) {
                logger.warn("Plugin [" + plugin.getName() + ":" + plugin.getVersion() +
                        "] may not be compatible with this application as the application Grails version is less" +
                        " than the plugin requires. Plugin is compatible with Grails version " +
                        pluginGrailsVersion + " but app is " + appGrailsVersion);
                return false;
            }

            // maximum version required by plugin cannot be less than grails app version
            if (comparator.compare(pluginMaxGrailsVersion, appGrailsVersion) < 0) {
                logger.warn("Plugin [" + plugin.getName() + ":" + plugin.getVersion() +
                        "] may not be compatible with this application as the application Grails version is greater" +
                        " than the plugins max specified. Plugin is compatible with Grails versions " +
                        pluginGrailsVersion + " but app is " + appGrailsVersion);
                return false;
            }
        }

        return true;
    }

    private GrailsPlugin createBinaryGrailsPlugin(Class<?> pluginClass, BinaryGrailsPluginDescriptor binaryDescriptor) {
        return new BinaryGrailsPlugin(pluginClass, binaryDescriptor, this.application);
    }

    protected GrailsPlugin createGrailsPlugin(Class<?> pluginClass) {
        return new DefaultGrailsPlugin(pluginClass, this.application);
    }

    protected GrailsPlugin createGrailsPlugin(Class<?> pluginClass, Resource resource) {
        return new DefaultGrailsPlugin(pluginClass, resource, this.application);
    }

    private List<GrailsPlugin> findUserPlugins(ClassLoader gcl) {
        List<GrailsPlugin> grailsUserPlugins = new ArrayList<>();

        logger.info("Attempting to load [" + this.pluginResources.length + "] user defined plugins");
        for (Resource r : this.pluginResources) {
            Class<?> pluginClass = loadPluginClass(gcl, r);

            if (isGrailsPlugin(pluginClass)) {
                GrailsPlugin plugin = createGrailsPlugin(pluginClass, r);
                //attemptPluginLoad(plugin);
                isCompatiblePlugin(plugin);
                grailsUserPlugins.add(plugin);
            }
            else {
                logger.warn("Class [" + pluginClass + "] not loaded as plugin. Grails plugins must end with the convention 'GrailsPlugin'!");
            }
        }

        for (Class<?> pluginClass : this.pluginClasses) {
            if (isGrailsPlugin(pluginClass)) {
                GrailsPlugin plugin = createGrailsPlugin(pluginClass);
                //attemptPluginLoad(plugin);
                isCompatiblePlugin(plugin);
                grailsUserPlugins.add(plugin);
            }
            else {
                logger.warn("Class [" + pluginClass + "] not loaded as plugin. Grails plugins must end with the convention 'GrailsPlugin'!");
            }
        }
        return grailsUserPlugins;
    }

    private boolean isGrailsPlugin(Class<?> pluginClass) {
        return pluginClass != null && pluginClass.getName().endsWith(GRAILS_PLUGIN_SUFFIX);
    }

    private void processDelayedEvictions() {
        for (Map.Entry<GrailsPlugin, String[]> entry : this.delayedEvictions.entrySet()) {
            GrailsPlugin plugin = entry.getKey();
            for (String pluginName : entry.getValue()) {
                evictPlugin(plugin, pluginName);
            }
        }
    }

    private void initializePlugins() {
        for (GrailsPlugin plugin : this.plugins.values()) {
            if (plugin != null) {
                plugin.setApplicationContext(this.applicationContext);
            }
        }
    }

    /**
     * This method will attempt to load that plugins not loaded in the first pass
     */
    private void loadDelayedPlugins() {
        while (!this.delayedLoadPlugins.isEmpty()) {
            GrailsPlugin plugin = this.delayedLoadPlugins.remove(0);
            if (areDependenciesResolved(plugin)) {
                if (!hasValidPluginsToLoadBefore(plugin)) {
                    registerPlugin(plugin);
                }
                else {
                    this.delayedLoadPlugins.add(plugin);
                }
            }
            else {
                // ok, it still hasn't resolved the dependency after the initial
                // load of all plugins. All hope is not lost, however, so lets first
                // look inside the remaining delayed loads before giving up
                boolean foundInDelayed = false;
                for (GrailsPlugin remainingPlugin : this.delayedLoadPlugins) {
                    if (isDependentOn(plugin, remainingPlugin)) {
                        foundInDelayed = true;
                        break;
                    }
                }
                if (foundInDelayed) {
                    this.delayedLoadPlugins.add(plugin);
                }
                else {
                    this.failedPlugins.put(plugin.getName(), plugin);
                    logger.error("ERROR: Plugin [" + plugin.getName() + "] cannot be loaded because its dependencies [" +
                            DefaultGroovyMethods.inspect(plugin.getDependencyNames()) + "] cannot be resolved");
                }
            }
        }
    }

    private boolean hasValidPluginsToLoadBefore(GrailsPlugin plugin) {
        String[] loadAfterNames = plugin.getLoadAfterNames();
        for (GrailsPlugin delayedLoadPlugin : this.delayedLoadPlugins) {
            for (String name : loadAfterNames) {
                if (delayedLoadPlugin.getName().equals(name)) {
                    return hasDelayedDependencies(delayedLoadPlugin) || areDependenciesResolved(delayedLoadPlugin);
                }
            }
        }
        return false;
    }

    private boolean hasDelayedDependencies(GrailsPlugin other) {
        String[] dependencyNames = other.getDependencyNames();
        for (String dependencyName : dependencyNames) {
            for (GrailsPlugin grailsPlugin : this.delayedLoadPlugins) {
                if (grailsPlugin.getName().equals(dependencyName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks whether the first plugin is dependant on the second plugin.
     *
     * @param plugin     The plugin to check
     * @param dependency The plugin which the first argument may be dependant on
     * @return true if it is
     */
    private boolean isDependentOn(GrailsPlugin plugin, GrailsPlugin dependency) {
        for (String name : plugin.getDependencyNames()) {
            String requiredVersion = plugin.getDependentVersion(name);

            if (name.equals(dependency.getName()) &&
                    GrailsVersionUtils.isValidVersion(dependency.getVersion(), requiredVersion)) {
                return true;
            }
        }
        return false;
    }

    private boolean areDependenciesResolved(GrailsPlugin plugin) {
        for (String name : plugin.getDependencyNames()) {
            if (!hasGrailsPlugin(name, plugin.getDependentVersion(name))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns true if there are no plugins left that should, if possible, be loaded before this plugin.
     *
     * @param plugin The plugin
     * @return true if there are
     */
    private boolean areNoneToLoadBefore(GrailsPlugin plugin) {
        for (String name : plugin.getLoadAfterNames()) {
            if (getGrailsPlugin(name) == null) {
                return false;
            }
        }
        return true;
    }

    private Class<?> loadPluginClass(ClassLoader cl, Resource r) {
        Class<?> pluginClass;
        if (cl instanceof GroovyClassLoader) {
            try {
                if (logger.isInfoEnabled()) {
                    logger.info("Parsing & compiling " + r.getFilename());
                }
                pluginClass = ((GroovyClassLoader) cl).parseClass(IOGroovyMethods.getText(r.getInputStream(), "UTF-8"));
            }
            catch (CompilationFailedException e) {
                throw new PluginException("Error compiling plugin [" + r.getFilename() + "] " + e.getMessage(), e);
            }
            catch (IOException e) {
                throw new PluginException("Error reading plugin [" + r.getFilename() + "] " + e.getMessage(), e);
            }
        }
        else {
            String className;
            try {
                className = GrailsResourceUtils.getClassName(r.getFile().getAbsolutePath());
            }
            catch (IOException e) {
                throw new PluginException("Cannot find plugin class from resource: [" + r.getFilename() + "]", e);
            }
            try {
                pluginClass = Class.forName(className, true, cl);
            }
            catch (ClassNotFoundException e) {
                throw new PluginException("Cannot find plugin class [" + className + "] resource: [" + r.getFilename() + "]", e);
            }
        }
        return pluginClass;
    }

    /**
     * Attempts to load a plugin based on its dependencies. If a plugin's dependencies cannot be resolved
     * it will add it to the list of dependencies to be resolved later.
     *
     * @param plugin The plugin
     */
    private void attemptPluginLoad(GrailsPlugin plugin) {
        if (areDependenciesResolved(plugin) && areNoneToLoadBefore(plugin)) {
            registerPlugin(plugin);
        }
        else {
            this.delayedLoadPlugins.add(plugin);
        }
    }

    private void registerPlugin(GrailsPlugin plugin) {
        if (!canRegisterPlugin(plugin)) {
            if (logger.isInfoEnabled()) {
                logger.info("Grails plugin " + plugin + " is disabled and was not loaded");
            }
            return;
        }

        if (logger.isInfoEnabled()) {
            logger.info("Grails plugin [" + plugin.getName() + "] with version [" + plugin.getVersion() + "] loaded successfully");
        }

        if (plugin instanceof ParentApplicationContextAware) {
            ((ParentApplicationContextAware) plugin).setParentApplicationContext(this.parentCtx);
        }
        plugin.setManager(this);
        String[] evictionNames = plugin.getEvictionNames();
        if (evictionNames.length > 0) {
            this.delayedEvictions.put(plugin, evictionNames);
        }

        String[] observedPlugins = plugin.getObservedPluginNames();
        for (String observedPlugin : observedPlugins) {
            Set<GrailsPlugin> observers = this.pluginToObserverMap.computeIfAbsent(observedPlugin, k -> new HashSet<>());
            observers.add(plugin);
        }
        this.pluginList.add(plugin);
        this.plugins.put(plugin.getName(), plugin);
        this.classNameToPluginMap.put(plugin.getPluginClass().getName(), plugin);
    }

    protected boolean canRegisterPlugin(GrailsPlugin plugin) {
        Environment environment = Environment.getCurrent();
        return plugin.isEnabled() && plugin.supportsEnvironment(environment);
    }

    protected void evictPlugin(GrailsPlugin evictor, String evicteeName) {
        GrailsPlugin pluginToEvict = this.plugins.get(evicteeName);
        if (pluginToEvict != null) {
            this.pluginList.remove(pluginToEvict);
            this.plugins.remove(pluginToEvict.getName());

            if (logger.isInfoEnabled()) {
                logger.info("Grails plugin " + pluginToEvict + " was evicted by " + evictor);
            }
        }
    }

    private boolean hasGrailsPlugin(String name, String version) {
        return getGrailsPlugin(name, version) != null;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        for (GrailsPlugin plugin : this.pluginList) {
            plugin.setApplicationContext(applicationContext);
        }
    }

    public void setParentApplicationContext(ApplicationContext parent) {
        this.parentCtx = parent;
    }

    /**
     * @deprecated Replaced by agent-based reloading, will be removed in a future version of Grails
     */
    @Deprecated
    public void checkForChanges() {
        // do nothing
    }

    public void reloadPlugin(GrailsPlugin plugin) {
        plugin.doArtefactConfiguration();

        RuntimeSpringConfiguration springConfig = new DefaultRuntimeSpringConfiguration(this.parentCtx);

        doRuntimeConfiguration(plugin.getName(), springConfig);
        springConfig.registerBeansWithContext((GenericApplicationContext) this.applicationContext);

        plugin.doWithApplicationContext(this.applicationContext);
        plugin.doWithDynamicMethods(this.applicationContext);
    }

    @Override
    public void setApplication(GrailsApplication application) {
        Assert.notNull(application, "Argument [application] cannot be null");
        this.application = application;
        for (GrailsPlugin plugin : this.pluginList) {
            plugin.setApplication(application);
        }
    }

    @Override
    public void doDynamicMethods() {
        checkInitialised();
        // remove common meta classes just to be sure
        MetaClassRegistry registry = GroovySystem.getMetaClassRegistry();
        for (Class<?> COMMON_CLASS : COMMON_CLASSES) {
            registry.removeMetaClass(COMMON_CLASS);
        }
        for (GrailsPlugin plugin : this.pluginList) {
            if (plugin.supportsCurrentScopeAndEnvironment()) {
                try {
                    plugin.doWithDynamicMethods(this.applicationContext);
                }
                catch (Throwable t) {
                    throw new GrailsConfigurationException("Error configuring dynamic methods for plugin " + plugin + ": " + t.getMessage(), t);
                }
            }
        }
    }

    private PluginFilter getPluginFilter() {
        if (this.pluginFilter == null) {
            this.pluginFilter = new IdentityPluginFilter();
        }
        return this.pluginFilter;
    }

    public void setPluginFilter(PluginFilter pluginFilter) {
        this.pluginFilter = pluginFilter;
    }

    public List<GrailsPlugin> getPluginList() {
        return Collections.unmodifiableList(this.pluginList);
    }

}