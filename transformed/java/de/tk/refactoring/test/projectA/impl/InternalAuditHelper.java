package de.tk.refactoring.test.projectA.impl;

public class InternalAuditHelper {

    public String computeAuditTag(String input) {
        return "AUDIT[" + input + "]";
    }
}
