package de.tk.refactoring.test.projectA;

import de.tk.dependencyanalyse.transformation.Api;

/**
 * Base implementation class in projectA with both an API method and an internal method.
 */
public class BaseService {

    @Api
    public String getBaseId() {
        return "BASE-100";
    }

    public String getInternalBaseState() {
        return "INTERNAL-BASE-STATE";
    }
}
