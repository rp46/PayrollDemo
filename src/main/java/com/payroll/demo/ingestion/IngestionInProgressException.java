package com.payroll.demo.ingestion;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.io.Serial;

/**
 * Raised when a sync is requested while one is already running on this instance.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class IngestionInProgressException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IngestionInProgressException() {
        super("An ingestion run is already in progress; wait for it to finish before starting another.");
    }
}
