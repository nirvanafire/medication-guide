package com.medication.repository;

import com.medication.entity.DrugAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface DrugAliasRepository extends JpaRepository<DrugAlias, Long> {

    Optional<DrugAlias> findByAliasName(String aliasName);

    List<DrugAlias> findByAliasNameContaining(String aliasName);

    List<DrugAlias> findByStandardName(String standardName);

    @Query("SELECT DISTINCT d.standardName FROM DrugAlias d")
    Set<String> findAllStandardNames();

    @Query("SELECT d FROM DrugAlias d WHERE d.aliasName LIKE %:keyword% OR d.standardName LIKE %:keyword%")
    List<DrugAlias> searchByKeyword(@Param("keyword") String keyword);
}
