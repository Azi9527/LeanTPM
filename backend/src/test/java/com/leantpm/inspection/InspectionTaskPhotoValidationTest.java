package com.leantpm.inspection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionTaskPhotoValidationTest {

    @Test
    void classifiesLegacyPositivePhotoMinimumAsResultPhoto() {
        assertThat(InspectionTaskService.usesResultPhotoAttachment("NORMAL_ABNORMAL", false, 1))
                .isTrue();
        assertThat(InspectionTaskService.usesResultPhotoAttachment("NORMAL_ABNORMAL", true, 0))
                .isTrue();
        assertThat(InspectionTaskService.usesResultPhotoAttachment("IMAGE", false, 0))
                .isTrue();
        assertThat(InspectionTaskService.usesResultPhotoAttachment("NORMAL_ABNORMAL", false, 0))
                .isFalse();
    }

    @Test
    void identifiesTheExactItemAndMissingPhotoCount() {
        var violation = new InspectionMapper.ResultAttachmentValidationRow(
                16L, 6, "润滑系统-注油泵01",
                0, 1, 2, 0, 0, 10, "image/jpeg,image/png"
        );

        assertThat(InspectionTaskService.attachmentValidationMessage(violation))
                .isEqualTo("第 6 项「润滑系统-注油泵01」：至少需要 1 张照片，当前 0 张");
    }

    @Test
    void explainsSizeAndContentTypeViolations() {
        var violation = new InspectionMapper.ResultAttachmentValidationRow(
                3L, 2, "传动系统-轴承",
                2, 0, 2, 1, 1, 5, "image/jpeg,image/png"
        );

        assertThat(InspectionTaskService.attachmentValidationMessage(violation))
                .contains("第 2 项「传动系统-轴承」")
                .contains("1 张照片超过单张 5 MB")
                .contains("1 张照片类型不支持");
    }
}
