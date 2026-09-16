package com.jobportal.domain.enums;

// Broad categories used for browsing, filters and the "applications by category" chart.
public enum JobCategory {

    SOFTWARE_DEVELOPMENT("Software Development"),
    DATA_ANALYTICS("Data & Analytics"),
    DESIGN("Design"),
    MARKETING("Marketing"),
    SALES("Sales"),
    FINANCE("Finance & Accounting"),
    HUMAN_RESOURCES("Human Resources"),
    CUSTOMER_SUPPORT("Customer Support"),
    OPERATIONS("Operations"),
    OTHER("Other");

    private final String label;

    JobCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
