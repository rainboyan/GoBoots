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
package grails.dev.commands

import groovy.transform.CompileStatic

import org.grails.core.io.support.GrailsFactoriesLoader

/**
 * A registry of {@grails.dev.commands.ApplicationContextCommand} instances
 *
 * @author Graeme Rocher
 * @since 3.0
 */
@CompileStatic
class ApplicationContextCommandRegistry {

    private static final Map<String, ApplicationCommand> COMMANDS = [:]

    static {
        def registeredCommands = GrailsFactoriesLoader.loadFactories(ApplicationCommand)

        def iterator = registeredCommands.iterator()
        while (iterator.hasNext()) {
            def cmd = iterator.next()
            COMMANDS[cmd.name] = cmd
        }
    }

    static Collection<ApplicationCommand> findCommands() {
        COMMANDS.values()
    }

    static ApplicationCommand findCommand(String name) {
        COMMANDS[name]
    }

}