package de.tk.refactoring.tool.model;

/**
 * Metadata entry for a constructor call on an implementation class that must be replaced
 * by the API factory creation call.
 *
 * @param targetClassFqn        FQN of the class containing the constructor call
 * @param instantiatedClassFqn  FQN of the instantiated implementation class
 * @param providerInterfaceFqn  FQN of the Guice-injected Provider factory interface
 * @param lineNumber            1-based line number of the constructor call in the original source
 */
public record ConstructorCallTarget(
        String targetClassFqn,
        String instantiatedClassFqn,
        String providerInterfaceFqn,
        int lineNumber
) {}
