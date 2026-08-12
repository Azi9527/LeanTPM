package com.leantpm.inspection;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class V53InspectionAbnormalMeasuresMigrationTest {

    @Test
    void addsThreeMeasureColumnsAndPreservesHistoricalFinalResults() throws Exception {
        String sql = Files.readString(
                Path.of("src/main/resources/db/migration/V53__inspection_abnormal_measures.sql"),
                StandardCharsets.UTF_8
        );

        assertThat(sql)
                .contains("cause_analysis VARCHAR(2000) NULL")
                .contains("permanent_countermeasure VARCHAR(2000) NULL")
                .doesNotContain("UPDATE inspection_abnormal")
                .doesNotContain("DROP COLUMN final_result");

        String mapper = Files.readString(
                Path.of("src/main/resources/mapper/inspection/InspectionMapper.xml"),
                StandardCharsets.UTF_8
        );
        assertThat(mapper.replaceAll("\\s+", " "))
                .contains(
                        "COALESCE(abnormal.permanent_countermeasure, abnormal.final_result) AS permanent_countermeasure"
                );
    }
}
