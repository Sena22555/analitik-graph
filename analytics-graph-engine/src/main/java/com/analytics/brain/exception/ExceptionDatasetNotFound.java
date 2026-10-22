package com.analytics.brain.exception;

import java.util.NoSuchElementException;

public class ExceptionDatasetNotFound extends NoSuchElementException {

    public ExceptionDatasetNotFound(String message) {
        super(message);
    }
}
