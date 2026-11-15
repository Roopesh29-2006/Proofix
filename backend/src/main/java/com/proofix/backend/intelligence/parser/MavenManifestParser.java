package com.proofix.backend.intelligence.parser;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import com.proofix.backend.intelligence.model.DependencyFinding;
import com.proofix.backend.intelligence.model.DependencyFinding.VersionStatus;
import com.proofix.backend.intelligence.model.DependencyManifestParseResult;
import com.proofix.backend.intelligence.model.DependencyManifestParseResult.MavenArtifact;
import com.proofix.backend.intelligence.model.EvidenceReference;
import com.proofix.backend.intelligence.model.ParseStatus;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

@Component
public class MavenManifestParser implements DependencyManifestParser {

    private static final String POM_FILE_NAME = "pom.xml";
    private static final String MAVEN_NAMESPACE =
            "http://maven.apache.org/POM/4.0.0";
    private static final String MAVEN_ECOSYSTEM = "maven";
    private static final String SPRING_BOOT_GROUP =
            "org.springframework.boot";
    private static final List<String> JAVA_PROPERTY_NAMES = List.of(
            "java.version",
            "maven.compiler.source",
            "maven.compiler.target",
            "maven.compiler.release");

    @Override
    public boolean supports(String repositoryRelativePath) {
        return repositoryRelativePath != null
                && POM_FILE_NAME.equalsIgnoreCase(
                        fileName(repositoryRelativePath));
    }

    @Override
    public DependencyManifestParseResult parse(
            String repositoryRelativePath,
            String persistedContent) {

        if (!supports(repositoryRelativePath)) {
            return result(
                    repositoryRelativePath,
                    ParseStatus.UNSUPPORTED_PATH,
                    List.of(),
                    Map.of(),
                    Map.of(),
                    null,
                    null,
                    null,
                    List.of("Path is not a Maven pom.xml file."));
        }
        if (persistedContent == null) {
            return result(
                    repositoryRelativePath,
                    ParseStatus.NULL_CONTENT,
                    List.of(),
                    Map.of(),
                    Map.of(),
                    null,
                    null,
                    null,
                    List.of("Persisted file content is unavailable."));
        }
        if (persistedContent.isBlank()) {
            return result(
                    repositoryRelativePath,
                    ParseStatus.EMPTY_CONTENT,
                    List.of(),
                    Map.of(),
                    Map.of(),
                    null,
                    null,
                    null,
                    List.of("Persisted file content is empty."));
        }

        List<String> warnings = new ArrayList<>();
        final Document document;
        try {
            DocumentBuilder builder = secureDocumentBuilder();
            document = builder.parse(new InputSource(new ByteArrayInputStream(
                    persistedContent.getBytes(StandardCharsets.UTF_8))));
        } catch (ParserConfigurationException exception) {
            return result(
                    repositoryRelativePath,
                    ParseStatus.SECURITY_CONFIGURATION_ERROR,
                    List.of(),
                    Map.of(),
                    Map.of(),
                    null,
                    null,
                    null,
                    List.of("Secure XML parser configuration failed."));
        } catch (SAXException | java.io.IOException exception) {
            return result(
                    repositoryRelativePath,
                    ParseStatus.MALFORMED_XML,
                    List.of(),
                    Map.of(),
                    Map.of(),
                    null,
                    null,
                    null,
                    List.of("Maven POM XML is malformed or unreadable."));
        } catch (UnsupportedOperationException exception) {
            return result(
                    repositoryRelativePath,
                    ParseStatus.SECURITY_CONFIGURATION_ERROR,
                    List.of(),
                    Map.of(),
                    Map.of(),
                    null,
                    null,
                    null,
                    List.of("Required XML security operation is unsupported."));
        }

        Element project = document.getDocumentElement();
        if (project == null || !"project".equals(localName(project))) {
            return result(
                    repositoryRelativePath,
                    ParseStatus.MALFORMED_XML,
                    List.of(),
                    Map.of(),
                    Map.of(),
                    null,
                    null,
                    null,
                    List.of("Maven POM root element must be project."));
        }
        if (!hasOnlyMavenNamespace(document)) {
            return result(
                    repositoryRelativePath,
                    ParseStatus.UNSUPPORTED_NAMESPACE,
                    List.of(),
                    Map.of(),
                    Map.of(),
                    null,
                    null,
                    null,
                    List.of(
                            "Maven POM must use the standard Maven XML namespace."));
        }

        Map<String, String> properties = parseProperties(
                directChild(project, "properties"));
        Map<String, String> javaCandidates = javaVersionCandidates(
                project,
                properties,
                warnings);
        MavenArtifact springBootParent = parseSpringBootParent(project);
        MavenArtifact springBootPlugin = parseSpringBootPlugin(project);
        List<DependencyFinding> dependencies = parseDependencies(
                project,
                repositoryRelativePath,
                warnings);

        return result(
                repositoryRelativePath,
                ParseStatus.SUCCESS,
                dependencies,
                properties,
                javaCandidates,
                firstJavaVersion(javaCandidates),
                springBootParent,
                springBootPlugin,
                warnings);
    }

