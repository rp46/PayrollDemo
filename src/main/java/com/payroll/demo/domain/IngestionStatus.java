package com.payroll.demo.domain;

public enum IngestionStatus {

    /** The run is in flight. A row stuck here means the process died mid-run. */
    RUNNING,

    /** Every page was fetched and every record written. */
    SUCCEEDED,

    /** The upstream calls all worked, but some records were rejected by validation. */
    PARTIAL,

    /** The run stopped early, normally because the HRMS call could not be completed. */
    FAILED
}
