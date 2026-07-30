package com.leantpm.equipment;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.api.PageResult;
import com.leantpm.common.idempotency.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1")
public class EquipmentController {
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    private final EquipmentService service;

    public EquipmentController(EquipmentService service) {
        this.service = service;
    }

    @GetMapping("/equipment")
    @PreAuthorize("hasAuthority('equipment:ledger:view')")
    public ApiResponse<PageResult<EquipmentDtos.EquipmentRow>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) String currentStatusCode,
            @RequestParam(required = false) String lifecycleStage,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int pageSize
    ) {
        return ApiResponse.success(service.page(
                keyword, categoryId, organizationId, locationId, currentStatusCode,
                lifecycleStage, status, page, pageSize
        ));
    }

    @GetMapping("/equipment/{id}")
    @PreAuthorize("hasAuthority('equipment:ledger:view')")
    public ApiResponse<EquipmentDtos.EquipmentDetail> detail(@PathVariable long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping("/equipment")
    @Idempotent
    @PreAuthorize("hasAuthority('equipment:ledger:create')")
    public ApiResponse<Map<String, Long>> create(
            @Valid @RequestBody EquipmentDtos.SaveEquipmentRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.create(request)));
    }

    @PutMapping("/equipment/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('equipment:ledger:update')")
    public ApiResponse<Void> update(
            @PathVariable long id,
            @Valid @RequestBody EquipmentDtos.SaveEquipmentRequest request
    ) {
        service.update(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/equipment/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('equipment:ledger:delete')")
    public ApiResponse<Void> delete(
            @PathVariable long id,
            @RequestParam @Min(0) int version
    ) {
        service.delete(id, version);
        return ApiResponse.success();
    }

    @PostMapping("/equipment/{id}/copy")
    @Idempotent
    @PreAuthorize("hasAuthority('equipment:ledger:copy')")
    public ApiResponse<Map<String, Long>> copy(
            @PathVariable long id,
            @Valid @RequestBody EquipmentDtos.CopyEquipmentRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.copy(id, request)));
    }

    @PostMapping("/equipment/{id}/transfer")
    @Idempotent
    @PreAuthorize("hasAuthority('equipment:ledger:transfer')")
    public ApiResponse<Void> transfer(
            @PathVariable long id,
            @Valid @RequestBody EquipmentDtos.TransferRequest request
    ) {
        service.transfer(id, request);
        return ApiResponse.success();
    }

    @PutMapping("/equipment/{id}/current-status")
    @Idempotent
    @PreAuthorize("hasAuthority('equipment:status:update')")
    public ApiResponse<Void> changeStatus(
            @PathVariable long id,
            @Valid @RequestBody EquipmentDtos.ChangeStatusRequest request
    ) {
        service.changeStatus(id, request);
        return ApiResponse.success();
    }

    @GetMapping("/equipment/{id}/status-history")
    @PreAuthorize("hasAnyAuthority('equipment:ledger:view','equipment:status:view')")
    public ApiResponse<List<EquipmentDtos.StatusHistoryRow>> statusHistory(
            @PathVariable long id
    ) {
        return ApiResponse.success(service.statusHistory(id));
    }

    @GetMapping("/equipment/barcodes")
    @PreAuthorize("hasAuthority('equipment:barcode:view')")
    public ApiResponse<List<EquipmentDtos.BarcodeRow>> barcodes(
            @RequestParam(required = false) Long equipmentId,
            @RequestParam(defaultValue = "true") boolean activeOnly
    ) {
        return ApiResponse.success(service.barcodes(equipmentId, activeOnly));
    }

    @PostMapping("/equipment/{id}/barcode")
    @Idempotent
    @PreAuthorize("hasAuthority('equipment:barcode:manage')")
    public ApiResponse<EquipmentDtos.BarcodeRow> generateBarcode(
            @PathVariable long id,
            @Valid @RequestBody EquipmentDtos.GenerateBarcodeRequest request
    ) {
        return ApiResponse.success(service.generateBarcode(id, request, false));
    }

    @PostMapping("/equipment/{id}/barcode/regenerate")
    @Idempotent
    @PreAuthorize("hasAuthority('equipment:barcode:manage')")
    public ApiResponse<EquipmentDtos.BarcodeRow> regenerateBarcode(
            @PathVariable long id,
            @Valid @RequestBody EquipmentDtos.GenerateBarcodeRequest request
    ) {
        return ApiResponse.success(service.generateBarcode(id, request, true));
    }

    @DeleteMapping("/equipment/{id}/barcode")
    @Idempotent
    @PreAuthorize("hasAuthority('equipment:barcode:manage')")
    public ApiResponse<Void> unbindBarcode(
            @PathVariable long id,
            @RequestParam(required = false) @Size(max = 500) String reason
    ) {
        service.unbindBarcode(id, reason);
        return ApiResponse.success();
    }

    @GetMapping(value = "/equipment/barcodes/{id}/image", produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize("hasAnyAuthority('equipment:barcode:view','equipment:barcode:print')")
    public ResponseEntity<byte[]> barcodeImage(
            @PathVariable long id,
            @RequestParam(defaultValue = "320") @Min(120) @Max(1200) int width,
            @RequestParam(defaultValue = "120") @Min(60) @Max(600) int height
    ) {
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG)
                .body(service.barcodeImage(id, width, height));
    }

    @GetMapping("/public/equipment/{token}")
    public ApiResponse<EquipmentDtos.PublicEquipmentView> publicView(
            @PathVariable
            @Pattern(regexp = "^[a-fA-F0-9]{64}$", message = "二维码令牌格式不正确")
            String token
    ) {
        return ApiResponse.success(service.publicView(token));
    }

    @PostMapping(
            value = "/equipment/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @Idempotent
    @PreAuthorize("hasAuthority('equipment:ledger:import')")
    public ApiResponse<EquipmentDtos.ImportResult> importWorkbook(
            @RequestPart("file") MultipartFile file
    ) {
        return ApiResponse.success(service.importWorkbook(file));
    }

    @GetMapping("/equipment/import-template")
    @PreAuthorize("hasAuthority('equipment:ledger:import')")
    public ResponseEntity<byte[]> importTemplate() {
        return workbookResponse(service.importTemplate(), "LeanTPM-equipment-import-template.xlsx");
    }

    @GetMapping("/equipment/export")
    @PreAuthorize("hasAuthority('equipment:ledger:export')")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) String currentStatusCode,
            @RequestParam(required = false) String lifecycleStage,
            @RequestParam(required = false) Integer status
    ) {
        return workbookResponse(
                service.exportWorkbook(
                        keyword, categoryId, organizationId, locationId,
                        currentStatusCode, lifecycleStage, status
                ),
                "LeanTPM-equipment-ledger.xlsx"
        );
    }

    private ResponseEntity<byte[]> workbookResponse(byte[] body, String filename) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(XLSX)
                .body(body);
    }
}
