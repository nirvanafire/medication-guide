package com.medication.repository;

import com.medication.entity.DrugDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DrugDocumentRepository extends JpaRepository<DrugDocument, Long> {

    Optional<DrugDocument> findByDrugName(String drugName);

    List<DrugDocument> findByStatus(DrugDocument.DocumentStatus status);

    List<DrugDocument> findByDrugNameContaining(String keyword);

    boolean existsByDrugNameAndVersion(String drugName, String version);

    void deleteByDrugName(String drugName);
}