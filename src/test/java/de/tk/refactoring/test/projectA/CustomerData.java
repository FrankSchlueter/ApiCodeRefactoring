package de.tk.refactoring.test.projectA;

import de.tk.dependencyanalyse.transformation.Api;

/**
 * API-conformant value object (isApiKonform = true).
 * Remains directly in the API package without interface extraction.
 */
@Api(isApiKonform = true)
public class CustomerData {

    private final String customerName;

    public CustomerData(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerName() {
        return customerName;
    }
}