    private static List<DependencyFinding> parseDependencies(
            Element project,
            String filePath,
            List<String> warnings) {

        Element dependenciesElement = directChild(project, "dependencies");
        if (dependenciesElement == null) {
            return List.of();
        }

        List<DependencyFinding> dependencies = new ArrayList<>();
        for (Element dependency : directChildren(
                dependenciesElement,
                "dependency")) {
            String groupId = text(directChild(dependency, "groupId"));
            String artifactId = text(directChild(dependency, "artifactId"));
            if (isBlank(groupId) || isBlank(artifactId)) {
                warnings.add(
                        "Dependency without groupId or artifactId was skipped.");
                continue;
            }

            String version = text(directChild(dependency, "version"));
            dependencies.add(new DependencyFinding(
                    MAVEN_ECOSYSTEM,
                    groupId,
                    artifactId,
                    version,
                    versionStatus(version),
                    text(directChild(dependency, "scope")),
                    booleanValue(directChild(dependency, "optional")),
                    text(directChild(dependency, "classifier")),
                    text(directChild(dependency, "type")),
                    new EvidenceReference(filePath, null, null, null, null)));
        }
        return List.copyOf(dependencies);
    }

    private static Map<String, String> parseProperties(Element propertiesElement) {
        if (propertiesElement == null) {
            return Map.of();
        }

        Map<String, String> properties = new LinkedHashMap<>();
        for (Element property : childElements(propertiesElement)) {
            properties.put(localName(property), text(property));
        }
        return Map.copyOf(properties);
    }

    private static Map<String, String> javaVersionCandidates(
            Element project,
            Map<String, String> properties,
            List<String> warnings) {

        Map<String, String> candidates = new LinkedHashMap<>();
        for (String propertyName : JAVA_PROPERTY_NAMES) {
            String value = properties.get(propertyName);
            if (!isBlank(value)) {
                candidates.put(propertyName, value);
            }
        }

        Element build = directChild(project, "build");
        Element plugins = build == null ? null : directChild(build, "plugins");
        if (plugins != null) {
            for (Element plugin : directChildren(plugins, "plugin")) {
                if (!"maven-compiler-plugin".equals(
                        text(directChild(plugin, "artifactId")))) {
                    continue;
                }
                Element configuration = directChild(plugin, "configuration");
                if (configuration == null) {
                    continue;
                }
                for (String key : List.of("release", "source", "target")) {
                    String value = text(directChild(configuration, key));
                    if (!isBlank(value)) {
                        candidates.put("compiler.plugin." + key, value);
                    }
                }
            }
        }

        if (hasConflictingJavaValues(candidates)) {
            warnings.add("Multiple Java/compiler version values were declared.");
        }
        return Map.copyOf(candidates);
    }

    private static MavenArtifact parseSpringBootParent(Element project) {
        Element parent = directChild(project, "parent");
        if (parent == null
                || !SPRING_BOOT_GROUP.equals(
                        text(directChild(parent, "groupId")))
                || !"spring-boot-starter-parent".equals(
                        text(directChild(parent, "artifactId")))) {
            return null;
        }
        return artifact(parent);
    }

    private static MavenArtifact parseSpringBootPlugin(Element project) {
        Element build = directChild(project, "build");
        Element plugins = build == null ? null : directChild(build, "plugins");
        if (plugins == null) {
            return null;
        }
        for (Element plugin : directChildren(plugins, "plugin")) {
            String groupId = text(directChild(plugin, "groupId"));
            if ((groupId == null || SPRING_BOOT_GROUP.equals(groupId))
                    && "spring-boot-maven-plugin".equals(
                        text(directChild(plugin, "artifactId")))) {
                return artifact(plugin);
            }
        }
        return null;
    }

    private static MavenArtifact artifact(Element element) {
        String version = text(directChild(element, "version"));
        return new MavenArtifact(
                text(directChild(element, "groupId")),
                text(directChild(element, "artifactId")),
                version,
                versionStatus(version));
    }

