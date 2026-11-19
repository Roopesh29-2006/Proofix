package com.proofix.runner.config.spring;

import com.proofix.runner.scan.model.Warning;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [SEC] Reads application*.yml with the SnakeYAML NODE API (compose, never load into objects, so
 * no type construction happens and every scalar keeps its line). LoaderOptions are tight: at most
 * 10 aliases for collections, nesting depth 20, code points bounded by the file's byte size, no
 * recursive keys, no duplicate keys (also enforced while flattening, as compose alone does not).
 * The flattener refuses alias cycles and stops after {@link ConfigDocs#MAX_ENTRIES} entries.
 *
 * <p>SnakeYAML exception messages quote file content, so every failure is caught here and turned
 * into a CONFIG_UNPARSEABLE warning with fixed text; no message or cause is kept.
 */
public final class YamlConfigReader {
    private YamlConfigReader() {}

    public static final int MAX_ALIASES = 10;
    public static final int MAX_DEPTH = 20;

    public static ConfigDocs read(String path, byte[] bytes, String fileProfile) {
        LoaderOptions o = new LoaderOptions();
        o.setMaxAliasesForCollections(MAX_ALIASES);
        o.setNestingDepthLimit(MAX_DEPTH);
        o.setCodePointLimit(Math.max(1, bytes.length));
        o.setAllowRecursiveKeys(false);
        o.setAllowDuplicateKeys(false);
        o.setProcessComments(false);
        List<Warning> warnings = new ArrayList<>();
        List<ConfigDocs.Doc> docs = new ArrayList<>();
        try {
            Yaml yaml = new Yaml(o);
            int[] count = {0};
            for (Node root : yaml.composeAll(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
                if (root == null) continue;
                List<String[]> raw = new ArrayList<>();
                List<Integer> lines = new ArrayList<>();
                flatten(root, "", raw, lines, new IdentityHashMap<>(), count);
                docs.add(ConfigDocs.doc(raw, lines, fileProfile, path, warnings));
            }
        } catch (ConfigDocs.Unparseable e) {
            return ConfigDocs.failed(path, e.tooMany);
        } catch (RuntimeException | StackOverflowError e) {
            return ConfigDocs.failed(path, false);
        }
        return new ConfigDocs(docs, warnings);
    }

    private static void flatten(Node n, String prefix, List<String[]> raw, List<Integer> lines,
                                Map<Node, Boolean> onPath, int[] count) {
        if (onPath.containsKey(n)) throw new ConfigDocs.Unparseable(false); // alias cycle
        if (n instanceof ScalarNode s) {
            if (prefix.isEmpty()) return;
            if (++count[0] > ConfigDocs.MAX_ENTRIES) throw new ConfigDocs.Unparseable(true);
            raw.add(new String[] {prefix, Tag.NULL.equals(s.getTag()) ? "" : s.getValue()});
            lines.add(s.getStartMark().getLine() + 1);
            return;
        }
        onPath.put(n, Boolean.TRUE);
        if (n instanceof MappingNode m) {
            Set<String> keys = new HashSet<>();
            for (NodeTuple t : m.getValue()) {
                if (!(t.getKeyNode() instanceof ScalarNode k)) continue;
                String key = k.getValue();
                if (!keys.add(key)) throw new ConfigDocs.Unparseable(false); // duplicate key
                if (key.equals("<<")) continue; // merge keys are not followed
                flatten(t.getValueNode(), prefix.isEmpty() ? key : prefix + "." + key, raw, lines, onPath, count);
            }
        } else if (n instanceof SequenceNode q) {
            List<Node> items = q.getValue();
            for (int i = 0; i < items.size(); i++) {
                flatten(items.get(i), prefix + "[" + i + "]", raw, lines, onPath, count);
            }
        }
        onPath.remove(n);
    }
}
