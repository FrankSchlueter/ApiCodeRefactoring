package de.tk.refactoring.test.projectA;

import de.tk.dependencyanalyse.transformation.Api;

/**
 * Existing, incomplete API interface for {@link BillingService}.
 * Missing the {@code getInvoiceCode(int)} API method before transformation.
 */
@Api
public interface BillingServiceApi {

    int calculateTotal(int amount);
}
