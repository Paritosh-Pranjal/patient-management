package com.pm.patientservice.mapper;

import com.pm.patientservice.dto.PatientRequestDTO;
import com.pm.patientservice.dto.PatientResponseDTO;
import com.pm.patientservice.model.Patient;

import java.time.LocalDate;

public class PatientMapper {
    public static PatientResponseDTO toDTO(Patient p) {
        PatientResponseDTO dto = new PatientResponseDTO();
        dto.setId(p.getId().toString());
        dto.setName(p.getName());
        dto.setEmail(p.getEmail());
        dto.setDateOfBirth(p.getDateOfBirth().toString());
        dto.setAddress(p.getAddress());
        if (p.getRegisteredDate() != null) {
            dto.setRegisteredDate(p.getRegisteredDate().toString());
        }
        return dto;
    }

    public static Patient toModel(PatientRequestDTO dto) {
        Patient p = new Patient();
        p.setName(dto.getName());
        p.setEmail(dto.getEmail());
        p.setAddress(dto.getAddress());
        p.setDateOfBirth(LocalDate.parse(dto.getDateOfBirth()));
        p.setRegisteredDate(LocalDate.parse(dto.getRegisteredDate()));
        return p;
    }
}
