package app.alertify.configuration.service;

import java.util.Set;

import org.springframework.data.domain.Pageable;

import app.alertify.jpa.specification.InvalidFilterException;

final class SearchValidation {

    private SearchValidation() {
    }

    static void validateSort(Pageable pageable, Set<String> allowedFields) {
        pageable.getSort().forEach(order -> {
            if (!allowedFields.contains(order.getProperty())) {
                throw new InvalidFilterException("sort=" + order.getProperty());
            }
        });
    }
}
