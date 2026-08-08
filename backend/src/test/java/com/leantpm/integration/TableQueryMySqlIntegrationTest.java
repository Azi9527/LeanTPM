package com.leantpm.integration;

import com.leantpm.common.query.TableQuery;
import com.leantpm.equipment.EquipmentDtos;
import com.leantpm.equipment.EquipmentMapper;
import com.leantpm.inspection.InspectionDtos;
import com.leantpm.inspection.InspectionMapper;
import com.leantpm.security.datascope.DataPermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "LEANTPM_TEST_DB_URL", matches = ".+")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=${LEANTPM_TEST_DB_URL}",
                "spring.datasource.username=${LEANTPM_TEST_DB_USERNAME:root}",
                "spring.datasource.password=${LEANTPM_TEST_DB_PASSWORD:}",
                "spring.data.redis.repositories.enabled=false",
                "management.health.redis.enabled=false",
                "leantpm.security.jwt-secret=integration-test-secret-at-least-32-characters",
                "leantpm.bootstrap.admin-password="
        }
)
class TableQueryMySqlIntegrationTest {
    private static final long TENANT_ID = 1L;
    private static final DataPermission ALL_DATA = DataPermission.all(1L);

    @Autowired
    private EquipmentMapper equipmentMapper;

    @Autowired
    private InspectionMapper inspectionMapper;

    @Test
    void combinesEquipmentHeaderFiltersInDatabaseAndUsesTheSameConditionsForCount() {
        List<EquipmentDtos.EquipmentRow> baseline = equipmentMapper.findEquipmentPage(
                TENANT_ID, ALL_DATA, null, null, null, null, null, null, 1,
                TableQuery.empty(), 0, 100
        );
        assertThat(baseline).isNotEmpty();
        EquipmentDtos.EquipmentRow sample = baseline.getFirst();

        TableQuery query = new TableQuery(
                TableQuery.Logic.AND,
                List.of(
                        new TableQuery.Filter("equipmentCode", TableQuery.Operator.EQ,
                                sample.equipmentCode(), List.of()),
                        new TableQuery.Filter("equipmentName", TableQuery.Operator.CONTAINS,
                                sample.equipmentName().substring(0, 1), List.of())
                ),
                "equipmentCode",
                TableQuery.SortDirection.ASC
        );

        List<EquipmentDtos.EquipmentRow> records = equipmentMapper.findEquipmentPage(
                TENANT_ID, ALL_DATA, null, null, null, null, null, null, 1,
                query, 0, 100
        );
        long total = equipmentMapper.countEquipment(
                TENANT_ID, ALL_DATA, null, null, null, null, null, null, 1, query
        );

        assertThat(records).extracting(EquipmentDtos.EquipmentRow::equipmentCode)
                .containsExactly(sample.equipmentCode());
        assertThat(total).isEqualTo(records.size());
    }

    @Test
    void combinesInspectionTaskHeaderFiltersInDatabase() {
        InspectionDtos.TaskQuery baseQuery = new InspectionDtos.TaskQuery(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, false, null, false
        );
        List<InspectionDtos.TaskRow> baseline = inspectionMapper.findTasksTable(
                TENANT_ID, ALL_DATA, baseQuery, TableQuery.empty(), 0, 100
        );
        if (baseline.isEmpty()) return;
        InspectionDtos.TaskRow sample = baseline.getFirst();

        TableQuery query = new TableQuery(
                TableQuery.Logic.AND,
                List.of(
                        new TableQuery.Filter("taskCode", TableQuery.Operator.EQ,
                                sample.taskCode(), List.of()),
                        new TableQuery.Filter("equipmentCode", TableQuery.Operator.EQ,
                                sample.equipmentCode(), List.of()),
                        new TableQuery.Filter("plannedDate", TableQuery.Operator.EQ,
                                sample.plannedDate().toString(), List.of())
                ),
                "plannedDate",
                TableQuery.SortDirection.DESC
        );

        List<InspectionDtos.TaskRow> records = inspectionMapper.findTasksTable(
                TENANT_ID, ALL_DATA, baseQuery, query, 0, 100
        );
        long total = inspectionMapper.countTasksTable(TENANT_ID, ALL_DATA, baseQuery, query);

        assertThat(records).extracting(InspectionDtos.TaskRow::taskCode)
                .containsExactly(sample.taskCode());
        assertThat(total).isEqualTo(records.size());
    }

