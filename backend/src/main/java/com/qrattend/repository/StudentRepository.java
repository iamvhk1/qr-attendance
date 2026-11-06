package com.qrattend.repository;

import com.qrattend.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StudentRepository extends JpaRepository<Student, UUID> {

    List<Student> findByCourseId(UUID courseId);

    /**
     * All students of the course whose roll number matches ignoring letter case, oldest first.
     * Normally zero or one; more than one only if the course already holds case-different
     * duplicates (for example from an older import).
     */
    @Query("SELECT s FROM Student s WHERE s.course.id = :courseId AND UPPER(s.rollNumber) = UPPER(:rollNumber) ORDER BY s.createdAt ASC")
    List<Student> findAllByCourseIdAndRollNumberIgnoreCase(@Param("courseId") UUID courseId,
                                                           @Param("rollNumber") String rollNumber);

    /**
     * Case-insensitive: "cs24b075" finds the student enrolled as "CS24B075". If the course holds
     * case-different duplicates, the oldest one is returned instead of failing.
     */
    default Optional<Student> findByCourseIdAndRollNumber(UUID courseId, String rollNumber) {
        return findAllByCourseIdAndRollNumberIgnoreCase(courseId, rollNumber).stream().findFirst();
    }

    /** Case-insensitive: true if the course already has this roll number in any letter case. */
    @Query("SELECT COUNT(s) > 0 FROM Student s WHERE s.course.id = :courseId AND UPPER(s.rollNumber) = UPPER(:rollNumber)")
    boolean existsByCourseIdAndRollNumber(@Param("courseId") UUID courseId,
                                          @Param("rollNumber") String rollNumber);

    void deleteByCourseIdAndRollNumberIn(UUID courseId, List<String> rollNumbers);
}
