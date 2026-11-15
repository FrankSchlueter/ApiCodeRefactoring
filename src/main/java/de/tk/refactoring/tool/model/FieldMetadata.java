package de.tk.refactoring.tool.model;

/**
 * Represents a public static final constant field in the dependency metamodel.
 */
public record FieldMetadata(
        String name,
        String type,
        String initializerExpr
) {}
