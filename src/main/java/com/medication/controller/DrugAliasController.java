package com.medication.controller;

import com.medication.entity.DrugAlias;
import com.medication.repository.DrugAliasRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/drug-alias")
@RequiredArgsConstructor
public class DrugAliasController {

    private final DrugAliasRepository drugAliasRepository;

    @GetMapping
    public ResponseEntity<List<DrugAlias>> list() {
        return ResponseEntity.ok(drugAliasRepository.findAll());
    }

    @GetMapping("/standard-names")
    public ResponseEntity<List<String>> listStandardNames() {
        return ResponseEntity.ok(drugAliasRepository.findAllStandardNames().stream().sorted().toList());
    }

    @GetMapping("/search")
    public ResponseEntity<List<DrugAlias>> search(@RequestParam String keyword) {
        return ResponseEntity.ok(drugAliasRepository.searchByKeyword(keyword));
    }

    @GetMapping("/by-standard/{standardName}")
    public ResponseEntity<List<DrugAlias>> byStandardName(@PathVariable String standardName) {
        return ResponseEntity.ok(drugAliasRepository.findByStandardName(standardName));
    }

    @PostMapping
    public ResponseEntity<DrugAlias> create(@RequestBody DrugAlias alias) {
        alias.setId(null);
        DrugAlias saved = drugAliasRepository.save(alias);
        log.info("创建药品别名: {} -> {}", saved.getAliasName(), saved.getStandardName());
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/batch")
    public ResponseEntity<List<DrugAlias>> createBatch(@RequestBody List<DrugAlias> aliases) {
        for (DrugAlias alias : aliases) {
            alias.setId(null);
        }
        List<DrugAlias> saved = drugAliasRepository.saveAll(aliases);
        log.info("批量创建药品别名: {} 条", saved.size());
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DrugAlias> update(@PathVariable Long id, @RequestBody DrugAlias alias) {
        alias.setId(id);
        DrugAlias saved = drugAliasRepository.save(alias);
        log.info("更新药品别名: {}", saved.getId());
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        drugAliasRepository.deleteById(id);
        log.info("删除药品别名: {}", id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/by-standard/{standardName}")
    public ResponseEntity<Map<String, Integer>> deleteByStandardName(@PathVariable String standardName) {
        List<DrugAlias> aliases = drugAliasRepository.findByStandardName(standardName);
        drugAliasRepository.deleteAll(aliases);
        log.info("删除标准药品[{}]的所有别名: {} 条", standardName, aliases.size());
        return ResponseEntity.ok(Map.of("deleted", aliases.size()));
    }
}
