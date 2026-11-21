package com.proofix.runner.source;

import java.util.List;
import java.util.Map;

/**
 * What one parsed Java source contributes, copied out of the syntax tree (which is then dropped).
 * Holds names, lines and evaluable expressions only; never file bytes.
 *
 * @param packageName       "" for the default package
 * @param imports           simple name -> fully qualified name (single-type imports)
 * @param wildcardImports   packages (or classes, for nested types) imported with ".*"
 * @param staticImports     member name -> fully qualified declaring class (single static imports)
 * @param staticWildcards   classes imported with "import static ...*"
 * @param types             canonical names of every (nested) type declared in the file
 */
public record SourceSummary(String packageName, Map<String, String> imports, List<String> wildcardImports,
                            Map<String, String> staticImports, List<String> staticWildcards, List<String> types,
                            List<Constant> constants, List<Controller> controllers, List<Integer> interfaceMappingLines,
                            List<BootApp> bootApps, List<EnvUse> envUses) {

    public SourceSummary {
        imports = Map.copyOf(imports);
        wildcardImports = List.copyOf(wildcardImports);
        staticImports = Map.copyOf(staticImports);
        staticWildcards = List.copyOf(staticWildcards);
        types = List.copyOf(types);
        constants = List.copyOf(constants);
        controllers = List.copyOf(controllers);
        interfaceMappingLines = List.copyOf(interfaceMappingLines);
        bootApps = List.copyOf(bootApps);
        envUses = List.copyOf(envUses);
    }

    /** A String constant: static final in a class, or any String field of an interface. */
    public record Constant(String owner, String name, Expr init, int line) {}

    /**
     * A mapping annotation. {@code fixedMethod} is set for @GetMapping etc. and null for
     * @RequestMapping, whose {@code methods} lists the method= expressions. {@code paths} is empty
     * when no path is given. {@code pathAmbiguous}: both value= and path= are set and differ.
     */
    public record Mapping(String fixedMethod, List<Expr> paths, List<Expr> methods, boolean pathAmbiguous, int line) {
        public Mapping {
            paths = List.copyOf(paths);
            methods = List.copyOf(methods);
        }
    }

    public record Handler(String method, Mapping mapping) {}

    /**
     * A @RestController/@Controller class. {@code scopes} are the canonical names of the class and
     * its enclosing classes, innermost first (for simple-name constant lookup).
     */
    public record Controller(String canonicalName, List<String> scopes, int annotationLine, Mapping classMapping,
                             List<Handler> handlers, boolean hasSupertype) {
        public Controller {
            scopes = List.copyOf(scopes);
            handlers = List.copyOf(handlers);
        }
    }

    /** A @SpringBootApplication class; hasMain = declares public static void main(String[]). */
    public record BootApp(String canonicalName, int annotationLine, boolean hasMain) {}

    /** kind VALUE (@Value text, raw) or GETENV (literal name); text null = computed name. */
    public record EnvUse(Kind kind, String text, int line) {
        public enum Kind { VALUE, GETENV }
    }
}
