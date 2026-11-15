package de.tk.refactoring.test.projectA.impl;

import de.tk.dependencyanalyse.transformation.Api;
import de.tk.refactoring.test.projectA.BillingServiceApi;

public class BillingService implements BillingServiceIntern {

    private int counter = 1;

    @Api
    public BillingService() {
    }

    @Override
@Api
public int calculateTotal(int amount) {
    return amount * 2;
}

    @Api
public String getInvoiceCode(int amount) {
    return "INV-" + calculateTotal(amount);
}

    public String resetInternalCounter() {
        this.counter = 0;
        return "COUNTER-RESET:" + counter;
    }
}
