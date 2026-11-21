package com.proofix.runner.source;

import com.proofix.runner.config.ConfigContext;
import com.proofix.runner.config.ConfigFactsExtractor;
import com.proofix.runner.config.spring.ConfigDocs;
import com.proofix.runner.config.spring.SpringConfigFacts;
import com.proofix.runner.detect.model.Detection;
import com.proofix.runner.detect.model.Role;
import com.proofix.runner.detect.model.Stack;
import com.proofix.runner.facts.model.Endpoint;
import com.proofix.runner.facts.model.Fact;
import com.proofix.runner.facts.model.FactEntry;
import com.proofix.runner.facts.model.FactEvidence;
import com.proofix.runner.facts.model.FactStatus;
import com.proofix.runner.scan.WarningCodes;
import com.proofix.runner.scan.model.Warning;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Facts read from Java source (Build 2B-0b-2): endpoints, entry point, env var names in code.
 * Runs after the config facts, whose context-path and entry.mainClass facts it uses.
 */
public final class SourceFacts {
    private SourceFacts() {}

    static final String NOT_COVERED_STACK = "endpoints of this kind of backend stack are not detected";

    public record Result(List<Endpoint> endpoints, boolean complete) {}

    /**
     * Adds endpoint results, replaces or adds entry.mainClass facts in {@code facts} and records
     * env var uses from code in {@code env}.
     */
    public static Result extract(ConfigContext ctx, Detection detection, List<JavaSources.Module> modules,
                                 List<FactEntry> facts, Map<String, SpringConfigFacts.EnvAcc> env, int maxParsed) {
        boolean complete = !modules.isEmpty();
        for (Stack s : detection.stacks()) {
            if (Role.BACKEND.equals(s.role()) && !ConfigFactsExtractor.MAVEN_KIND.equals(s.kind())) {
                ctx.warn(new Warning(WarningCodes.ENDPOINTS_NOT_COVERED, s.path(), NOT_COVERED_STACK));
                complete = false;
            }
        }
        if (modules.isEmpty()) return new Result(List.of(), false);

        JavaSources.Result sources = JavaSources.read(ctx, modules, maxParsed);
        EndpointExtractor.Result endpoints = EndpointExtractor.extract(ctx, sources, contextPaths(modules, facts));
        EntryPoints.apply(modules, sources, ctx.scan().git().commitSha(), facts);
        codeEnv(ctx, sources, env);
        return new Result(endpoints.endpoints(), complete && endpoints.complete());
    }

    static final String COMPUTED_MESSAGE = "System.getenv name is not a string literal; the variable is not listed";

    private static void codeEnv(ConfigContext ctx, JavaSources.Result sources, Map<String, SpringConfigFacts.EnvAcc> env) {
        String commit = ctx.scan().git().commitSha();
        for (JavaSources.ParsedFile f : sources.files()) {
            for (SourceSummary.EnvUse u : f.summary().envUses()) {
                if (u.kind() == SourceSummary.EnvUse.Kind.GETENV && u.text() == null) {
                    ctx.warn(new Warning(WarningCodes.ENV_NAME_COMPUTED, null, f.path(), u.line(), COMPUTED_MESSAGE));
                    continue;
                }
                boolean value = u.kind() == SourceSummary.EnvUse.Kind.VALUE;
                FactEvidence ev = new FactEvidence(value ? SpringConfigFacts.RULE_VALUE : SpringConfigFacts.RULE_GETENV,
                        f.path(), u.line(), commit, f.sha256());
                if (value) SpringConfigFacts.recordValueAnnotation(env, u.text(), ev);
                else SpringConfigFacts.recordGetenv(env, u.text(), ev);
            }
        }
    }

    /** Module dir -> the context path of its app (its own, or the stack's only app module). */
    static Map<String, EndpointExtractor.ContextPath> contextPaths(List<JavaSources.Module> modules, List<FactEntry> facts) {
        Map<String, EndpointExtractor.ContextPath> out = new HashMap<>();
        for (JavaSources.Module m : modules) {
            String app = m.app() ? m.dir() : null;
            if (app == null) {
                List<JavaSources.Module> apps = modules.stream()
                        .filter(x -> x.app() && x.stackPath().equals(m.stackPath())).toList();
                if (apps.size() == 1) app = apps.get(0).dir();
            }
            out.put(m.dir(), app == null ? new EndpointExtractor.Unknown() : contextPath(facts, app));
        }
        return out;
    }

    private static EndpointExtractor.ContextPath contextPath(List<FactEntry> facts, String appDir) {
        for (FactEntry e : facts) {
            if (e instanceof Fact f && f.name().equals(SpringConfigFacts.CONTEXT_PATH) && f.module().equals(appDir)
                    && ConfigDocs.DEFAULT_PROFILE.equals(f.profile())) {
                return f.status() == FactStatus.KNOWN
                        ? new EndpointExtractor.Known(f.value(), f.evidence())
                        : new EndpointExtractor.Unknown();
            }
        }
        return new EndpointExtractor.None();
    }
}
