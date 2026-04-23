package com.aist.controller;

import com.aist.entity.Requirement;
import com.aist.service.RequirementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * REST Controller for Requirement management
 * Provides CRUD endpoints for requirement operations
 */
@RestController
@RequestMapping("/requirements")
public class RequirementController {

    @Autowired
    private RequirementService requirementService;

    /**
     * List requirements; with {@code onlyEnabled=true} return only rows where {@code enable=1}.
     */
    @GetMapping
    public ResponseEntity<List<Requirement>> getAllRequirements(
            @RequestParam(required = false, defaultValue = "false") boolean onlyEnabled) {
        if (onlyEnabled) {
            return ResponseEntity.ok(requirementService.lambdaQuery()
                    .eq(Requirement::getEnable, 1)
                    .orderByAsc(Requirement::getId)
                    .list());
        }
        return ResponseEntity.ok(requirementService.list());
    }

    /**
     * Get a requirement by ID
     *
     * @param id The requirement ID
     * @return The requirement if found, or 404 if not found
     */
    @GetMapping("/{id}")
    public ResponseEntity<Requirement> getRequirementById(@PathVariable Long id) {
        Requirement requirement = requirementService.getById(id);
        if (requirement != null) {
            return ResponseEntity.ok(requirement);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Create a new requirement
     *
     * @param requirement The requirement to create
     * @return The created requirement
     */
    @PostMapping
    public ResponseEntity<Requirement> createRequirement(@RequestBody Requirement requirement) {
        requirement.setRequirementTime(LocalDateTime.now());
        if (requirement.getEnable() == null) {
            requirement.setEnable(1);
        }
        if (requirement.getStatus() == null) {
            requirement.setStatus(0);
        }
        requirementService.save(requirement);
        return ResponseEntity.ok(requirement);
    }

    /**
     * Update an existing requirement
     *
     * @param id          The requirement ID
     * @param requirement The updated requirement data
     * @return The updated requirement if found, or 404 if not found
     */
    @PutMapping("/{id}")
    public ResponseEntity<Requirement> updateRequirement(@PathVariable Long id, @RequestBody Requirement requirement) {
        Requirement existingRequirement = requirementService.getById(id);
        if (existingRequirement == null) {
            return ResponseEntity.notFound().build();
        }
        requirement.setId(id);
        if (requirement.getStatus() == null
                && StringUtils.hasText(requirement.getAnalysisResults())) {
            String newAr = requirement.getAnalysisResults();
            String oldAr = existingRequirement.getAnalysisResults();
            if (oldAr == null || !newAr.equals(oldAr)) {
                requirement.setStatus(2);
            }
        }
        requirementService.updateById(requirement);
        return ResponseEntity.ok(requirement);
    }


    /**
     * Soft-delete: set {@code enable=0} (row retained). Call after the user confirms in the delete dialog.
     */
    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> softDeleteRequirement(@PathVariable Long id) {
        return softDeleteById(id);
    }

    /**
     * Same behavior as {@link #softDeleteRequirement(Long)}; kept for older clients.
     */

    private ResponseEntity<Void> softDeleteById(Long id) {
        Requirement existing = requirementService.getById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        requirementService.lambdaUpdate()
                .eq(Requirement::getId, id)
                .set(Requirement::getEnable, 0)
                .update();
        return ResponseEntity.ok().build();
    }

}
