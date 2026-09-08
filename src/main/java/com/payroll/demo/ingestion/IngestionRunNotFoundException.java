package com.payroll.demo.ingestion;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.io.Serial;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class IngestionRunNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IngestionRunNotFoundException(Long id) {
        super("No ingestion run with id " + id);
    }
}
