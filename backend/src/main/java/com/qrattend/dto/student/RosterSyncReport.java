package com.qrattend.dto.student;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Report returned after an Excel roster sync operation.
 * Tells the professor exactly what changed in their student roster.
 */
@Data
@NoArgsConstructor @AllArgsConstructor @Builder
public class RosterSyncReport {

    /** Roll numbers of newly added students. */
    private List<String> added;

    /** Roll numbers of students removed from the roster. */
    private List<String> removed;

    /** Count of students that were already present and unchanged. */
    private int unchanged;

    /** Total student count in the course after the sync. */
    private int totalAfterSync;
}
