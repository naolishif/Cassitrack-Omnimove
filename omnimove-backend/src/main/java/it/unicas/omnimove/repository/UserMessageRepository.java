package it.unicas.omnimove.repository;

import it.unicas.omnimove.model.UserMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

@Repository
public interface UserMessageRepository extends JpaRepository<UserMessage, Long> {

    List<UserMessage> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndReadAtIsNull(Long userId);

    /**
     * Unread counts for every sender in one query.
     *
     * <p>The user list shows a marker per row, and asking per row would be one
     * query per user on a page that already lists them all.
     */
    @Query("SELECT m.userId, COUNT(m) FROM UserMessage m WHERE m.readAt IS NULL GROUP BY m.userId")
    List<Object[]> countUnreadByUser();

    /**
     * Marks this sender's messages read. Called when an operator opens the card.
     *
     * <p>{@code @Transactional} is not optional on a modifying query: JPA refuses
     * to run an update outside a transaction, and the caller — a plain GET on a
     * controller — has none of its own. Without it the whole request failed with
     * TransactionRequiredException and the card would not open at all.
     */
    @Modifying
    @Transactional
    @Query("UPDATE UserMessage m SET m.readAt = :now WHERE m.userId = :userId AND m.readAt IS NULL")
    int markRead(@Param("userId") Long userId, @Param("now") ZonedDateTime now);
}
