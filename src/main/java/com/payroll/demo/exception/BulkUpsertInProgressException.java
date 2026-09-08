package com.payroll.demo.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.io.Serial;

/** Raised when a bulk upsert is requested while one is already running on this instance. */
@ResponseStatus(HttpStatus.CONFLICT)
public class BulkUpsertInProgressException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public BulkUpsertInProgressException() {
        super("A bulk payroll upsert is already in progress; wait for it to finish before starting another.");
    }
}
