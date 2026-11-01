package com.yottaflex.budget.service;

/** Raised for requests that are well-formed but violate a business rule (e.g. an illegal lifecycle transition). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
