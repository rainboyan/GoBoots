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
package org.grails.web.converters.marshaller.json;

import java.text.Format;
import java.time.LocalDate;
import java.util.Locale;
import java.util.TimeZone;

import org.apache.commons.lang3.time.FastDateFormat;

import grails.converters.JSON;

import org.grails.web.converters.exceptions.ConverterException;
import org.grails.web.converters.marshaller.ObjectMarshaller;
import org.grails.web.json.JSONException;

/**
 * JSON ObjectMarshaller which converts a Date Object, conforming to the ECMA-Script-Specification
 * Draft, to a String value.
 *
 * @author Siegfried Puchbauer
 * @since 1.1
 */
public class LocalDateMarshaller implements ObjectMarshaller<JSON> {

    private final Format formatter;

    /**
     * Constructor with a custom formatter.
     * @param formatter the formatter
     */
    public LocalDateMarshaller(Format formatter) {
        this.formatter = formatter;
    }

    /**
     * Default constructor.
     */
    public LocalDateMarshaller() {
        this(FastDateFormat.getInstance("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("GMT"), Locale.US));
    }

    public boolean supports(Object object) {
        return object instanceof LocalDate;
    }

    public void marshalObject(Object object, JSON converter) throws ConverterException {
        try {
            converter.getWriter().value(this.formatter.format(object));
        }
        catch (JSONException e) {
            throw new ConverterException(e);
        }
    }

}