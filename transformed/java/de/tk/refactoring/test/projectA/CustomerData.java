package de.tk.refactoring.test.projectA;

import de.tk.dependencyanalyse.transformation.Api;

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