    @Test
    void appliesCatalogPlanAndAbnormalHeaderFiltersInDatabase() {
        List<InspectionDtos.ItemRow> items = inspectionMapper.findItems(
                TENANT_ID, ALL_DATA, null, null, null, null, null,
                TableQuery.empty(), 0, 100
        );
        assertThat(inspectionMapper.countItems(
                TENANT_ID, ALL_DATA, null, null, null, null, null,
                TableQuery.empty()
        )).isGreaterThanOrEqualTo(items.size());
        if (!items.isEmpty()) {
            InspectionDtos.ItemRow sample = items.getFirst();
            TableQuery query = exact("itemCode", sample.itemCode(), "itemCode");
            assertThat(inspectionMapper.findItems(
                    TENANT_ID, ALL_DATA, null, null, null, null, null,
                    query, 0, 100
            )).extracting(InspectionDtos.ItemRow::itemCode).containsExactly(sample.itemCode());

            String ruleToken = Boolean.TRUE.equals(sample.requiredFlag()) ? "必填"
                    : Boolean.TRUE.equals(sample.photoRequiredFlag()) ? "拍照"
                    : sample.status() == 1 ? "启用" : "停用";
            TableQuery ruleQuery = contains("ruleSummary", ruleToken, "ruleSummary");
            assertThat(inspectionMapper.findItems(
                    TENANT_ID, ALL_DATA, null, null, null, null, null,
                    ruleQuery, 0, 100
            )).isNotEmpty();
        }

        List<InspectionDtos.SchemeRow> schemes = inspectionMapper.findSchemes(
                TENANT_ID, null, null, null, TableQuery.empty(), 0, 100
        );
        assertThat(inspectionMapper.countSchemes(
                TENANT_ID, null, null, null, TableQuery.empty()
        )).isGreaterThanOrEqualTo(schemes.size());
        if (!schemes.isEmpty()) {
            InspectionDtos.SchemeRow sample = schemes.getFirst();
            TableQuery query = exact("schemeCode", sample.schemeCode(), "schemeCode");
            assertThat(inspectionMapper.findSchemes(
                    TENANT_ID, null, null, null, query, 0, 100
            )).extracting(InspectionDtos.SchemeRow::schemeCode).containsExactly(sample.schemeCode());
        }

        List<InspectionDtos.PlanRow> plans = inspectionMapper.findPlans(
                TENANT_ID, ALL_DATA, null, null, TableQuery.empty(), 0, 100
        );
        assertThat(inspectionMapper.countPlans(
                TENANT_ID, ALL_DATA, null, null, TableQuery.empty()
        )).isGreaterThanOrEqualTo(plans.size());
        if (!plans.isEmpty()) {
            InspectionDtos.PlanRow sample = plans.getFirst();
            TableQuery query = exact("equipmentCode", sample.equipmentCode(), "equipmentCode");
            assertThat(inspectionMapper.findPlans(
                    TENANT_ID, ALL_DATA, null, null, query, 0, 100
            )).allMatch(row -> row.equipmentCode().equals(sample.equipmentCode()));

            if (sample.assigneeName() != null && !sample.assigneeName().isBlank()) {
                String[] assignees = sample.assigneeName().split("、");
                String assigneeToken = assignees[assignees.length - 1].trim();
                TableQuery assigneeQuery = contains("assigneeName", assigneeToken, "assigneeName");
                List<InspectionDtos.PlanRow> assigneePlans = inspectionMapper.findPlans(
                        TENANT_ID, ALL_DATA, null, null, assigneeQuery, 0, 100
                );
                assertThat(assigneePlans).isNotEmpty()
                        .allMatch(row -> row.assigneeName().contains(assigneeToken));
                assertThat(inspectionMapper.countPlans(
                        TENANT_ID, ALL_DATA, null, null, assigneeQuery
                )).isEqualTo(assigneePlans.size());
            }
        }

        List<InspectionDtos.AbnormalRow> abnormalities = inspectionMapper.findAbnormalities(
                TENANT_ID, ALL_DATA, null, null, TableQuery.empty(), 0, 100
        );
        assertThat(inspectionMapper.countAbnormalities(
                TENANT_ID, ALL_DATA, null, null, TableQuery.empty()
        )).isGreaterThanOrEqualTo(abnormalities.size());
        if (!abnormalities.isEmpty()) {
            InspectionDtos.AbnormalRow sample = abnormalities.getFirst();
            TableQuery query = exact("abnormalCode", sample.abnormalCode(), "abnormalCode");
            assertThat(inspectionMapper.findAbnormalities(
                    TENANT_ID, ALL_DATA, null, null, query, 0, 100
            )).extracting(InspectionDtos.AbnormalRow::abnormalCode)
                    .containsExactly(sample.abnormalCode());
        }
    }

    private static TableQuery exact(String field, String value, String sortBy) {
        return new TableQuery(
                TableQuery.Logic.AND,
                List.of(new TableQuery.Filter(field, TableQuery.Operator.EQ, value, List.of())),
                sortBy,
                TableQuery.SortDirection.ASC
        );
    }

    private static TableQuery contains(String field, String value, String sortBy) {
        return new TableQuery(
                TableQuery.Logic.AND,
                List.of(new TableQuery.Filter(field, TableQuery.Operator.CONTAINS, value, List.of())),
                sortBy,
                TableQuery.SortDirection.ASC
        );
    }
}