    private static DocumentBuilder secureDocumentBuilder()
            throws ParserConfigurationException {
        DocumentBuilderFactory factory =
                DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        setFeature(
                factory,
                "http://apache.org/xml/features/disallow-doctype-decl",
                true);
        setFeature(
                factory,
                "http://xml.org/sax/features/external-general-entities",
                false);
        setFeature(
                factory,
                "http://xml.org/sax/features/external-parameter-entities",
                false);
        setFeature(
                factory,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                false);
        setFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setAttribute(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setAttribute(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
            @Override
            public void warning(org.xml.sax.SAXParseException exception) {
                // Expected parser diagnostics are represented by the parse result.
            }

            @Override
            public void error(org.xml.sax.SAXParseException exception)
                    throws SAXException {
                throw exception;
            }

            @Override
            public void fatalError(org.xml.sax.SAXParseException exception)
                    throws SAXException {
                throw exception;
            }
        });
        builder.setEntityResolver((publicId, systemId) ->
                new InputSource(new ByteArrayInputStream(new byte[0])));
        return builder;
    }

    private static void setFeature(
            DocumentBuilderFactory factory,
            String feature,
            boolean value) throws ParserConfigurationException {
        factory.setFeature(feature, value);
    }

    private static void setAttribute(
            DocumentBuilderFactory factory,
            String name,
            String value) throws ParserConfigurationException {
        try {
            factory.setAttribute(name, value);
        } catch (IllegalArgumentException exception) {
            throw new ParserConfigurationException(
                    "Required XML security attribute is unsupported: " + name);
        }
    }

    private static DependencyManifestParseResult result(
            String filePath,
            ParseStatus status,
            List<DependencyFinding> dependencies,
            Map<String, String> properties,
            Map<String, String> javaCandidates,
            String selectedJavaVersion,
            MavenArtifact springBootParent,
            MavenArtifact springBootPlugin,
            List<String> warnings) {
        return new DependencyManifestParseResult(
                filePath,
                status,
                dependencies,
                properties,
                javaCandidates,
                selectedJavaVersion,
                springBootParent,
                springBootPlugin,
                warnings);
    }

    private static String firstJavaVersion(Map<String, String> candidates) {
        for (String key : List.of(
                "maven.compiler.release",
                "maven.compiler.source",
                "maven.compiler.target",
                "java.version",
                "compiler.plugin.release",
                "compiler.plugin.source",
                "compiler.plugin.target")) {
            String value = candidates.get(key);
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasConflictingJavaValues(
            Map<String, String> candidates) {
        return candidates.values().stream()
                .filter(value -> !isBlank(value))
                .map(String::trim)
                .distinct()
                .count() > 1;
    }

    private static VersionStatus versionStatus(String version) {
        if (isBlank(version)) {
            return VersionStatus.MISSING;
        }
        if (version.matches("\\$\\{[^{}]+\\}")) {
            return VersionStatus.PROPERTY_REFERENCE;
        }
        if (version.contains("${")
                || version.contains("{")
                || version.contains("}")) {
            return VersionStatus.UNRESOLVED;
        }
        return VersionStatus.EXPLICIT;
    }

    private static boolean hasOnlyMavenNamespace(Document document) {
        return hasOnlyMavenNamespace(document.getDocumentElement());
    }

    private static boolean hasOnlyMavenNamespace(Element element) {
        if (!MAVEN_NAMESPACE.equals(element.getNamespaceURI())) {
            return false;
        }
        for (Node node = element.getFirstChild();
                node != null;
                node = node.getNextSibling()) {
            if (node instanceof Element child
                    && !hasOnlyMavenNamespace(child)) {
                return false;
            }
        }
        return true;
    }

    private static Boolean booleanValue(Element element) {
        String value = text(element);
        if (value == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return null;
    }

    private static String fileName(String path) {
        String normalized = path.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1)
                .toLowerCase(Locale.ROOT);
    }

    private static Element directChild(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Element child : childElements(parent)) {
            if (name.equals(localName(child))) {
                return child;
            }
        }
        return null;
    }

    private static List<Element> directChildren(Element parent, String name) {
        return childElements(parent).stream()
                .filter(child -> name.equals(localName(child)))
                .toList();
    }

    private static List<Element> childElements(Element parent) {
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild();
                node != null;
                node = node.getNextSibling()) {
            if (node instanceof Element element) {
                children.add(element);
            }
        }
        return children;
    }

    private static String localName(Element element) {
        String localName = element.getLocalName();
        return localName == null ? element.getNodeName() : localName;
    }

    private static String text(Element element) {
        if (element == null) {
            return null;
        }
        String value = element.getTextContent();
        return value == null ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
