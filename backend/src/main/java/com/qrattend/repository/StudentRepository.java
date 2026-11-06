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

    /** Case-insensitive: "cs24b075" finds the student enrolled as "CS24B075". */
    @Query("SELECT s FROM Student s WHERE s.course.id = :courseId AND UPPER(s.rollNumber) = UPPER(:rollNumber)")
    Optional<Student> findByCourseIdAndRollNumber(@Param("courseId") UUID courseId,
                                                  @Param("rollNumber") String rollNumber);

    /** Case-insensitive: true if the course already has this roll number in any letter case. */
    @Query("SELECT COUNT(s) > 0 FROM Student s WHERE s.course.id = :courseId AND UPPER(s.rollNumber) = UPPER(:rollNumber)")
    boolean existsByCourseIdAndRollNumber(@Param("courseId") UUID courseId,
                                          @Param("rollNumber") String rollNumber);

    void deleteByCourseIdAndRollNumberIn(UUID courseId, List<String> rollNumbers);
}
