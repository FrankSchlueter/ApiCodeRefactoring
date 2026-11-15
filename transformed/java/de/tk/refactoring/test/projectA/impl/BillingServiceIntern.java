package de.tk.refactoring.test.projectA.impl;

import de.tk.refactoring.test.projectA.BillingServiceApi;

public interface BillingServiceIntern extends BillingServiceApi {

    String resetInternalCounter();
}
