// SPDX-License-Identifier: Apache-2.0
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.tatrman.ttr.yaml.CanonicalYaml;
import org.tatrman.ttr.yaml.CanonicalYamlException;

public final class CanonicalYamlConsumer {
    public static void main(String[] args) throws Exception {
        var codec = CanonicalYaml.INSTANCE;
        var source = "{ttr: '0.21', model: er, defs: [{kind: entity, id: Item, properties: {}}]}\n";
        var document = codec.read(source, "consumer.yaml");
        check(document.getAst().getErrors().isEmpty(), "typed parse");
        check(document.getAst().getDefinitions().size() == 1, "entity count");
        var canonical = codec.write(document);
        check(canonical.equals(codec.write(codec.read(canonical, "again.yaml"))), "YAML bytes");
        check(canonical.equals(codec.write(codec.fromTtr(codec.toTtr(document), "native.ttrm"))), "native fixed point");
        for (var name : new String[]{"model-yaml.schema.json", "profile.schema.json", "canonical-metadata.json"}) {
            try (InputStream resource = CanonicalYaml.class.getResourceAsStream("/schemas/" + name)) {
                check(resource != null, "bundled " + name);
                check(Arrays.equals(resource.readAllBytes(), Files.readAllBytes(Path.of("schemas", name))), "schema bytes " + name);
            }
        }
        try {
            codec.read("ttr: '0.21'\nunknown: true\n", "bad.yaml");
            throw new AssertionError("unknown key accepted");
        } catch (CanonicalYamlException expected) {
            check(expected.getFile().equals("bad.yaml") && expected.getLine() == 2, "located rejection");
        }
        System.out.println("PASS: published codec parse, YAML/native fixed points, three offline resources and located rejection");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
