package com.leantpm.common.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leantpm.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableQueryParserTest {
    private final TableQueryParser parser = new TableQueryParser(new ObjectMapper());

    @Test
    void parsesCompoundFiltersAndWhitelistedSort() {
        TableQuery query = parser.parse("""
                {"logic":"AND","filters":[
                  {"field":"equipmentName","operator":"CONTAINS","value":"泵"},
                  {"field":"statusDurationSeconds","operator":"BETWEEN","values":["60","3600"]}
                ]}
                """, "equipmentCode", "desc", Set.of(
                "equipmentName", "statusDurationSeconds", "equipmentCode"
        ));

        assertThat(query.logic()).isEqualTo(TableQuery.Logic.AND);
        assertThat(query.filters()).hasSize(2);
        assertThat(query.sortBy()).isEqualTo("equipmentCode");
        assertThat(query.sortDirection()).isEqualTo(TableQuery.SortDirection.DESC);
    }

    @Test
    void rejectsUnknownFieldsBeforeTheyReachSql() {
        assertThatThrownBy(() -> parser.parse(
                "{\"filters\":[{\"field\":\"e.id desc --\",\"operator\":\"EQ\",\"value\":\"1\"}]}",
                null, null, Set.of("equipmentCode")
        )).isInstanceOf(BusinessException.class)
          .hasMessageContaining("不支持筛选字段");
    }
}
