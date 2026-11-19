package com.proofix.runner.config.xml;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * [SEC] Hardened XML parsing with line numbers. The factory settings are the ones used by the
 * backend's MavenManifestParser.secureDocumentBuilder(), applied to SAX instead of DOM (DOM has no
 * line numbers): DOCTYPE is rejected outright, external entities and external DTDs are off, secure
 * processing is on, external DTD/schema access is "", XInclude is off, the entity resolver always
 * returns empty input and every parser error is fatal.
 *
 * <p>Parser exception messages can quote file content, so none is ever kept: every failure becomes
 * an {@link XmlException} with a fixed message, no cause and no stack trace.
 */
public final class SafeXml {
    private SafeXml() {}

    public static final int MAX_DEPTH = 64;
    public static final int MAX_ELEMENTS = 100_000;

    public static final String UNPARSEABLE = "XML could not be parsed (malformed or contains a DOCTYPE)";
    public static final String LIMIT = "XML exceeds the element depth or count limit";

    /** A parse failure. The message is one of the fixed constants above; there is never a cause. */
    public static final class XmlException extends Exception {
        XmlException(String fixedMessage) {
            super(fixedMessage, null, false, false);
        }
    }

    /** Thrown from the handler; carries no content. */
    private static final class LimitHit extends SAXException {
        LimitHit() {
            super(LIMIT);
        }
    }

    public static XmlNode parse(byte[] bytes) throws XmlException {
        TreeHandler handler = new TreeHandler();
        try {
            XMLReader reader = newParser().getXMLReader();
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.setEntityResolver((publicId, systemId) -> new InputSource(new ByteArrayInputStream(new byte[0])));
            reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
        } catch (LimitHit e) {
            throw new XmlException(LIMIT);
        } catch (SAXException | IOException | RuntimeException e) {
            throw new XmlException(UNPARSEABLE);
        }
        if (handler.root == null) throw new XmlException(UNPARSEABLE);
        return handler.root;
    }

    private static SAXParser newParser() throws SAXException {
        try {
            SAXParserFactory f = SAXParserFactory.newInstance();
            f.setNamespaceAware(true);
            f.setValidating(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            SAXParser p = f.newSAXParser();
            p.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            p.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return p;
        } catch (ParserConfigurationException e) {
            // Fail closed: never fall back to a parser without these settings.
            throw new IllegalStateException("hardened XML parser unavailable");
        }
    }

    private static final class TreeHandler extends DefaultHandler {
        private final Deque<XmlNode> stack = new ArrayDeque<>();
        private Locator locator;
        private XmlNode root;
        private int elements;

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            if (++elements > MAX_ELEMENTS || stack.size() >= MAX_DEPTH) throw new LimitHit();
            String name = localName == null || localName.isEmpty() ? qName : localName;
            XmlNode node = new XmlNode(name, uri, locator == null ? 1 : locator.getLineNumber());
            if (stack.isEmpty()) {
                root = node;
            } else {
                stack.peek().add(node);
            }
            stack.push(node);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            stack.pop().finish();
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (!stack.isEmpty()) stack.peek().appendText(ch, start, length);
        }

        @Override
        public void warning(SAXParseException e) {
            // ignored: a warning never changes the parse result
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    }
}
