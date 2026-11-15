package de.tk.refactoring.test.projectA.impl;

import de.tk.dependencyanalyse.transformation.Api;
import de.tk.refactoring.test.projectA.BaseService;

public class BaseServiceImpl implements BaseServiceIntern {

    @Api
public String getBaseId() {
    return "BASE-100";
}

    public String getInternalBaseState() {
        return "INTERNAL-BASE-STATE";
    }
}
