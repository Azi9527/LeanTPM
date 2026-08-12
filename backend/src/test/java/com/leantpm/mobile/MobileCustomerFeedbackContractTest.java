package com.leantpm.mobile;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MobileCustomerFeedbackContractTest {
    @Test
    void equipmentStatusListUsesTheSameMobileScanPermissionAndScopeAsEquipmentContext()
            throws IOException {
        String controller = source("src/main/java/com/leantpm/mobile/MobileController.java");
        String service = source("src/main/java/com/leantpm/mobile/MobileService.java");
        String mapper = source("src/main/resources/mapper/mobile/MobileMapper.xml");

        assertThat(controller).contains("@GetMapping(\"/equipment-status\")");
        assertThat(controller).contains(
                "hasAuthority('mobile:access') and hasAuthority('mobile:scan')"
        );
        assertThat(service).contains("equipmentStatus(");
        assertThat(service).contains("inspectionScanScope(");
        assertThat(mapper).contains("id=\"equipmentStatusRows\"");
        assertThat(mapper).contains("AS active_barcode_token");
        assertThat(mapper).contains("<include refid=\"equipmentScope\"/>");
    }

    @Test
    void bootstrapAndReleaseContractsExposeLatestCodeAndForceUpgrade() throws IOException {
        String mobileDtos = source("src/main/java/com/leantpm/mobile/MobileDtos.java");
        String mobileService = source("src/main/java/com/leantpm/mobile/MobileService.java");
        String releaseDtos = source(
                "src/main/java/com/leantpm/mobile/release/AppReleaseDtos.java"
        );
        String releaseController = source(
                "src/main/java/com/leantpm/mobile/release/AppReleaseController.java"
        );
        String releaseService = source(
                "src/main/java/com/leantpm/mobile/release/AppReleaseService.java"
        );

        assertThat(mobileDtos).contains("int latestVersionCode", "boolean forceUpgrade");
        assertThat(mobileService).contains(
                "mobile.android-latest-version-code",
                "mobile.android-force-upgrade"
        );
        assertThat(releaseDtos).contains("boolean forceUpgrade");
        assertThat(releaseController).contains("boolean forceUpgrade");
        assertThat(releaseService).contains("mobile.android-force-upgrade");
    }

    private String source(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
