package com.leantpm.common.idempotency;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface IdempotencyMapper {
    int insertProcessing(
            @Param("tenantId") long tenantId,
            @Param("keyHash") String keyHash,
            @Param("fingerprint") String fingerprint,
            @Param("ownerToken") String ownerToken,
            @Param("leaseExpiresAt") Instant leaseExpiresAt,
            @Param("expiresAt") Instant expiresAt
    );

    IdempotencyRecord findForUpdate(
            @Param("tenantId") long tenantId,
            @Param("keyHash") String keyHash
    );

    int markUnknown(
            @Param("tenantId") long tenantId,
            @Param("keyHash") String keyHash,
            @Param("ownerToken") String ownerToken,
            @Param("fencingToken") long fencingToken,
            @Param("now") Instant now
    );

    int complete(
            @Param("tenantId") long tenantId,
            @Param("keyHash") String keyHash,
            @Param("ownerToken") String ownerToken,
            @Param("fencingToken") long fencingToken,
            @Param("responseStatus") int responseStatus,
            @Param("responseContentType") String responseContentType,
            @Param("responsePayload") byte[] responsePayload,
            @Param("completedAt") Instant completedAt,
            @Param("expiresAt") Instant expiresAt
    );

    int deleteExpired(
            @Param("tenantId") long tenantId,
            @Param("keyHash") String keyHash,
            @Param("now") Instant now
    );

    int deleteExpiredBatch(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );
}
