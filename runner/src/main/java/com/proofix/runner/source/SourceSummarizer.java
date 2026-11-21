package com.proofix.runner.source;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.Type;
import com.proofix.runner.source.SourceSummary.BootApp;
import com.proofix.runner.source.SourceSummary.Constant;
import com.proofix.runner.source.SourceSummary.Controller;
import com.proofix.runner.source.SourceSummary.EnvUse;
import com.proofix.runner.source.SourceSummary.Handler;
import com.proofix.runner.source.SourceSummary.Mapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Copies what the endpoint, entry-point and env extractors need out of one compilation unit.
 * Annotations are matched by simple or fully qualified name; nothing is resolved by type.
 */
public final class SourceSummarizer {
    private SourceSummarizer() {}

    private static final String WEB = "org.springframework.web.bind.annotation.";

    /** Mapping annotation simple name -> fixed HTTP method (null for @RequestMapping). */
    static final Map<String, String> MAPPINGS = new HashMap<>();

    static {
        MAPPINGS.put("GetMapping", "GET");
        MAPPINGS.put("PostMapping", "POST");
        MAPPINGS.put("PutMapping", "PUT");
        MAPPINGS.put("DeleteMapping", "DELETE");
        MAPPINGS.put("PatchMapping", "PATCH");
        MAPPINGS.put("RequestMapping", null);
    }

    private static final String[][] CONTROLLERS = {
            {"RestController", WEB + "RestController"},
            {"Controller", "org.springframework.stereotype.Controller"}};
    private static final String[] BOOT_APP = {"SpringBootApplication",
            "org.springframework.boot.autoconfigure.SpringBootApplication"};
    private static final String[] VALUE = {"Value", "org.springframework.beans.factory.annotation.Value"};
    private static final String SYSTEM = "java.lang.System";

    private static final class State {
        String pkg = "";
        final Map<String, String> imports = new HashMap<>();
        final List<String> wildcards = new ArrayList<>();
        final Map<String, String> staticImports = new HashMap<>();
        final List<String> staticWildcards = new ArrayList<>();
        final List<String> types = new ArrayList<>();
        final List<Constant> constants = new ArrayList<>();
        final List<Controller> controllers = new ArrayList<>();
        final List<Integer> interfaceMappings = new ArrayList<>();
        final List<BootApp> bootApps = new ArrayList<>();
        final List<EnvUse> env = new ArrayList<>();
    }

