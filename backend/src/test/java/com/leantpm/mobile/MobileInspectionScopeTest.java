package com.leantpm.mobile;

import com.leantpm.security.datascope.DataPermission;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MobileInspectionScopeTest {
    @Test
    void addsTheAuthorizedInspectionOrganizationTreeWithoutDroppingExistingScope() {
        DataPermission base = DataPermission.restricted(7L, true, Set.of(11L));

        DataPermission result = MobileService.inspectionScanScope(base, List.of(20L, 21L, 22L));

        assertThat(result.allData()).isFalse();
        assertThat(result.selfData()).isTrue();
        assertThat(result.organizationIds()).containsExactlyInAnyOrder(11L, 20L, 21L, 22L);
    }

    @Test
    void keepsAdministratorScopeUnrestricted() {
        DataPermission base = DataPermission.all(7L);

        assertThat(MobileService.inspectionScanScope(base, List.of(20L)))
                .isSameAs(base);
    }

    @Test
    void organizationQueryPromotesOnlyTeamsToTheirDirectParentAndThenIncludesDescendants()
            throws IOException {
        String xml = Files.readString(
                Path.of("src/main/resources/mapper/mobile/MobileMapper.xml"),
                StandardCharsets.UTF_8
        );

        assertThat(xml).contains("id=\"inspectionScanOrganizationIds\"");
        assertThat(xml).contains("own.organization_type = 'TEAM'");
        assertThat(xml).contains("membership_team.organization_type = 'TEAM'");
        assertThat(xml).contains("own.parent_id");
        assertThat(xml).contains("membership_team.parent_id");
        assertThat(xml).contains("JOIN inspection_scan_roots parent ON child.parent_id = parent.id");
        assertThat(xml).contains("child.tenant_id = #{tenantId}");
    }
}
