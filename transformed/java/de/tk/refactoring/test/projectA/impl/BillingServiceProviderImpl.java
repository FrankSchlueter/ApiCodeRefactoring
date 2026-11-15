package de.tk.refactoring.test.projectA.impl;

import de.tk.util.injection.ImplementationClass;
import jakarta.inject.Inject;
import de.tk.refactoring.test.projectA.BillingServiceProvider;
import de.tk.refactoring.test.projectA.BillingServiceApi;

@ImplementationClass
public class BillingServiceProviderImpl implements BillingServiceProvider {

    @ImplementationClass
    @Inject
    public BillingServiceProviderImpl() {
    }

    @Override
public BillingServiceApi create() {
    return new BillingService();
}
}
