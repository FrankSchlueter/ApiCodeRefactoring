package de.tk.refactoring.test.projectA;

import de.tk.dependencyanalyse.transformation.Api;

@Api
public interface BillingServiceApi {

    int calculateTotal(int amount);

    String getInvoiceCode(int amount);

    class ProviderHolder {

        @jakarta.inject.Inject
public static BillingServiceProvider provider;
    }

    static BillingServiceApi create() {
        return ProviderHolder.provider.create();
    }
}
