package com.walletsys.repository;

import com.walletsys.entity.OutboxEvent;
import com.walletsys.entity.enums.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Backing store for the transactional outbox poller. {@link #findPendingBatch} is the
 * hot query, run every {@code app.outbox.poll-interval-ms}; it is covered by the
 * partial index {@code idx_outbox_events_status_created_at} (WHERE status = 'PENDING')
 * so the scan cost stays constant regardless of how many historical PUBLISHED rows
 * accumulate.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, java.util.UUID> {

    @Query("SELECT o FROM OutboxEvent o WHERE o.status = :status ORDER BY o.createdAt ASC")
    List<OutboxEvent> findPendingBatch(@Param("status") OutboxStatus status, Pageable pageable);
}
