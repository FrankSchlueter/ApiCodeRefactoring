package de.tk.refactoring.test.projectA;

import de.tk.dependencyanalyse.transformation.Api;

/**
 * Implementation class that already implements {@link BillingServiceApi},
 * but defines an additional @Api method {@link #getInvoiceCode(int)} not yet in the interface,
 * as well as an internal method {@link #resetInternalCounter()}.
 */
public class BillingService implements BillingServiceApi {

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