    public static SourceSummary summarize(CompilationUnit cu) {
        State s = new State();
        cu.getPackageDeclaration().ifPresent(p -> s.pkg = p.getNameAsString());
        for (ImportDeclaration i : cu.getImports()) {
            String name = i.getNameAsString();
            if (i.isStatic()) {
                if (i.isAsterisk()) {
                    s.staticWildcards.add(name);
                } else {
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) s.staticImports.put(name.substring(dot + 1), name.substring(0, dot));
                }
            } else if (i.isAsterisk()) {
                s.wildcards.add(name);
            } else {
                s.imports.put(name.substring(name.lastIndexOf('.') + 1), name);
            }
        }
        for (TypeDeclaration<?> t : cu.getTypes()) type(s, t, null, List.of());
        env(s, cu);
        return new SourceSummary(s.pkg, s.imports, s.wildcards, s.staticImports, s.staticWildcards, s.types,
                s.constants, s.controllers, s.interfaceMappings, s.bootApps, s.env);
    }

    private static void type(State s, TypeDeclaration<?> t, String outer, List<String> outerScopes) {
        String name = t.getNameAsString();
        String canonical = outer != null ? outer + "." + name : (s.pkg.isEmpty() ? name : s.pkg + "." + name);
        List<String> scopes = new ArrayList<>();
        scopes.add(canonical);
        scopes.addAll(outerScopes);
        s.types.add(canonical);
        boolean isInterface = t instanceof ClassOrInterfaceDeclaration c && c.isInterface();

        for (FieldDeclaration f : t.getFields()) {
            if (!isInterface && !(f.isStatic() && f.isFinal())) continue;
            for (VariableDeclarator v : f.getVariables()) {
                if (!isString(v.getType()) || v.getInitializer().isEmpty()) continue;
                s.constants.add(new Constant(canonical, v.getNameAsString(), Expr.of(v.getInitializer().get()), line(v)));
            }
        }

        if (isInterface) {
            Integer first = null;
            for (AnnotationExpr a : t.getAnnotations()) if (isMapping(a)) first = min(first, line(a));
            for (MethodDeclaration m : t.getMethods()) {
                for (AnnotationExpr a : m.getAnnotations()) if (isMapping(a)) first = min(first, line(a));
            }
            if (first != null) s.interfaceMappings.add(first);
        }

        if (t instanceof ClassOrInterfaceDeclaration c && !c.isInterface()) {
            Optional<AnnotationExpr> ctl = t.getAnnotations().stream()
                    .filter(a -> is(a, CONTROLLERS[0]) || is(a, CONTROLLERS[1])).findFirst();
            if (ctl.isPresent()) {
                Mapping classMapping = t.getAnnotations().stream()
                        .filter(a -> is(a, "RequestMapping", WEB + "RequestMapping"))
                        .findFirst().map(a -> mapping(a, null)).orElse(null);
                List<Handler> handlers = new ArrayList<>();
                for (MethodDeclaration m : t.getMethods()) {
                    for (AnnotationExpr a : m.getAnnotations()) {
                        String simple = mappingName(a);
                        if (simple != null) handlers.add(new Handler(m.getNameAsString(), mapping(a, MAPPINGS.get(simple))));
                    }
                }
                boolean sup = !c.getExtendedTypes().isEmpty() || !c.getImplementedTypes().isEmpty();
                s.controllers.add(new Controller(canonical, scopes, line(ctl.get()), classMapping, handlers, sup));
            }
        }

        for (AnnotationExpr a : t.getAnnotations()) {
            if (is(a, BOOT_APP)) {
                boolean main = t.getMethods().stream().anyMatch(SourceSummarizer::isMain);
                s.bootApps.add(new BootApp(canonical, line(a), main));
                break;
            }
        }

        for (BodyDeclaration<?> m : t.getMembers()) {
            if (m instanceof TypeDeclaration<?> td) type(s, td, canonical, scopes);
        }
    }

    private static Mapping mapping(AnnotationExpr a, String fixed) {
        List<Expr> value = null;
        List<Expr> path = null;
        List<Expr> methods = new ArrayList<>();
        if (a instanceof SingleMemberAnnotationExpr sm) {
            value = list(sm.getMemberValue());
        } else if (a instanceof NormalAnnotationExpr n) {
            for (MemberValuePair p : n.getPairs()) {
                switch (p.getNameAsString()) {
                    case "value" -> value = list(p.getValue());
                    case "path" -> path = list(p.getValue());
                    case "method" -> methods = list(p.getValue());
                    default -> { }
                }
            }
        }
        boolean ambiguous = value != null && path != null && !value.equals(path);
        List<Expr> paths = path != null ? path : value != null ? value : List.of();
        return new Mapping(fixed, paths, fixed == null ? methods : List.of(), ambiguous, line(a));
    }

    private static List<Expr> list(Expression e) {
        List<Expr> out = new ArrayList<>();
        if (e instanceof ArrayInitializerExpr arr) {
            for (Expression x : arr.getValues()) out.add(Expr.of(x));
        } else {
            out.add(Expr.of(e));
        }
        return out;
    }

    private static void env(State s, CompilationUnit cu) {
        for (AnnotationExpr a : cu.findAll(AnnotationExpr.class)) {
            if (!is(a, VALUE)) continue;
            Expression v = null;
            if (a instanceof SingleMemberAnnotationExpr sm) v = sm.getMemberValue();
            if (a instanceof NormalAnnotationExpr n) {
                for (MemberValuePair p : n.getPairs()) if (p.getNameAsString().equals("value")) v = p.getValue();
            }
            if (v instanceof StringLiteralExpr lit) s.env.add(new EnvUse(EnvUse.Kind.VALUE, lit.asString(), line(a)));
        }
        boolean staticGetenv = SYSTEM.equals(s.staticImports.get("getenv")) || s.staticWildcards.contains(SYSTEM);
        for (MethodCallExpr m : cu.findAll(MethodCallExpr.class)) {
            if (!m.getNameAsString().equals("getenv")) continue;
            boolean system = m.getScope()
                    .map(sc -> sc.toString().equals("System") || sc.toString().equals(SYSTEM))
                    .orElse(staticGetenv);
            if (!system) continue;
            String name = m.getArguments().size() == 1 && m.getArgument(0) instanceof StringLiteralExpr lit
                    ? lit.asString() : null;
            s.env.add(new EnvUse(EnvUse.Kind.GETENV, name, line(m)));
        }
    }

    private static boolean isMain(MethodDeclaration m) {
        if (!m.getNameAsString().equals("main") || !m.isPublic() || !m.isStatic() || !m.getType().isVoidType()
                || m.getParameters().size() != 1) {
            return false;
        }
        Parameter p = m.getParameter(0);
        Type t = p.getType();
        if (p.isVarArgs()) return isString(t);
        return t.isArrayType() && t.asArrayType().getArrayLevel() == 1 && isString(t.asArrayType().getComponentType());
    }

    private static boolean isString(Type t) {
        String n = t.asString();
        return n.equals("String") || n.equals("java.lang.String");
    }

    static boolean isMapping(AnnotationExpr a) {
        return mappingName(a) != null;
    }

    /** The simple name of a mapping annotation, or null. */
    private static String mappingName(AnnotationExpr a) {
        String n = a.getNameAsString();
        if (n.startsWith(WEB)) n = n.substring(WEB.length());
        return MAPPINGS.containsKey(n) && !n.contains(".") ? n : null;
    }

    private static boolean is(AnnotationExpr a, String... names) {
        String n = a.getNameAsString();
        for (String x : names) if (n.equals(x)) return true;
        return false;
    }

    private static Integer min(Integer a, int b) {
        return a == null ? b : Math.min(a, b);
    }

    static int line(Node n) {
        return n.getBegin().map(p -> Math.max(1, p.line)).orElse(1);
    }
}
