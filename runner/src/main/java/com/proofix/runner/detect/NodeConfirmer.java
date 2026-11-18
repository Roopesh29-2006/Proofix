package com.proofix.runner.detect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proofix.runner.detect.model.Role;

import java.io.IOException;
import java.util.List;

/** Classifies a package.json (read with Jackson; nothing is executed). */
public final class NodeConfirmer {
    private NodeConfirmer() {}

    public static final List<String> SERVER_DEPS = List.of(
            "express", "fastify", "koa", "@nestjs/core", "@hapi/hapi", "hono", "@adonisjs/core", "sails");
    public static final List<String> FRONTEND_DEPS = List.of(
            "vite", "react-scripts", "@angular/core", "vue", "svelte", "@sveltejs/kit", "nuxt");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Parses package.json; returns null if it is not a JSON object. */
    static JsonNode parse(byte[] bytes) {
        try {
            JsonNode n = JSON.readTree(bytes);
            return n != null && n.isObject() ? n : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static Confirmation confirmNode(MarkerReader.MarkerData pkg) {
        if (pkg.tooLarge()) return new Confirmation(false, Role.TOOLING, Confirmation.MARKER_TOO_LARGE);
        if (!pkg.readable()) return new Confirmation(false, Role.TOOLING, Confirmation.MARKER_UNREADABLE);
        JsonNode root = parse(pkg.bytes());
        if (root == null) return new Confirmation(false, Role.TOOLING, Confirmation.MARKER_UNPARSEABLE);

        String entry = null;
        JsonNode scripts = root.get("scripts");
        if (scripts != null && scripts.isObject()) {
            if (scripts.hasNonNull("start")) entry = "scripts.start";
            else if (scripts.hasNonNull("dev")) entry = "scripts.dev";
        }
        if (entry == null && root.hasNonNull("main")) entry = "main";

        String server = firstDep(root, SERVER_DEPS);
        if (server != null && entry != null) {
            return new Confirmation(true, Role.BACKEND, "server dependency " + server + " with " + entry);
        }
        if (server == null) {
            String fe = firstDep(root, FRONTEND_DEPS);
            if (fe != null) return new Confirmation(true, Role.FRONTEND, "frontend dependency " + fe);
        }
        return new Confirmation(false, Role.TOOLING,
                server != null ? "server dependency " + server + " without start, dev or main"
                        : "no server or frontend dependency");
    }

    public static Confirmation confirmNext(MarkerReader.MarkerData pkg) {
        if (pkg.readable() && parse(pkg.bytes()) == null) {
            return new Confirmation(false, Role.TOOLING, Confirmation.MARKER_UNPARSEABLE);
        }
        return new Confirmation(true, Role.FRONTEND, "next.config present");
    }

    static boolean isUnparseable(MarkerReader.MarkerData pkg) {
        return pkg.readable() && parse(pkg.bytes()) == null;
    }

    private static String firstDep(JsonNode root, List<String> names) {
        for (String name : names) {
            for (String section : List.of("dependencies", "devDependencies")) {
                JsonNode deps = root.get(section);
                if (deps != null && deps.isObject() && deps.has(name)) return name;
            }
        }
        return null;
    }
}
