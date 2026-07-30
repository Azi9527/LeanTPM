package com.leantpm.system.mapper;

import com.leantpm.system.attachment.AttachmentDtos;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface AttachmentRelationMapper {
    List<AttachmentDtos.AttachmentRelationRow> findRelationsByAttachmentIds(
            @Param("tenantId") long tenantId,
            @Param("attachmentIds") List<Long> attachmentIds
    );

    AttachmentDtos.AttachmentRelationRow findRelation(
            @Param("tenantId") long tenantId,
            @Param("relationId") long relationId
    );

    AttachmentDtos.AttachmentRelationRow findExactRelation(
            @Param("tenantId") long tenantId,
            @Param("attachmentId") long attachmentId,
            @Param("businessType") String businessType,
            @Param("businessId") long businessId,
            @Param("relationType") String relationType
    );

    int insertRelation(
            @Param("tenantId") long tenantId,
            @Param("attachmentId") long attachmentId,
            @Param("request") AttachmentDtos.SaveAttachmentRelationRequest request,
            @Param("operatorId") long operatorId
    );

    int deleteRelation(
            @Param("tenantId") long tenantId,
            @Param("relationId") long relationId
    );
}
