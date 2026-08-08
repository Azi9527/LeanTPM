package com.leantpm.mobile.release;

import com.leantpm.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppReleaseControllerTest {
    @Test
    void buildsDownloadUrlFromBrowserOriginInsteadOfBackendLoopbackAddress() {
        assertThat(AppReleaseController.appDownloadUrl("https://851xn5pikw00.guyubao.com"))
                .isEqualTo(
                        "https://851xn5pikw00.guyubao.com/api/v1/public/app/android/download"
                );
        assertThat(AppReleaseController.appDownloadUrl("http://192.168.31.91:15173"))
                .isEqualTo("http://192.168.31.91:15173/api/v1/public/app/android/download");
    }

    @Test
    void rejectsOriginsContainingPathsOrUnsupportedSchemes() {
        assertThatThrownBy(() -> AppReleaseController.appDownloadUrl("file:///tmp/app"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AppReleaseController.appDownloadUrl("https://example.com/path"))
                .isInstanceOf(BusinessException.class);
    }
}
