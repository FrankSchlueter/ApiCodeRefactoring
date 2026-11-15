package de.tk.refactoring.tool.model;

import java.util.List;

/**
 * Represents a constructor signature in the dependency metamodel.
 */
public record ConstructorSignature(
        List<MethodSignature.ParameterSpec> parameters,
        List<String> thrownExceptions
) {
    public ConstructorSignature(List<MethodSignature.ParameterSpec> parameters) {
        this(parameters, List.of());
    }
}
