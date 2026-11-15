package de.tk.refactoring.test.projectA;

/**
 * Purely internal helper class without any @Api usage.
 * Must be moved into the impl package during transformation.
 */
public class InternalAuditHelper {

    public String computeAuditTag(String input) {
        return "AUDIT[" + input + "]";
    }
}
