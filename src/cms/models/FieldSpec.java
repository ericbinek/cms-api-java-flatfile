package cms.models;

import java.util.List;

/**
 * Typed schema metadata for one entity field. A sealed hierarchy so the model
 * can branch over field shapes with an exhaustive pattern-matching switch
 * instead of reading an untyped Map descriptor and casting its entries.
 */
public sealed interface FieldSpec {

    Cardinality cardinality();

    enum Cardinality { ONE, MANY }

    record Scalar(String type, Cardinality cardinality, Integer maxLength, boolean multiline) implements FieldSpec {}

    record Enumerated(List<String> values, Cardinality cardinality) implements FieldSpec {}

    record Ref(List<String> targets, Cardinality cardinality) implements FieldSpec {}

    record Embed(String type, Cardinality cardinality) implements FieldSpec {}
}
