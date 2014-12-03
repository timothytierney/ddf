/**
 * Copyright (c) Codice Foundation
 * 
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 * 
 **/
package ddf.catalog.transformer.input.tika;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Hashtable;
import java.util.List;
import java.util.SortedSet;

import org.apache.commons.lang.StringUtils;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.ToXMLContentHandler;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import ddf.catalog.data.Metacard;
import ddf.catalog.data.impl.AttributeImpl;
import ddf.catalog.data.impl.BasicTypes;
import ddf.catalog.data.impl.MetacardImpl;
import ddf.catalog.transform.CatalogTransformerException;
import ddf.catalog.transform.InputTransformer;

public class TikaInputTransformer implements InputTransformer {
    private static final Logger LOGGER = LoggerFactory.getLogger(TikaInputTransformer.class);

    public TikaInputTransformer(BundleContext bundleContext) {
        if(bundleContext == null) {
            LOGGER.error("Bundle context is null. Unable to register {} as an osgi service.", TikaInputTransformer.class.getSimpleName());
            return;
        }
        
        registerService(bundleContext);
    }
    
    @Override
    public Metacard transform(InputStream input) throws IOException, CatalogTransformerException {
        return transform(input, null);
    }

	@Override
	public Metacard transform(InputStream input, String uri)
			throws IOException, CatalogTransformerException {
		
		LOGGER.debug("Transforming input stream using Tika.");

		if (input == null) {
			throw new CatalogTransformerException(
					"Cannot transform null input.");
		}
	
		Parser parser = new AutoDetectParser();
		Metadata metadata = new Metadata();
		ToXMLContentHandler xmlHandler = new ToXMLContentHandler();
		
		try {
			parser.parse(input, xmlHandler, metadata, new ParseContext());
        
		} catch (SAXException e) {
            throw new CatalogTransformerException("SAX exception processing input.", e);
        } catch (TikaException e) {
            throw new CatalogTransformerException("Tika exception processing input.", e);
        }
		
		Metacard metacard = createMetacard(metadata, uri, xmlHandler.toString());
		
		LOGGER.debug("Finished transforming input stream using Tika.");
		return metacard;
	}
    
	private Metacard createMetacard(Metadata metadata, String uri, String metacardMetadata) {
		Metacard metacard = new MetacardImpl(BasicTypes.BASIC_METACARD);
		
		String contentType = metadata.get(Metadata.CONTENT_TYPE);
		if(StringUtils.isNotBlank(contentType)) {
			metacard.setAttribute(new AttributeImpl(Metacard.CONTENT_TYPE, contentType));
		}
		
		String title = metadata.get(TikaCoreProperties.TITLE);

		if (StringUtils.isNotBlank(title)) {
			metacard.setAttribute(new AttributeImpl(Metacard.TITLE, title));
		}
		
		String createdDateStr = metadata.get(TikaCoreProperties.CREATED);
		Date createdDate = convertDate(createdDateStr);
		if (createdDate != null) {
			metacard.setAttribute(new AttributeImpl(Metacard.CREATED,
					createdDate));
		}

		String modifiedDateStr = metadata.get(TikaCoreProperties.MODIFIED);
		Date modifiedDate = convertDate(modifiedDateStr);
		if (modifiedDate != null) {
			metacard.setAttribute(new AttributeImpl(Metacard.MODIFIED,
					modifiedDate));
		}
		
		if (StringUtils.isNotBlank(uri)) {
			metacard.setAttribute(new AttributeImpl(Metacard.RESOURCE_URI, URI.create(uri)));
		} else {
			metacard.setAttribute(new AttributeImpl(Metacard.RESOURCE_URI, null));
		}
		
		if(StringUtils.isNotBlank(metacardMetadata)) {
			metacard.setAttribute(new AttributeImpl(Metacard.METADATA, metacardMetadata));
		}
		
		String lat = metadata.get(Metadata.LATITUDE);
		String lon = metadata.get(Metadata.LONGITUDE);
		String wkt = toWkt(lon, lat);
		
		if(StringUtils.isNotBlank(wkt)) {
			metacard.setAttribute(new AttributeImpl(Metacard.GEOGRAPHY, wkt));
		}
		
		return metacard;
	}
    
    private String toWkt(String lon, String lat) {
    	
    	if(StringUtils.isBlank(lon) || StringUtils.isBlank(lat)) {
    		return null;
    	}
    	
    	StringBuilder wkt = new StringBuilder();
    	wkt.append("POINT(");
    	wkt.append(lon);
    	wkt.append(" ");
    	wkt.append(lat);
    	wkt.append(")");
    	LOGGER.debug("wkt: {} ", wkt.toString());
    	return wkt.toString();
    }
    
	private Date convertDate(String dateStr) {
		if (StringUtils.isBlank(dateStr)) {
			return null;
		}
			
		Date date = javax.xml.bind.DatatypeConverter.parseDateTime(dateStr)
				.getTime();
		return date;
	}
	
    private void registerService(BundleContext bundleContext) {
        LOGGER.debug("Registering {} as an osgi service.",
                TikaInputTransformer.class.getSimpleName());
        bundleContext.registerService(ddf.catalog.transform.InputTransformer.class, this,
                getServiceProperties());
    }

    private Hashtable<String, Object> getServiceProperties() {
        Hashtable<String, Object> properties = new Hashtable<String, Object>();
        properties.put(ddf.catalog.Constants.SERVICE_ID, "tika");
        properties.put(ddf.catalog.Constants.SERVICE_TITLE, "Tika Input Transformer");
        properties.put(ddf.catalog.Constants.SERVICE_DESCRIPTION, "Tika Input Transformer");
        properties.put("mime-type", getSupportedMimeTypes());
        // Set service ranking to be -1 so that this default transformer is used after any other
        // custom, more specific transformers have been used.
        properties.put(Constants.SERVICE_RANKING, -1);

        return properties;
    }

    private List<String> getSupportedMimeTypes() {
        SortedSet<MediaType> mediaTypes = MediaTypeRegistry.getDefaultRegistry().getTypes();
        List<String> mimeTypes = new ArrayList<String>(mediaTypes.size());

        if (mediaTypes != null) {
            for (MediaType mediaType : mediaTypes) {
                String mimeType = mediaType.getType() + "/" + mediaType.getSubtype();
                mimeTypes.add(mimeType);
            }
        }

        LOGGER.debug("supported mime types: {}", mimeTypes);
        return mimeTypes;
    }
}