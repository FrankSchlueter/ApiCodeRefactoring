package de.tk.refactoring.tool.model;

import java.util.List;
import java.util.Optional;

/**
 * Metadata for a class or interface used as API by external modules.
 */
public record ApiClassMetadata(
        String fullyQualifiedName,
        boolean isApiKonform,
        Optional<String> existingApiInterfaceFqn,
        Optional<String> superClassFqn,
        List<String> implementedInterfaces,
        List<MethodSignature> apiMethods,
        List<MethodSignature> internalMethods,
        List<MethodSignature> staticApiMethods,
        List<FieldMetadata> staticApiFields,
        List<ConstructorSignature> apiConstructors
) {
    public String simpleName() {
        return extractSimpleName(fullyQualifiedName);
    }

    public String packageName() {
        int idx = fullyQualifiedName.lastIndexOf('.');
        return idx < 0 ? "" : fullyQualifiedName.substring(0, idx);
    }

    /**
     * Returns the FQN of the API interface to be used by callers.
     * If an existing API interface is present, returns its FQN; otherwise returns the original FQN.
     */
    public String effectiveApiInterfaceFqn() {
        return existingApiInterfaceFqn.orElse(fullyQualifiedName);
    }

    /**
     * Returns the simple name of the API interface to be used by callers.
     */
    public String effectiveApiInterfaceSimpleName() {
        return extractSimpleName(effectiveApiInterfaceFqn());
    }

    /**
     * Returns the simple name of the implementation class after transformation.
     * If an existing API interface already exists, the implementation class keeps its simple name;
     * otherwise it is renamed with the suffix "Impl".
     */
    public String effectiveImplSimpleName() {
        return existingApiInterfaceFqn.isPresent() ? simpleName() : simpleName() + "Impl";
    }

    /**
     * Returns the simple name of the generated Intern interface.
     */
    public String internInterfaceSimpleName() {
        return simpleName() + "Intern";
    }

    /**
     * Returns the simple name of the generated Provider factory interface.
     */
    public String providerInterfaceSimpleName() {
        return simpleName() + "Provider";
    }

    /**
     * Returns the simple name of the generated ProviderImpl factory implementation class.
     */
    public String providerImplSimpleName() {
        return simpleName() + "ProviderImpl";
    }

    public boolean requiresProvider() {
        return !isApiKonform && (!apiConstructors.isEmpty() || !staticApiMethods.isEmpty());
    }

    private static String extractSimpleName(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx < 0 ? fqn : fqn.substring(idx + 1);
    }
}
