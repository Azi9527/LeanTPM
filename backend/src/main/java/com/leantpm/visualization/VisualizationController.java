package com.leantpm.visualization;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.common.idempotency.Idempotent;
import com.leantpm.system.attachment.AttachmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.core.io.Resource;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/visualization")
public class VisualizationController {
    private final VisualizationService service;
    private final VisualizationStreamService streamService;
    private final AttachmentService attachmentService;

    public VisualizationController(
            VisualizationService service,
            VisualizationStreamService streamService,
            AttachmentService attachmentService
    ) {
        this.service = service;
        this.streamService = streamService;
        this.attachmentService = attachmentService;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyAuthority("
            + "'visualization:cockpit:view','visualization:status:view',"
            + "'visualization:inspection:view','visualization:maintenance:view',"
            + "'visualization:oee:view')")
    public ApiResponse<VisualizationDtos.DashboardResult> dashboard(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(required = false) Long organizationId
    ) {
        return ApiResponse.success(service.dashboard(startDate, endDate, organizationId));
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyAuthority("
            + "'visualization:cockpit:view','visualization:3d:view',"
            + "'visualization:status:view','visualization:inspection:view',"
            + "'visualization:maintenance:view','visualization:oee:view')")
    public SseEmitter stream() {
        return streamService.subscribe();
    }

    @GetMapping("/scenes")
    @PreAuthorize("hasAnyAuthority('visualization:3d:view','visualization:scene:view')")
    public ApiResponse<List<VisualizationDtos.SceneSummary>> scenes() {
        return ApiResponse.success(service.scenes());
    }

    @GetMapping("/scenes/{id}")
    @PreAuthorize("hasAnyAuthority('visualization:3d:view','visualization:scene:view')")
    public ApiResponse<VisualizationDtos.SceneDetail> scene(@PathVariable long id) {
        return ApiResponse.success(service.scene(id));
    }

    @PostMapping("/scenes")
    @Idempotent
    @PreAuthorize("hasAuthority('visualization:scene:manage')")
    public ApiResponse<Map<String, Long>> createScene(
            @Valid @RequestBody VisualizationDtos.SaveSceneRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.createScene(request)));
    }

    @PutMapping("/scenes/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('visualization:scene:manage')")
    public ApiResponse<Void> updateScene(
            @PathVariable long id,
            @Valid @RequestBody VisualizationDtos.SaveSceneRequest request
    ) {
        service.updateScene(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/scenes/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('visualization:scene:manage')")
    public ApiResponse<Void> deleteScene(
            @PathVariable long id,
            @RequestParam @Min(0) int version
    ) {
        service.deleteScene(id, version);
        return ApiResponse.success();
    }

    @PostMapping("/scenes/{sceneId}/nodes")
    @Idempotent
    @PreAuthorize("hasAuthority('visualization:scene:manage')")
    public ApiResponse<Map<String, Long>> createNode(
            @PathVariable long sceneId,
            @Valid @RequestBody VisualizationDtos.SaveNodeRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.createNode(sceneId, request)));
    }

    @PutMapping("/nodes/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('visualization:scene:manage')")
    public ApiResponse<Void> updateNode(
            @PathVariable long id,
            @Valid @RequestBody VisualizationDtos.SaveNodeRequest request
    ) {
        service.updateNode(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/nodes/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('visualization:scene:manage')")
    public ApiResponse<Void> deleteNode(
            @PathVariable long id,
            @RequestParam @Min(0) int version
    ) {
        service.deleteNode(id, version);
        return ApiResponse.success();
    }

    @GetMapping("/equipment/{equipmentId}/snapshot")
    @PreAuthorize("hasAnyAuthority('visualization:3d:view','visualization:status:view')")
    public ApiResponse<VisualizationDtos.EquipmentSnapshot> equipmentSnapshot(
            @PathVariable long equipmentId
    ) {
        return ApiResponse.success(service.equipmentSnapshot(equipmentId));
    }

    @GetMapping("/models")
    @PreAuthorize("hasAnyAuthority('visualization:3d:view','visualization:scene:view')")
    public ApiResponse<List<VisualizationDtos.ModelResource>> models() {
        return ApiResponse.success(service.models());
    }

    @PostMapping("/models")
    @Idempotent
    @PreAuthorize("hasAuthority('visualization:model:manage')")
    public ApiResponse<Map<String, Long>> createModel(
            @Valid @RequestBody VisualizationDtos.SaveModelRequest request
    ) {
        return ApiResponse.success(Map.of("id", service.createModel(request)));
    }

    @PostMapping(
            path = "/models/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @Idempotent
    @PreAuthorize("hasAuthority('visualization:model:manage')")
    public ApiResponse<Map<String, Long>> uploadModel(
            @RequestPart("file") MultipartFile file,
            @Valid @RequestPart("request") VisualizationDtos.SaveModelRequest request
    ) {
        var attachment = attachmentService.store(file, null, null);
        var withAttachment = new VisualizationDtos.SaveModelRequest(
                request.resourceCode(), request.resourceName(), request.resourceLevel(),
                attachment.id(), request.modelFormat(), request.primitiveType(),
                request.fallbackColor(), request.thumbnailAttachmentId(),
                request.description(), request.status(), request.version()
        );
        return ApiResponse.success(Map.of("id", service.createModel(withAttachment)));
    }

    @PutMapping("/models/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('visualization:model:manage')")
    public ApiResponse<Void> updateModel(
            @PathVariable long id,
            @Valid @RequestBody VisualizationDtos.SaveModelRequest request
    ) {
        service.updateModel(id, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/models/{id}")
    @Idempotent
    @PreAuthorize("hasAuthority('visualization:model:manage')")
    public ApiResponse<Void> deleteModel(
            @PathVariable long id,
            @RequestParam @Min(0) int version
    ) {
        service.deleteModel(id, version);
        return ApiResponse.success();
    }

    @GetMapping("/models/{id}/content")
    @PreAuthorize("hasAnyAuthority('visualization:3d:view','visualization:scene:view')")
    public ResponseEntity<Resource> modelContent(@PathVariable long id) {
        VisualizationDtos.ModelResource model = service.model(id);
        if (model.attachmentId() == null) {
            return ResponseEntity.noContent().build();
        }
        var download = attachmentService.load(model.attachmentId());
        String contentType = download.record().contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : download.record().contentType();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(download.record().fileSize())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(
                                        download.record().originalName(),
                                        StandardCharsets.UTF_8
                                )
                                .build()
                                .toString()
                )
                .body(download.resource());
    }

    @GetMapping("/status-colors")
    @PreAuthorize("hasAnyAuthority('visualization:3d:view','visualization:scene:view')")
    public ApiResponse<List<VisualizationDtos.StatusColor>> statusColors() {
        return ApiResponse.success(service.statusColors());
    }

    @PutMapping("/status-colors/{statusCode}")
    @Idempotent
    @PreAuthorize("hasAuthority('visualization:status-color:manage')")
    public ApiResponse<Void> updateStatusColor(
            @PathVariable String statusCode,
            @Valid @RequestBody VisualizationDtos.SaveStatusColorRequest request
    ) {
        service.updateStatusColor(statusCode, request);
        return ApiResponse.success();
    }
}
