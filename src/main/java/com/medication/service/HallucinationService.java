package com.medication.service;

import com.medication.util.HallucinationDetector;
import com.medication.util.HallucinationDetector.HallucinationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HallucinationService {

    private final HallucinationDetector hallucinationDetector;

    public HallucinationResult check(String answer, List<String> sourceChunks) {
        return hallucinationDetector.detect(answer, sourceChunks);
    }
}