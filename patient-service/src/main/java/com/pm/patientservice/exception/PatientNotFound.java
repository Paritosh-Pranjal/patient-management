package com.pm.patientservice.exception;

public class PatientNotFound extends RuntimeException {
    public PatientNotFound(String message) {
        super(message);
    }
}
