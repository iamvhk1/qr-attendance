package com.qrattend.repository;

import com.qrattend.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StudentRepository extends JpaRepository<Student, UUID> {

    List<Student> findByCourseId(UUID courseId);

    Optional<Student> findByCourseIdAndRollNumber(UUID courseId, String rollNumber);

    boolean existsByCourseIdAndRollNumber(UUID courseId, String rollNumber);

    void deleteByCourseIdAndRollNumberIn(UUID courseId, List<String> rollNumbers);
}
