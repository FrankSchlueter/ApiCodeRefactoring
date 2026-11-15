package de.tk.refactoring.tool.model;

import java.util.List;

/**
 * Represents a method signature in the dependency metamodel.
 */
public record MethodSignature(
        String name,
        String returnType,
        List<ParameterSpec> parameters,
        List<String> typeParameters,
        List<String> thrownExceptions
) {
    public MethodSignature(String name, String returnType, List<ParameterSpec> parameters) {
        this(name, returnType, parameters, List.of(), List.of());
    }

    public record ParameterSpec(String type, String name) {}
}
