package de.tk.refactoring.tool.model;

/**
 * Metadata entry for a method call whose scope must be cast to an Intern interface.
 *
 * @param targetClassFqn     FQN of the class being refactored
 * @param methodName         Name of the called internal method
 * @param lineNumber         1-based line number of the method call in the original source
 * @param internInterfaceFqn FQN of the Intern interface to cast the call scope to
 */
public record InternCastTarget(
        String targetClassFqn,
        String methodName,
        int lineNumber,
        String internInterfaceFqn
) {
    public String internInterfaceSimpleName() {
        int idx = internInterfaceFqn.lastIndexOf('.');
        return idx < 0 ? internInterfaceFqn : internInterfaceFqn.substring(idx + 1);
    }
}
