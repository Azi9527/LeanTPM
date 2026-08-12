package com.leantpm.mobile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MobileDtosTest {

    @Test
    void forceLatestRaisesTheLegacyMinimumToLatestVersion() {
        var policy = new MobileDtos.AndroidVersionPolicy(
                100, "1.0.11", 104, true, "/download", "强制升级"
        );

        assertThat(policy.minimumVersionCode()).isEqualTo(104);
    }

    @Test
    void optionalUpgradePreservesConfiguredMinimum() {
        var policy = new MobileDtos.AndroidVersionPolicy(
                100, "1.0.11", 104, false, "/download", "可选升级"
        );

        assertThat(policy.minimumVersionCode()).isEqualTo(100);
    }

    @Test
    void forceLatestDoesNotLowerAStricterMinimum() {
        var policy = new MobileDtos.AndroidVersionPolicy(
                105, "1.0.11", 104, true, "/download", "强制升级"
        );

        assertThat(policy.minimumVersionCode()).isEqualTo(105);
    }
}
