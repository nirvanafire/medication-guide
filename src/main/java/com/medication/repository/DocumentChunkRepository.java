package com.medication.repository;

import com.medication.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    List<DocumentChunk> findByDocumentId(Long documentId);

    List<DocumentChunk> findByDrugName(String drugName);

    List<DocumentChunk> findByDrugNameAndSection(String drugName, String section);

    @Query("SELECT c FROM DocumentChunk c WHERE c.drugName = :drugName ORDER BY c.section, c.chunkIndex")
    List<DocumentChunk> findByDrugNameOrdered(@Param("drugName") String drugName);

    void deleteByDocumentId(Long documentId);
}