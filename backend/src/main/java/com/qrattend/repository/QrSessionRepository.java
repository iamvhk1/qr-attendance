package com.qrattend.repository;

import com.qrattend.entity.QrSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface QrSessionRepository extends JpaRepository<QrSession, UUID> {

    List<QrSession> findByCourseId(UUID courseId);

    List<QrSession> findByCourseIdOrderByCreatedAtDesc(UUID courseId);
}
